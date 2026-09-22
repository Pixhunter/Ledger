package org.example.api.controller

import org.example.api.model.PaymentRequestDto
import org.example.api.model.PaymentResponseDto
import org.example.service.PaymentService
import org.example.toModel
import org.slf4j.LoggerFactory

class LedgerController(
    private val payments: PaymentService,
) {
    private val log = LoggerFactory.getLogger(LedgerController::class.java)

    suspend fun createPayment(body: PaymentRequestDto): PaymentResponseDto {
        val payment = body.toModel()
        log.info("capture requestId=${payment.requestId} merchantId=${payment.merchantId}")

        return payments.capture(payment)
    }

    fun refundPayment() {

    }

    fun getMerchantBalanceWithMor() {

    }

    fun getMerchantMor() {

    }

    fun addMerchant() {

    }
}
