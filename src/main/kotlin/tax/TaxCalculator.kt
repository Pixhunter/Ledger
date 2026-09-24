package org.example.tax

import org.example.model.Money
import org.example.model.enums.Currency
import java.math.BigDecimal
import java.math.RoundingMode

object TaxCalculator {

    private val TEN_THOUSAND = BigDecimal("10000")

    fun tax(
        gross: BigDecimal,
        rate: BasisPoints,
        currency: Currency,
        reverseCharge: Boolean = false,
    ): BigDecimal {
        require(gross.signum() > 0) { "gross must be positive, was $gross" }
        if (reverseCharge || rate.value == 0) return Money.ZERO

        val rateDecimal = BigDecimal.valueOf(rate.value.toLong())
        val exact = gross.multiply(rateDecimal)
            .divide(TEN_THOUSAND.add(rateDecimal), Money.STORAGE_SCALE + 8, RoundingMode.HALF_EVEN)
        return Money.calculated(exact, currency)
    }

    fun fee(net: BigDecimal, rate: BasisPoints, currency: Currency): BigDecimal {
        require(net.signum() >= 0) { "net must not be negative, was $net" }
        val exact = net.multiply(BigDecimal.valueOf(rate.value.toLong()))
            .divide(TEN_THOUSAND, Money.STORAGE_SCALE + 8, RoundingMode.HALF_EVEN)
        return Money.calculated(exact, currency)
    }
}
