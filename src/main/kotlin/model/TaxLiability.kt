package org.example.model

import java.math.BigDecimal

data class TaxLiability(
    val country: String,
    val amount: BigDecimal,
    val payments: Int,
    val ratePercent: BigDecimal,
)