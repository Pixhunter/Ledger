package org.example.api.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.slf4j.MDCContext
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
import repository.store.ProcessingErrorStore
import model.LedgerError
import model.LedgerResult
import org.example.service.PaymentService
import org.example.service.RefundService
import org.example.utils.logger

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
    private val log = logger<LedgerController>()

    fun routes(route: Route) = with(route) {
        post("/v1/payment/capture") {
            log.info("Got request to capture payment")
            handle(
                eventType = EventType.CAPTURE,
                parse = { apiJson.decodeFromString<PaymentRequestDto>(it).toModel() },
                reference = { it.pspReference },
            ) { request, raw ->
                log.debug("merchant={} amount={} {}", request.merchantId, request.amount, request.currency)
                payments.createPayment(request, raw)
            }
        }

        post("/v1/payment/refund") {
            log.info("Got request to refund payment")
            handle(
                eventType = EventType.REFUND,
                parse = { apiJson.decodeFromString<RefundRequestDto>(it).toModel() },
                reference = { it.refundReference },
            ) { request, raw ->
                log.debug("payment={} amount={} {}", request.pspReference, request.amount, request.currency)
                refunds.createRefund(request, raw)
            }
        }
    }

    /** Signature, parse, run, answer - identical for both money events. */
    private suspend fun <T> RoutingContext.handle(
        eventType: EventType,
        parse: (rawBody: String) -> T,
        reference: (T) -> String,
        saveIncome: suspend (request: T, rawBody: String) -> LedgerResult,
    ) {
        val rawBody = call.receiveText()
        val startedAt = System.nanoTime()

        withContext(MDCContext(mapOf(EVENT to eventType.name.lowercase()))) {
            if (!signature.verify(rawBody.toByteArray(), call.request.headers[PspSignature.HEADER])) {
                log.info("Unsigned endpoint - provide secret key or report to fraud")
                finish(HttpStatusCode.Unauthorized, "unsigned", startedAt)
                call.respond(HttpStatusCode.Unauthorized, rejected(ErrorReasonDto.INVALID_REQUEST))
                return@withContext
            }

            val request = try {
                parse(rawBody)
            } catch (e: IllegalArgumentException) {
                log.error("Error while parsing request", e)

                finish(HttpStatusCode.BadRequest, "malformed", startedAt, e.message)
                call.respond(HttpStatusCode.BadRequest, rejected(ErrorReasonDto.INVALID_REQUEST))
                return@withContext
            }

            withContext(
                MDCContext(
                    mapOf(
                        EVENT to eventType.name.lowercase(),
                        REFERENCE to reference(request),
                    )
                )
            ) {
                val result = try {
                    saveIncome(request, rawBody)
                } catch (e: CancellationException) {
                    log.error("Got CancellationException while saving the payment", e)

                    throw e
                } catch (e: Exception) {
                    log.error("Error while saving the payment", e)

                    finish(HttpStatusCode.InternalServerError, "internal_error", startedAt, e.message, e)
                    call.respond(HttpStatusCode.InternalServerError)
                    return@withContext
                }

                answer(result, eventType, rawBody, startedAt)
            }
        }
    }

    /** The one line per request that says how it ended. */
    private fun finish(
        status: HttpStatusCode,
        outcome: String,
        startedAt: Long,
        detail: String? = null,
        cause: Throwable? = null,
    ) {
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000
        val line = "outcome={} status={} took={}ms"

        when {
            status.value >= 500 -> log.error(
                "Got INTERNAL SERVER error $line detail={}",
                outcome,
                status.value,
                tookMs,
                detail ?: "-",
                cause
            )

            status.value >= 400 && status != HttpStatusCode.NotFound ->
                log.warn("Got BAD REQUEST error: $line detail={}", outcome, status.value, tookMs, detail ?: "-")

            else -> log.info(line, outcome, status.value, tookMs)
        }
    }

    private suspend fun RoutingContext.answer(
        result: LedgerResult,
        eventType: EventType,
        rawBody: String,
        startedAt: Long,
    ) {
        when (result) {
            is LedgerResult.Recorded -> {
                log.info("Successfully record request")

                finish(HttpStatusCode.OK, "recorded status=${result.paymentStatus}", startedAt)
                call.respond(HttpStatusCode.OK, recorded())
            }

            is LedgerResult.Duplicate -> {
                log.info("Duplicated request")

                finish(HttpStatusCode.OK, "duplicate status=${result.paymentStatus}", startedAt)
                call.respond(HttpStatusCode.OK, recorded())
            }

            is LedgerResult.NothingToRecord -> {
                log.info("Nothing to record...")

                finish(HttpStatusCode.OK, "not_recorded", startedAt)
                call.respond(HttpStatusCode.OK, recorded())
            }

            is LedgerResult.PaymentNotFound -> {
                log.info("Payment not found for refund")
                finish(HttpStatusCode.NotFound, "payment_not_found", startedAt)
                call.respond(HttpStatusCode.NotFound, rejected(ErrorReasonDto.INVALID_REQUEST))
            }

            is LedgerResult.NotBookable -> {
                log.info("Can't record payment")

                record(result.error, eventType, rawBody)
                finish(HttpStatusCode.OK, "quarantined code=${result.error.code}", startedAt)
                call.respond(HttpStatusCode.OK, recorded())
            }
        }
    }

    // TODO alert on every row, and again when one is older than 1 day.
    private suspend fun record(error: LedgerError, eventType: EventType, rawBody: String) {
        log.info("Save an error while processing request eventType=${eventType.name} error=${error.code}")
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

    private companion object {
        /** MDC keys. Rendered by %X{ref} and %X{event} in logback.xml. */
        const val REFERENCE = "ref"
        const val EVENT = "event"
    }
}
