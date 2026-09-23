package org.example.tax

import org.example.model.enums.Currency
import java.math.BigDecimal
import java.math.RoundingMode

object TaxCalculator {
    fun split(
        gross: Long,
        currency: Currency,
        jurisdiction: String,
        taxRate: BasisPoints,
        feeRate: BasisPoints,
        reverseCharge: Boolean = false,
    ): Split {
        require(gross > 0) { "gross must be positive, was $gross" }

        val tax = when {
            reverseCharge -> 0L
            else -> divide(gross * taxRate.value, 10_000L + taxRate.value)
        }

        val net = gross - tax
        val fee = divide(net * feeRate.value, 10_000L)
        val merchant = net - fee

        check(merchant >= 0) {
            "fee and tax exceed the captured amount: gross=$gross tax=$tax fee=$fee"
        }

        return Split(
            gross = gross,
            tax = tax,
            fee = fee,
            merchant = merchant,
            currency = currency,
            jurisdiction = jurisdiction,
            taxRate = taxRate,
            reverseCharge = reverseCharge,
        )
    }

    private fun divide(numerator: Long, denominator: Long): Long =
        BigDecimal(numerator)
            .divide(BigDecimal(denominator), 0, RoundingMode.HALF_EVEN)
            .toLong()
}
