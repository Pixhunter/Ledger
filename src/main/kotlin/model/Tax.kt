package org.example.model

import org.example.model.enums.Country
import org.example.model.enums.Currency

data class Tax (
    val country: Country,
    val currency: Currency,

    val amount: Int, // let's have simple %
)