package org.example.api.model

import kotlinx.serialization.Serializable

@Serializable
data class BillingAddressDto(
    val country: String,
    val stateOrProvince: String? = null,
    val postalCode: String? = null,
)
