package org.example.model

import org.example.model.enums.Country
import org.example.model.enums.Currency
import java.math.BigDecimal

data class Tax (
    val country: Country,
    val currency: Currency,

    val amount: BigDecimal, // let's have simple %
)