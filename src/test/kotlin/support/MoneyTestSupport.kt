package org.example.support

import org.example.model.Money
import java.math.BigDecimal

fun money(value: String): BigDecimal = BigDecimal(value).setScale(Money.STORAGE_SCALE)
