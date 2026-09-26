package org.example.psp

import org.example.model.enums.Currency
import java.math.BigDecimal

data class PayoutRequest(
    val reference: String,
    val pspAccountId: String,
    val amount: BigDecimal,
    val currency: Currency,
)
