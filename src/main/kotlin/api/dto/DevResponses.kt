package org.example.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class PayoutRunDto(
    val payoutDate: String,
    val merchants: Int,
    val totalAmount: String,
    val payouts: List<MerchantPayoutDto>,
)

@Serializable
data class MerchantPayoutDto(
    val merchantId: String,
    val merchantName: String,
    val payments: Int,
    val amount: String,
)

@Serializable
data class TaxRunDto(
    val period: String,
    val countries: Int,
    val totalAmount: String,
    val remittances: List<CountryRemittanceDto>,
)

@Serializable
data class CountryRemittanceDto(
    val country: String,
    val payments: Int,
    val ratePercent: String,
    val amount: String,
)

@Serializable
data class SentDto(val sent: Int)

@Serializable
data class HealthDto(val status: String)
