package org.example.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class TaxBalanceDto(
    val country: String,
    val currency: String,
    val from: String?,
    val to: String,
    val owedAtStart: String,
    val movement: String,
    val owedAtEnd: String,
)

@Serializable
data class MerchantBalancesDto(
    val currency: String,
    /** The requested day, or null when the answer is the live balance. */
    val date: String?,
    val asOf: String,
    val merchants: List<MerchantBalanceDto>,
)

@Serializable
data class MerchantBalanceDto(
    val merchantId: String,
    val merchantName: String,
    val available: String,
    /** Absent for a past date: the daily snapshot records the merchant balance only. */
    val held: String? = null,
)
