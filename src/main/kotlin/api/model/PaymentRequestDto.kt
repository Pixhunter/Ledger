package org.example.api.model

import kotlinx.serialization.Serializable

@Serializable
data class PaymentRequestDto(
    val requestId: String,
    val merchantId: String,
    val customerId: String,
    val amount: Long,
    val currency: String,
    val taxLocation: TaxLocationDto,
)
