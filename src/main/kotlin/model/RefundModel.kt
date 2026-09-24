package org.example.model

import org.example.model.enums.Currency
import org.example.model.enums.RefundReason
import java.math.BigDecimal
import java.time.Instant

data class RefundModel(
    val refundReference: String,
    val pspReference: String,
    val amount: BigDecimal,
    val currency: Currency,
    val success: Boolean,
    val reason: RefundReason,
    val refundedAt: Instant,
)
