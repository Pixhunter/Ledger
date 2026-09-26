package org.example.payout

import kotlinx.coroutines.runBlocking
import org.example.model.ProcessingError
import org.example.model.enums.EventType
import org.example.model.enums.PayoutStatus
import org.example.model.enums.ProcessingErrorCode
import org.example.psp.PayoutRequest
import org.example.psp.PayoutResult
import org.example.psp.PspPayoutClient
import org.example.repository.ProcessingErrorStore
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PayoutJobsTest {

    private val merchant = UUID.randomUUID()
    private val date = LocalDate.of(2026, 9, 25)

    @Test
    fun `a positive balance is snapshotted and paid out`() = runBlocking {
        val store = FakePayoutStore(balances = listOf(MerchantBalance(merchant, "Test merchant", BigDecimal("97.00"), 3)))
        val errors = RecordingErrors()

        assertEquals(1, PayoutCalculationJob(store, errors).run(date).size)

        assertEquals(listOf(merchant to BigDecimal("97.00")), store.snapshots)
        assertEquals(listOf(merchant to BigDecimal("97.00")), store.computed)
        assertTrue(errors.saved.isEmpty())
    }

    @Test
    fun `a balance already computed is not paid twice`() = runBlocking {
        val store = FakePayoutStore(
            balances = listOf(MerchantBalance(merchant, "Test merchant", BigDecimal("97.00"), 3)),
            alreadyComputed = true,
        )

        assertTrue(PayoutCalculationJob(store, RecordingErrors()).run(date).isEmpty())
    }

    @Test
    fun `a negative balance is snapshotted, not paid, and not reported before the limit`() = runBlocking {
        val store = FakePayoutStore(
            balances = listOf(MerchantBalance(merchant, "Test merchant", BigDecimal("-40.00"), 2)),
            negativeDays = 13,
        )
        val errors = RecordingErrors()

        assertTrue(PayoutCalculationJob(store, errors).run(date).isEmpty())

        assertEquals(1, store.snapshots.size)
        assertTrue(store.computed.isEmpty())
        assertTrue(errors.saved.isEmpty())
    }

    @Test
    fun `a balance negative for the limit is reported to the review queue`() = runBlocking {
        val store = FakePayoutStore(
            balances = listOf(MerchantBalance(merchant, "Test merchant", BigDecimal("-40.00"), 2)),
            negativeDays = 14,
        )
        val errors = RecordingErrors()

        PayoutCalculationJob(store, errors).run(date)

        val error = errors.saved.single()
        assertEquals(ProcessingErrorCode.NEGATIVE_BALANCE, error.code)
        assertEquals(EventType.PAYOUT, error.eventType)
        assertTrue(error.detail.contains("14"))
        assertTrue(store.computed.isEmpty())
    }

    @Test
    fun `a zero balance produces neither a payout nor an error`() = runBlocking {
        val store = FakePayoutStore(balances = listOf(MerchantBalance(merchant, "Test merchant", BigDecimal.ZERO, 0)))
        val errors = RecordingErrors()

        assertTrue(PayoutCalculationJob(store, errors).run(date).isEmpty())
        assertTrue(store.computed.isEmpty())
        assertTrue(errors.saved.isEmpty())
    }

    @Test
    fun `an accepted payout is sent once with the merchant's PSP account`() = runBlocking {
        val store = FakePayoutStore(due = listOf(DuePayout(merchant, date, "acct-1", BigDecimal("97.00"))))
        val psp = RecordingPsp()

        assertEquals(1, PayoutDisbursementJob(store, psp).run())

        val request = psp.requests.single()
        assertEquals("payout-$merchant-$date", request.reference)
        assertEquals("acct-1", request.pspAccountId)
        assertEquals(BigDecimal("97.00"), request.amount)
        assertEquals(listOf(merchant to date), store.sent)
    }

    @Test
    fun `a rejected payout stays computed for the next run`() = runBlocking {
        val store = FakePayoutStore(due = listOf(DuePayout(merchant, date, "acct-1", BigDecimal("97.00"))))
        val psp = RecordingPsp(result = PayoutResult.Failed("account closed"))

        assertEquals(0, PayoutDisbursementJob(store, psp).run())
        assertTrue(store.sent.isEmpty())
    }

    private class FakePayoutStore(
        private val balances: List<MerchantBalance> = emptyList(),
        private val due: List<DuePayout> = emptyList(),
        private val alreadyComputed: Boolean = false,
        private val negativeDays: Int = 0,
    ) : PayoutStore {

        val snapshots = mutableListOf<Pair<UUID, BigDecimal>>()
        val computed = mutableListOf<Pair<UUID, BigDecimal>>()
        val sent = mutableListOf<Pair<UUID, LocalDate>>()

        override suspend fun balances() = balances

        override suspend fun recordDailyBalance(balance: MerchantBalance, balanceDate: LocalDate) {
            snapshots += balance.merchantId to balance.amount
        }

        override suspend fun consecutiveNegativeDays(merchantId: UUID, balanceDate: LocalDate) = negativeDays

        override suspend fun computePayout(balance: MerchantBalance, payoutDate: LocalDate): Boolean {
            if (alreadyComputed) return false
            computed += balance.merchantId to balance.amount
            return true
        }

        override suspend fun due(status: PayoutStatus) = due

        override suspend fun markSent(merchantId: UUID, payoutDate: LocalDate, pspReference: String) {
            sent += merchantId to payoutDate
        }
    }

    private class RecordingPsp(
        private val result: PayoutResult = PayoutResult.Accepted("psp-1"),
    ) : PspPayoutClient {
        val requests = mutableListOf<PayoutRequest>()

        override suspend fun payout(request: PayoutRequest): PayoutResult {
            requests += request
            return result
        }
    }

    private class RecordingErrors : ProcessingErrorStore {
        val saved = mutableListOf<ProcessingError>()
        override suspend fun save(error: ProcessingError) { saved += error }
    }
}
