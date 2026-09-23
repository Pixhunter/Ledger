package org.example.tax

import org.example.model.enums.Currency
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pure arithmetic. No database, no clock, no IO - so every rule below is
 * testable with a plain unit test, which matters more here than anywhere
 * else in the codebase: a bug in this file means money is wrong and a wrong
 * number gets filed with a government.
 */
object TaxCalculator {

    private val TEN_THOUSAND = BigDecimal(10_000)

    /**
     * Two rounding decisions, both deliberate:
     *
     *  - HALF_UP per component, the convention tax authorities expect.
     *  - The merchant's share is DERIVED as gross - tax - fee, never rounded
     *    independently. Rounding all three separately leaves a stray cent and
     *    the split stops summing to what the customer actually paid. The
     *    merchant absorbs the remainder, at most one minor unit.
     */
    fun split(
        amount: Long,
        currency: Currency,
        jurisdiction: String,
        taxRate: BasisPoints,
        taxMode: TaxMode,
        feeRate: BasisPoints,
        reverseCharge: Boolean = false,
    ): Split {
        require(amount > 0) { "amount must be positive, was $amount" }

        val rate = BigDecimal(taxRate.value)

        val gross: Long
        val net: Long
        val tax: Long

        when (taxMode) {
            TaxMode.EXCLUSIVE -> {
                net = amount
                tax = BigDecimal(net).multiply(rate)
                    .divide(TEN_THOUSAND, 0, RoundingMode.HALF_UP)
                    .toLong()
                gross = net + tax
            }

            TaxMode.INCLUSIVE -> {
                gross = amount
                net = BigDecimal(gross).multiply(TEN_THOUSAND)
                    .divide(TEN_THOUSAND.add(rate), 0, RoundingMode.HALF_UP)
                    .toLong()
                tax = gross - net
            }
        }

        val fee = BigDecimal(gross).multiply(BigDecimal(feeRate.value))
            .divide(TEN_THOUSAND, 0, RoundingMode.HALF_UP)
            .toLong()

        val merchant = gross - tax - fee

        check(merchant >= 0) {
            "fee and tax exceed the captured amount: gross=$gross tax=$tax fee=$fee"
        }

        return Split(
            gross = gross,
            net = net,
            tax = tax,
            fee = fee,
            merchant = merchant,
            currency = currency,
            jurisdiction = jurisdiction,
            taxRate = taxRate,
            taxMode = taxMode,
            reverseCharge = reverseCharge,
        )
    }
}
