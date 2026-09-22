package org.example.model

import org.example.model.enums.Country

data class Merchant (
    // based on call - looks like the currency can be not the same as tax
    val country: Country,
    val balance: Balance,
)