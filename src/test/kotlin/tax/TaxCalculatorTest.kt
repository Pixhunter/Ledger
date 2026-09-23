package org.example.tax

import org.example.model.enums.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.math.abs

class TaxCalculatorTest {

    private val feeRate = BasisPoints(300)
    private val spain = BasisPoints(2100)
    private val germany = BasisPoints(1900)

    private val RATES = listOf(1000, 1900, 2000, 2100, 2400)

    @Test
    fun `extracts tax already contained in the charged amount`() {
        val split = split(gross = 12_100, taxRate = spain)

        assertEquals(12_100, split.gross)
        assertEquals(2_100, split.tax)
        assertEquals(300, split.fee)
        assertEquals(9_700, split.merchant)
    }

    @Test
    fun `fee is charged on the net price, not on the tax`() {
        val split = split(gross = 12_100, taxRate = spain)

        assertEquals(300, split.fee)
    }

    @Test
    fun `rounds each component and lets the merchant absorb the remainder`() {
        val split = split(gross = 10_000, taxRate = germany)

        assertEquals(1_597, split.tax)
        assertEquals(252, split.fee)
        assertEquals(8_151, split.merchant)
    }

    @Test
    fun `reverse charge takes no tax at the applicable rate`() {
        val split = split(gross = 10_000, taxRate = spain, reverseCharge = true)

        assertEquals(0, split.tax)
        assertEquals(300, split.fee)
        assertEquals(9_700, split.merchant)
    }

    @Test
    fun `zero rate leaves the whole amount to split between fee and merchant`() {
        val split = split(gross = 10_000, taxRate = BasisPoints.ZERO)

        assertEquals(0, split.tax)
        assertEquals(300, split.fee)
        assertEquals(9_700, split.merchant)
    }

    @Test
    fun `split reconciles for every amount at every rate`() {
        for (bps in RATES) {
            for (gross in 1L..5_000L) {
                val split = split(gross = gross, taxRate = BasisPoints(bps))
                assertEquals(gross, split.tax + split.fee + split.merchant)
                assertTrue(split.tax >= 0 && split.fee >= 0 && split.merchant >= 0)
            }
        }
    }

    @Test
    fun `tax on a single payment stays within half a minor unit of the exact value`() {
        for (bps in RATES) {
            for (gross in 1L..5_000L) {
                val tax = split(gross = gross, taxRate = BasisPoints(bps)).tax
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
                totalTax += split(gross = gross, taxRate = BasisPoints(bps)).tax
                totalGross += gross
            }
            val drift = totalTax * (10_000 + bps) - totalGross * bps
            assertTrue(abs(drift) <= 10_000 + bps, "bps=$bps drifted ${drift / (10_000 + bps)} units")
        }
    }

    @Test
    fun `rejects a non positive amount`() {
        assertFailsWith<IllegalArgumentException> { split(gross = 0, taxRate = spain) }
        assertFailsWith<IllegalArgumentException> { split(gross = -1, taxRate = spain) }
    }

    private fun split(
        gross: Long,
        taxRate: BasisPoints,
        reverseCharge: Boolean = false,
    ) = TaxCalculator.split(
        gross = gross,
        currency = Currency.EUR,
        jurisdiction = "ES",
        taxRate = taxRate,
        feeRate = feeRate,
        reverseCharge = reverseCharge,
    )
}
