package org.example.support

import org.example.Constants
import java.math.BigDecimal

fun money(value: String): BigDecimal = BigDecimal(value).setScale(Constants.Amounts.STORAGE_SCALE)
