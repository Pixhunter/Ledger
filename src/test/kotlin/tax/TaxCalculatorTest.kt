package org.example.tax

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaxCalculatorTest {

    private val feeRate = BasisPoints(300)
    private val spain = BasisPoints(2100)
    private val germany = BasisPoints(1900)

    private val RATES = listOf(1000, 1900, 2000, 2100, 2400)

    @Test
    fun `extracts tax already contained in the charged amount`() {
        val tax = TaxCalculator.tax(12_100, spain)

        assertEquals(2_100, tax)
        assertEquals(300, TaxCalculator.fee(12_100 - tax, feeRate))
    }

    @Test
    fun `fee is charged on the net price, not on the tax`() {
        val tax = TaxCalculator.tax(12_100, spain)

        assertEquals(300, TaxCalculator.fee(12_100 - tax, feeRate))
        assertEquals(363, TaxCalculator.fee(12_100, feeRate))
    }

    @Test
    fun `rounds each component and leaves the remainder to the merchant`() {
        val tax = TaxCalculator.tax(10_000, germany)
        val fee = TaxCalculator.fee(10_000 - tax, feeRate)

        assertEquals(1_597, tax)
        assertEquals(252, fee)
        assertEquals(8_151, 10_000 - tax - fee)
    }

    @Test
    fun `reverse charge takes no tax at the applicable rate`() {
        assertEquals(0, TaxCalculator.tax(10_000, spain, reverseCharge = true))
    }

    @Test
    fun `zero rate takes no tax`() {
        assertEquals(0, TaxCalculator.tax(10_000, BasisPoints.ZERO))
    }

    @Test
    fun `tax and fee never exceed the amount`() {
        for (bps in RATES) {
            for (gross in 1L..5_000L) {
                val tax = TaxCalculator.tax(gross, BasisPoints(bps))
                val fee = TaxCalculator.fee(gross - tax, feeRate)

                assertTrue(tax >= 0 && fee >= 0, "gross=$gross bps=$bps")
                assertTrue(tax + fee <= gross, "gross=$gross bps=$bps tax=$tax fee=$fee")
            }
        }
    }

    @Test
    fun `tax on a single payment stays within half a minor unit of the exact value`() {
        for (bps in RATES) {
            for (gross in 1L..5_000L) {
                val tax = TaxCalculator.tax(gross, BasisPoints(bps))
                val error = 2 * (tax * (10_000 + bps) - gross * bps)

                assertTrue(abs(error) <= 10_000 + bps, "gross=$gross bps=$bps tax=$tax")
            }
        }
    }

    @Test
    fun `rounding does not drift towards the tax authority over many payments`() {
        for (bps in RATES) {
            var totalTax = 0L
            var totalGross = 0L
            for (gross in 1L..10_000L) {
                totalTax += TaxCalculator.tax(gross, BasisPoints(bps))
                totalGross += gross
            }

            val drift = totalTax * (10_000 + bps) - totalGross * bps
            assertTrue(abs(drift) <= 10_000 + bps, "bps=$bps drifted ${drift / (10_000 + bps)} units")
        }
    }

    @Test
    fun `rejects a non positive amount`() {
        assertFailsWith<IllegalArgumentException> { TaxCalculator.tax(0, spain) }
        assertFailsWith<IllegalArgumentException> { TaxCalculator.tax(-1, spain) }
    }
}
