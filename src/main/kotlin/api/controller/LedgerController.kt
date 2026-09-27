package org.example.api.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import org.example.api.DtoMapper.recorded
import org.example.api.DtoMapper.rejected
import org.example.api.DtoMapper.toModel
import org.example.api.apiJson
import org.example.api.generated.model.ErrorReasonDto
import org.example.api.generated.model.PaymentRequestDto
import org.example.api.generated.model.RefundRequestDto
import org.example.api.security.PspSignature
import org.example.model.ProcessingError
import org.example.model.enums.EventType
import org.example.api.randomUuid
import org.example.repository.ProcessingErrorStore
import org.example.service.LedgerError
import org.example.service.LedgerResult
import org.example.service.PaymentService
import org.example.service.RefundService
import org.slf4j.LoggerFactory

/**
 * The PSP-facing half of the production API: verify the signature, parse, hand
 * to the service, turn the one [LedgerResult] into a status and a body.
 *
 * It owns its own routes, so there is no separate routing layer forwarding
 * strings into it. The dev API is a second surface, in DevRoutes.
 */
class LedgerController(
    private val payments: PaymentService,
    private val refunds: RefundService,
    private val errors: ProcessingErrorStore,
    private val signature: PspSignature,
) {
    private val log = LoggerFactory.getLogger(LedgerController::class.java)

    fun routes(route: Route) = with(route) {
        post("/v1/payment/capture") {
            handle(EventType.CAPTURE) { raw ->
                val request = apiJson.decodeFromString<PaymentRequestDto>(raw).toModel()
                log.info("payment psp={} merchant={}", request.pspReference, request.merchantId)
                payments.createPayment(request, raw)
            }
        }

        post("/v1/payment/refund") {
            handle(EventType.REFUND) { raw ->
                val request = apiJson.decodeFromString<RefundRequestDto>(raw).toModel()
                log.info("refund psp={} refund={}", request.pspReference, request.refundReference)
                refunds.createRefund(request, raw)
            }
        }
    }

    /** Signature, parse, run, answer - identical for both money events. */
    private suspend fun RoutingContext.handle(
        eventType: EventType,
        book: suspend (rawBody: String) -> LedgerResult,
    ) {
        val rawBody = call.receiveText()

        if (!signature.verify(rawBody.toByteArray(), call.request.headers[PspSignature.HEADER])) {
            log.warn("rejected unsigned {} request", eventType)
            return call.respond(HttpStatusCode.Unauthorized, rejected(ErrorReasonDto.INVALID_REQUEST))
        }

        val result = runCatching { book(rawBody) }.getOrElse { e ->
            log.warn("bad request: {}", e.message)
            return call.respond(HttpStatusCode.BadRequest, rejected(ErrorReasonDto.INVALID_REQUEST))
        }

        answer(result, eventType, rawBody)
    }

    private suspend fun RoutingContext.answer(
        result: LedgerResult,
        eventType: EventType,
        rawBody: String,
    ) = when (result) {
        // Recorded errors are persisted atomically with their money event by
        // the repository. Recording them again here would split that guarantee.
        is LedgerResult.Recorded,
        is LedgerResult.Duplicate,
        is LedgerResult.NothingToRecord -> call.respond(HttpStatusCode.OK, recorded())

        is LedgerResult.PaymentNotFound ->
            call.respond(HttpStatusCode.NotFound, rejected(ErrorReasonDto.INVALID_REQUEST))

        is LedgerResult.NotBookable -> {
            record(result.error, eventType, rawBody)
            call.respond(HttpStatusCode.OK, recorded())
        }
    }

    // TODO alert on every row, and again when one is older than 3 days.
    private suspend fun record(error: LedgerError, eventType: EventType, rawBody: String) =
        errors.save(
            ProcessingError(
                id = randomUuid(),
                eventType = eventType,
                externalReference = error.reference,
                payload = rawBody,
                code = error.code,
                detail = error.detail,
            )
        )
}
