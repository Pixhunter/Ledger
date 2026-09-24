package org.example.tax

import org.example.model.enums.Currency
import org.example.support.money
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaxCalculatorTest {

    private val currency = Currency.EUR
    private val feeRate = BasisPoints(300)
    private val spain = BasisPoints(2100)
    private val germany = BasisPoints(1900)
    private val rates = listOf(1000, 1900, 2000, 2100, 2400)

    @Test
    fun `extracts tax already contained in the charged amount`() {
        val tax = TaxCalculator.tax(money("121.00"), spain, currency)

        assertEquals(money("21.00"), tax)
        assertEquals(money("3.00"), TaxCalculator.fee(money("121.00") - tax, feeRate, currency))
    }

    @Test
    fun `fee is charged on the net price not on tax`() {
        val gross = money("121.00")
        val tax = TaxCalculator.tax(gross, spain, currency)

        assertEquals(money("3.00"), TaxCalculator.fee(gross - tax, feeRate, currency))
        assertEquals(money("3.63"), TaxCalculator.fee(gross, feeRate, currency))
    }

    @Test
    fun `rounds each component to cents and leaves the remainder to merchant`() {
        val gross = money("100.00")
        val tax = TaxCalculator.tax(gross, germany, currency)
        val fee = TaxCalculator.fee(gross - tax, feeRate, currency)

        assertEquals(money("15.97"), tax)
        assertEquals(money("2.52"), fee)
        assertEquals(money("81.51"), gross - tax - fee)
    }

    @Test
    fun `half even rounding is used at a half cent`() {
        assertEquals(money("0.02"), TaxCalculator.fee(money("0.50"), BasisPoints(300), currency))
        assertEquals(money("0.02"), TaxCalculator.fee(money("0.75"), BasisPoints(200), currency))
    }

    @Test
    fun `reverse charge takes no tax at applicable rate`() {
        assertEquals(money("0"), TaxCalculator.tax(money("100.00"), spain, currency, reverseCharge = true))
    }

    @Test
    fun `zero rate takes no tax`() {
        assertEquals(money("0"), TaxCalculator.tax(money("100.00"), BasisPoints.ZERO, currency))
    }

    @Test
    fun `tax and fee never exceed amount`() {
        for (bps in rates) {
            for (cents in 1L..5_000L) {
                val gross = BigDecimal.valueOf(cents, 2).setScale(4)
                val tax = TaxCalculator.tax(gross, BasisPoints(bps), currency)
                val fee = TaxCalculator.fee(gross - tax, feeRate, currency)

                assertTrue(tax.signum() >= 0 && fee.signum() >= 0, "gross=$gross bps=$bps")
                assertTrue(tax + fee <= gross, "gross=$gross bps=$bps tax=$tax fee=$fee")
            }
        }
    }

    @Test
    fun `tax stays within half a cent of exact value`() {
        for (bps in rates) {
            for (cents in 1L..5_000L) {
                val gross = BigDecimal.valueOf(cents, 2)
                val rate = BigDecimal.valueOf(bps.toLong())
                val exact = gross.multiply(rate)
                    .divide(BigDecimal("10000").add(rate), 12, RoundingMode.HALF_EVEN)
                val tax = TaxCalculator.tax(gross, BasisPoints(bps), currency)

                assertTrue((tax - exact).abs() <= BigDecimal("0.005"), "gross=$gross bps=$bps tax=$tax")
            }
        }
    }

    @Test
    fun `calculated values use storage scale`() {
        val tax = TaxCalculator.tax(BigDecimal("121.1234"), spain, currency)
        val fee = TaxCalculator.fee(BigDecimal("100.1234"), feeRate, currency)

        assertEquals(4, tax.scale())
        assertEquals(4, fee.scale())
    }

    @Test
    fun `rejects a non positive gross amount`() {
        assertFailsWith<IllegalArgumentException> { TaxCalculator.tax(BigDecimal.ZERO, spain, currency) }
        assertFailsWith<IllegalArgumentException> { TaxCalculator.tax(BigDecimal("-0.01"), spain, currency) }
    }

    @Test
    fun `rejects a negative fee base`() {
        assertFailsWith<IllegalArgumentException> {
            TaxCalculator.fee(BigDecimal("-0.01"), feeRate, currency)
        }
    }
}
