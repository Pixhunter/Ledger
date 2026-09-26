package org.example.tax

import org.example.model.enums.Currency
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaxRatesTest {
    private val rates = TaxRates()

    @Test
    fun `selects the rate effective at payment time`() {
        assertEquals(2000, rates.lookup("EE", Currency.EUR, Instant.parse("2023-12-31T23:59:59Z"))?.rate?.value)
        assertEquals(2200, rates.lookup("EE", Currency.EUR, Instant.parse("2024-01-01T00:00:00Z"))?.rate?.value)
        assertEquals(2400, rates.lookup("EE", Currency.EUR, Instant.parse("2025-07-01T00:00:00Z"))?.rate?.value)
    }

    @Test
    fun `does not invent a rate before retained history`() {
        assertNull(rates.lookup("ES", Currency.EUR, Instant.parse("2020-12-31T23:59:59Z")))
    }
}
