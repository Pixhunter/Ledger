package org.example.model

import org.example.model.enums.Country
import org.example.model.enums.Currency
import java.math.BigDecimal

data class Customer(
    val currency: Currency,
    val country: Country,
    val amount: BigDecimal,
)
