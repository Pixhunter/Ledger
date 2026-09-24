package org.example.api.controller

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.example.api.ApiResponse
import org.example.api.generated.model.ErrorReasonDto
import org.example.api.generated.model.PaymentRequestDto
import org.example.api.generated.model.PaymentResponseDto
import org.example.api.generated.model.RefundRequestDto
import org.example.api.generated.model.RefundResponseDto
import org.example.api.mapper.PaymentMapper.toModel
import org.example.api.paymentRecorded
import org.example.api.paymentRejected
import org.example.api.refundNotProcessed
import org.example.api.security.PspSignature
import org.example.model.RejectReason
import org.example.service.PaymentResult
import org.example.service.PaymentService
import org.slf4j.LoggerFactory

class LedgerController(
    private val payments: PaymentService,
    private val signature: PspSignature,
) {
    private val log = LoggerFactory.getLogger(LedgerController::class.java)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createPayment(rawBody: String, signatureHeader: String?): ApiResponse<PaymentResponseDto> {
        if (!signature.verify(rawBody.toByteArray(), signatureHeader)) {
            log.warn("rejected unsigned payment")
            return rejected(HttpStatusCode.Unauthorized)
        }

        val request = runCatching { json.decodeFromString<PaymentRequestDto>(rawBody).toModel() }
            .getOrElse { e ->
                log.warn("bad payment request: {}", e.message)
                return rejected(HttpStatusCode.BadRequest)
            }

        log.info("payment psp={} merchant={}", request.pspReference, request.merchantId)

        return when (val result = payments.createPayment(request)) {
            is PaymentResult.Rejected -> rejected(HttpStatusCode.BadRequest, result.reason)
            else -> ApiResponse(HttpStatusCode.OK, paymentRecorded())
        }
    }

    suspend fun createRefund(rawBody: String, signatureHeader: String?): ApiResponse<RefundResponseDto> {
        if (!signature.verify(rawBody.toByteArray(), signatureHeader)) {
            log.warn("rejected unsigned refund")
            return ApiResponse(HttpStatusCode.Unauthorized, refundNotProcessed(ErrorReasonDto.INVALID_REQUEST))
        }

        val request = runCatching { json.decodeFromString<RefundRequestDto>(rawBody) }
            .getOrElse { e ->
                log.warn("bad refund request: {}", e.message)
                return ApiResponse(HttpStatusCode.BadRequest, refundNotProcessed(ErrorReasonDto.INVALID_REQUEST))
            }

        log.info(
            "refund received, not recorded: refund={} payment={} amount={} {}",
            request.refundReference,
            request.pspReference,
            request.amount,
            request.currency,
        )

        return ApiResponse(HttpStatusCode.OK, refundNotProcessed())
    }

    private fun rejected(
        status: HttpStatusCode,
        reason: RejectReason = RejectReason.INVALID_REQUEST,
    ) = ApiResponse(status, paymentRejected(reason.toDto()))

    private fun RejectReason.toDto(): ErrorReasonDto = when (this) {
        RejectReason.INVALID_REQUEST -> ErrorReasonDto.INVALID_REQUEST
    }
}
