package org.example.api.model

import kotlinx.serialization.Serializable

/** Customer billing address as collected by the PSP. Strongest tax evidence. */
@Serializable
data class BillingAddressDto(
    val country: String,
    val stateOrProvince: String? = null,
    val postalCode: String? = null,
)
