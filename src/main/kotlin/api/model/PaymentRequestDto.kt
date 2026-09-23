package org.example.api.model

import kotlinx.serialization.Serializable

@Serializable
data class PaymentRequestDto(
    val pspReference: String,
    val merchantId: String,
    val amount: Long,
    val currency: String,
    val billingAddress: BillingAddressDto? = null,
    val cardIssuingCountry: String,
    val ipCountry: String? = null,
    val customerVatId: String? = null,
    val capturedAt: String,
)
