package org.example.api.controller

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.example.api.ApiResponse
import org.example.api.generated.model.ErrorReasonDto
import org.example.api.generated.model.LedgerResponseDto
import org.example.api.generated.model.PaymentRequestDto
import org.example.api.generated.model.RefundRequestDto
import org.example.api.mapper.PaymentMapper.toModel
import org.example.api.mapper.RefundMapper.toModel
import org.example.api.recorded
import org.example.api.rejected
import org.example.api.security.PspSignature
import org.example.model.RejectReason
import org.example.service.LedgerResult
import org.example.service.PaymentService
import org.example.service.RefundService
import org.slf4j.LoggerFactory

class LedgerController(
    private val payments: PaymentService,
    private val refunds: RefundService,
    private val signature: PspSignature,
) {
    private val log = LoggerFactory.getLogger(LedgerController::class.java)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createPayment(rawBody: String, signatureHeader: String?): ApiResponse<LedgerResponseDto> {
        val request = parse(rawBody, signatureHeader) { json.decodeFromString<PaymentRequestDto>(it).toModel() }
            ?: return failed(HttpStatusCode.BadRequest)

        log.info("payment psp={} merchant={}", request.pspReference, request.merchantId)

        return answer(payments.createPayment(request))
    }

    suspend fun createRefund(rawBody: String, signatureHeader: String?): ApiResponse<LedgerResponseDto> {
        val request = parse(rawBody, signatureHeader) { json.decodeFromString<RefundRequestDto>(it).toModel() }
            ?: return failed(HttpStatusCode.BadRequest)

        log.info("refund psp={} refund={}", request.pspReference, request.refundReference)

        return answer(refunds.createRefund(request))
    }

    private fun <T> parse(rawBody: String, signatureHeader: String?, decode: (String) -> T): T? {
        if (!signature.verify(rawBody.toByteArray(), signatureHeader)) {
            log.warn("rejected unsigned request")
            return null
        }
        return runCatching { decode(rawBody) }.getOrElse { e ->
            log.warn("bad request: {}", e.message)
            null
        }
    }

    private fun answer(result: LedgerResult): ApiResponse<LedgerResponseDto> = when (result) {
        is LedgerResult.Recorded,
        is LedgerResult.Duplicate,
        is LedgerResult.NothingToRecord -> ApiResponse(HttpStatusCode.OK, recorded())

        is LedgerResult.PaymentNotFound -> failed(HttpStatusCode.NotFound)
        is LedgerResult.Rejected -> failed(HttpStatusCode.BadRequest, result.reason)
    }

    private fun failed(
        status: HttpStatusCode,
        reason: RejectReason = RejectReason.INVALID_REQUEST,
    ) = ApiResponse(status, rejected(reason.toDto()))

    private fun RejectReason.toDto(): ErrorReasonDto = when (this) {
        RejectReason.INVALID_REQUEST -> ErrorReasonDto.INVALID_REQUEST
    }
}
