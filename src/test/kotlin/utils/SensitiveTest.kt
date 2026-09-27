package org.example.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SensitiveTest {

    private val account = "DE89370400440532013000".sensitive()

    @Test
    fun `a sensitive value cannot be printed by any of the usual paths`() {
        assertEquals("***", account.toString())
        assertEquals("***", "$account")
        assertFalse("$account".contains("370400"))

        // A data class holding one does not leak it through its own toString.
        data class Transfer(val amount: String, val to: Sensitive<String>)
        assertFalse(Transfer("121.00", account).toString().contains("370400"))
    }

    @Test
    fun `reveal is the only way out`() {
        assertEquals("DE89370400440532013000", account.reveal())
    }

    @Test
    fun `a masked tail correlates without identifying`() {
        assertEquals("***3000", account.reveal().maskTail())
        assertEquals("***", "abc".maskTail(4))
    }
}
