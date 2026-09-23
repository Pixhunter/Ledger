package org.example.api.controller

import org.example.api.model.PaymentRequestDto
import org.example.api.model.PaymentResponseDto
import org.example.api.mapper.PaymentMapper.toModel
import org.example.service.PaymentResult
import org.example.service.PaymentService
import org.slf4j.LoggerFactory

class LedgerController(
    private val payments: PaymentService,
) {
    private val log = LoggerFactory.getLogger(LedgerController::class.java)

    suspend fun createPayment(body: PaymentRequestDto): Pair<PaymentResult, PaymentResponseDto> {
        val payment = body.toModel()
        log.info("payment psp={} merchant={}", payment.pspReference, payment.merchantId)

        val result = payments.createPayment(payment)
        return result to result.toResponse()
    }

}
