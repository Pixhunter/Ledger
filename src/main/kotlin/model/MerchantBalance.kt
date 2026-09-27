package org.example.model

import java.math.BigDecimal
import java.util.UUID

data class MerchantBalance(
    val merchantId: UUID,
    val merchantName: String,
    val amount: BigDecimal,
    val payments: Int,
    /** Not ACTIVE, or not in the merchant table at all: snapshot it, never pay it. */
    val suspended: Boolean = false,
)
