package org.example.model

import org.example.model.enums.Country
import org.example.model.enums.Currency
import java.math.BigDecimal

data class Merchant (
    // based on call - looks like the currency can be not the same as tax
    val currency: Currency,
    val country: Country,
    val amount: BigDecimal,
)