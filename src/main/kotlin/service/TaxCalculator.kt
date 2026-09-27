package org.example.service

import org.example.model.Money
import org.example.model.enums.Currency
import org.example.repository.BasisPoints
import org.example.utils.Constants
import java.math.BigDecimal
import java.math.RoundingMode

object TaxCalculator {

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
            .divide(Constants.Amounts.TEN_THOUSAND.add(rateDecimal), Constants.Amounts.DIVISION_SCALE, RoundingMode.HALF_EVEN)
        return Money.calculated(exact, currency)
    }

    fun fee(net: BigDecimal, rate: BasisPoints, currency: Currency): BigDecimal {
        require(net.signum() >= 0) { "net must not be negative, was $net" }
        val exact = net.multiply(BigDecimal.valueOf(rate.value.toLong()))
            .divide(Constants.Amounts.TEN_THOUSAND, Constants.Amounts.DIVISION_SCALE, RoundingMode.HALF_EVEN)
        return Money.calculated(exact, currency)
    }
}