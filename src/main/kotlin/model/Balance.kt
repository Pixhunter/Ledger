package org.example.model

import org.example.model.enums.Currency
import java.math.BigDecimal

data class Balance(
    val amount: BigDecimal,
    val currency: Currency,
)
