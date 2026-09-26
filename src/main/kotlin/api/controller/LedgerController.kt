package org.example.api.controller

import io.ktor.http.HttpStatusCode
import org.example.api.ApiResponse
import org.example.api.apiJson
import org.example.api.generated.model.ErrorReasonDto
import org.example.api.generated.model.LedgerResponseDto
import org.example.api.generated.model.PaymentRequestDto
import org.example.api.generated.model.RefundRequestDto
import org.example.api.mapper.PaymentMapper.toModel
import org.example.api.mapper.RefundMapper.toModel
import org.example.api.recorded
import org.example.api.rejected
import org.example.api.security.PspSignature
import org.example.model.ProcessingError
import org.example.model.RejectReason
import org.example.model.enums.EventType
import org.example.repository.ProcessingErrorStore
import org.example.service.LedgerError
import org.example.service.LedgerResult
import org.example.service.PaymentService
import org.example.service.RefundService
import java.util.UUID
import org.slf4j.LoggerFactory

class LedgerController(
    private val payments: PaymentService,
    private val refunds: RefundService,
    private val errors: ProcessingErrorStore,
    private val signature: PspSignature,
) {
    private val log = LoggerFactory.getLogger(LedgerController::class.java)

    suspend fun createPayment(rawBody: String, signatureHeader: String?): ApiResponse<LedgerResponseDto> {
        if (!signature.verify(rawBody.toByteArray(), signatureHeader)) {
            log.warn("rejected unsigned payment request")
            return failed(HttpStatusCode.Unauthorized)
        }

        val request = parse(rawBody) { apiJson.decodeFromString<PaymentRequestDto>(it).toModel() }
            ?: return failed(HttpStatusCode.BadRequest)

        log.info("payment psp={} merchant={}", request.pspReference, request.merchantId)

        return answer(payments.createPayment(request), EventType.CAPTURE, rawBody)
    }

    suspend fun createRefund(rawBody: String, signatureHeader: String?): ApiResponse<LedgerResponseDto> {
        if (!signature.verify(rawBody.toByteArray(), signatureHeader)) {
            log.warn("rejected unsigned refund request")
            return failed(HttpStatusCode.Unauthorized)
        }

        val request = parse(rawBody) { apiJson.decodeFromString<RefundRequestDto>(it).toModel() }
            ?: return failed(HttpStatusCode.BadRequest)

        log.info("refund psp={} refund={}", request.pspReference, request.refundReference)

        return answer(refunds.createRefund(request), EventType.REFUND, rawBody)
    }

    private fun <T> parse(rawBody: String, decode: (String) -> T): T? {
        return runCatching { decode(rawBody) }.getOrElse { e ->
            log.warn("bad request: {}", e.message)
            null
        }
    }

    private suspend fun answer(
        result: LedgerResult,
        eventType: EventType,
        rawBody: String,
    ): ApiResponse<LedgerResponseDto> = when (result) {
        is LedgerResult.Recorded -> {
            result.error?.let { record(it, eventType, rawBody) }
            ApiResponse(HttpStatusCode.OK, recorded())
        }

        is LedgerResult.Duplicate,
        is LedgerResult.NothingToRecord -> ApiResponse(HttpStatusCode.OK, recorded())

        is LedgerResult.PaymentNotFound -> failed(HttpStatusCode.NotFound)
        is LedgerResult.Rejected -> failed(HttpStatusCode.BadRequest, result.reason)

        is LedgerResult.NotBookable -> {
            record(result.error, eventType, rawBody)
            ApiResponse(HttpStatusCode.OK, recorded())
        }
    }

    // TODO alert on every row, and again when one is older than 3 days.
    private suspend fun record(error: LedgerError, eventType: EventType, rawBody: String) =
        errors.save(
            ProcessingError(
                id = UUID.randomUUID(),
                eventType = eventType,
                externalReference = error.reference,
                payload = rawBody,
                code = error.code,
                detail = error.detail,
            )
        )

    private fun failed(
        status: HttpStatusCode,
        reason: RejectReason = RejectReason.INVALID_REQUEST,
    ) = ApiResponse(status, rejected(reason.toDto()))

    private fun RejectReason.toDto(): ErrorReasonDto = when (this) {
        RejectReason.INVALID_REQUEST -> ErrorReasonDto.INVALID_REQUEST
    }
}
