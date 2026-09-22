package org.example.service

import org.example.api.model.PaymentResponseDto
import org.example.model.PaymentModel
import org.example.tax.TaxService

class PaymentService(
    private val taxes: TaxService,
) {
    suspend fun capture(payment: PaymentModel): PaymentResponseDto {
        taxes.recordCapture(payment)
        return PaymentResponseDto(state = "DONE")
    }
}
