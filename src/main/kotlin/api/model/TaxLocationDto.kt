package org.example.api.model

import kotlinx.serialization.Serializable

@Serializable
data class TaxLocationDto(
    val country: String,
    val subdivision: String? = null,
    val postalCode: String? = null,
    val taxId: String? = null,
    val customerType: String? = null,          // INDIVIDUAL | BUSINESS
    val evidence: List<TaxEvidenceDto> = emptyList(),
)

@Serializable
data class TaxEvidenceDto(
    val type: String,                          // BILLING_ADDRESS | IP_COUNTRY | CARD_BIN | PHONE_CODE
    val value: String,
)
