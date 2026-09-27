package org.example.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class TaxBalance(
    val country: String,
    val from: Instant?,
    val to: Instant,
    val owedAtStart: BigDecimal,
    val movement: BigDecimal,
    val owedAtEnd: BigDecimal,
)

data class MerchantBalanceView(
    val merchantId: UUID,
    val merchantName: String,
    val available: BigDecimal,
    /** Null for a past date: the daily snapshot records the merchant balance only. */
    val held: BigDecimal?,
)
