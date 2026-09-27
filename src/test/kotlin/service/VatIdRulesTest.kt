package org.example.service

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VatIdRulesTest {
    private val rules = VatIdRules()
    private val at = Instant.parse("2026-09-22T10:15:30Z")

    @Test
    fun `validates the format effective at payment time`() {
        assertTrue(rules.isValid("ES", "ESB12345678", at))
        assertTrue(rules.isValid("NL", "NL123456789B01", at))
        assertFalse(rules.isValid("ES", "DE123456789", at))
        assertFalse(rules.isValid("ES", "anything", at))
    }

    @Test
    fun `does not apply a rule before its valid date`() {
        assertFalse(rules.isValid("ES", "ESB12345678", Instant.parse("2020-12-31T23:59:59Z")))
    }
}
