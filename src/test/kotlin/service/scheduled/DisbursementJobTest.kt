package org.example.service.scheduled

import kotlinx.coroutines.runBlocking
import org.example.repository.DueTransfer
import org.example.repository.TransferKind
import org.example.repository.TransferResult
import org.example.support.FakeTransferStore
import org.example.support.RecordingTransferClient
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisbursementJobTest {

    private val date = LocalDate.of(2026, 9, 25)
    private val merchant = UUID.randomUUID()

    private fun payout() = DueTransfer(
        kind = TransferKind.PAYOUT,
        key = merchant.toString(),
        period = date,
        destination = "acct-1",
        amount = BigDecimal("97.00"),
    )

    private fun tax() = DueTransfer(
        kind = TransferKind.TAX,
        key = "ES",
        period = date,
        destination = "ES",
        amount = BigDecimal("21.00"),
    )

    @Test
    fun `an accepted transfer is sent once and marked with the external reference`() = runBlocking {
        val store = FakeTransferStore(listOf(payout()))
        val client = RecordingTransferClient()

        assertEquals(1, DisbursementJob(TransferKind.PAYOUT, store, client).run())

        assertEquals(listOf("payout-$merchant-$date"), client.references)
        assertEquals("acct-1", client.sent.single().destination)
        assertEquals("ext-payout-$merchant-$date", store.marked.single().second)
        assertTrue(store.released.isEmpty())
    }

    @Test
    fun `a rejected transfer is released for the next run`() = runBlocking {
        val store = FakeTransferStore(listOf(payout()))
        val client = RecordingTransferClient { TransferResult.Failed("account closed") }

        assertEquals(0, DisbursementJob(TransferKind.PAYOUT, store, client).run())

        assertTrue(store.marked.isEmpty())
        assertEquals(1, store.released.size)
    }

    @Test
    fun `the reference distinguishes a tax remittance from a payout`() = runBlocking {
        val store = FakeTransferStore(listOf(tax()))
        val client = RecordingTransferClient()

        assertEquals(1, DisbursementJob(TransferKind.TAX, store, client).run())
        assertEquals(listOf("tax-ES-$date"), client.references)
    }
}
