package org.example.model

import org.example.model.enums.Currency
import org.example.support.money
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {

    @Test
    fun `EUR input with cents is preserved and stored at scale four`() {
        assertEquals(money("10.01"), Money.amount(BigDecimal("10.01"), Currency.EUR))
    }

    @Test
    fun `EUR input with more than two decimal places is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Money.amount(BigDecimal("10.001"), Currency.EUR)
        }
    }

    @Test
    fun `amount must fit numeric nineteen four`() {
        assertFailsWith<IllegalArgumentException> {
            Money.amount(BigDecimal("1000000000000000.00"), Currency.EUR)
        }
    }
}
