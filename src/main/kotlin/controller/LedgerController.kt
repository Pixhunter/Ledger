package org.example.controller

import io.klogging.Klogging
import org.example.service.PaymentService

class LedgerController(
    private val paymentService: PaymentService
) {
    companion object: Klogging

    suspend fun createPayment() {
        logger.info { "Add payment from customer to merchant" }
        paymentService.addPayment()
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