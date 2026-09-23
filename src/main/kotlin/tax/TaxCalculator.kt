package org.example.tax

import java.math.BigDecimal
import java.math.RoundingMode

object TaxCalculator {

    fun tax(gross: Long, rate: BasisPoints, reverseCharge: Boolean = false): Long {
        require(gross > 0) { "gross must be positive, was $gross" }
        if (reverseCharge) return 0
        return divide(gross * rate.value.toLong(), 10_000L + rate.value)
    }

    fun fee(net: Long, rate: BasisPoints): Long {
        require(net >= 0) { "net must not be negative, was $net" }
        return divide(net * rate.value.toLong(), 10_000L)
    }

    private fun divide(numerator: Long, denominator: Long): Long =
        BigDecimal(numerator)
            .divide(BigDecimal(denominator), 0, RoundingMode.HALF_EVEN)
            .toLong()
}
