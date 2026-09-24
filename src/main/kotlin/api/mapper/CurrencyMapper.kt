package org.example.api.mapper

import org.example.api.generated.model.CurrencyDto
import org.example.model.enums.Currency

fun CurrencyDto.toDomain(): Currency = when (this) {
    CurrencyDto.EUR -> Currency.EUR
}
