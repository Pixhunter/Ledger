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
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.randomUuid

class PayoutJobsTest {

    private val merchant = randomUuid()
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
    fun `a transient merchant failure is retried without duplicating the payout`() = runBlocking {
        val store = FakePayoutStore(
            balances = listOf(MerchantBalance(merchant, "Test merchant", BigDecimal("97.00"), 3)),
            computeFailures = 1,
        )

        assertEquals(
            1,
            PayoutCalculationJob(store, RecordingErrors(), retryDelayMs = 0).run(date).size,
        )
        assertEquals(2, store.computeCalls)
        assertEquals(1, store.computed.size)
    }

    @Test
    fun `merchant balances are processed in bounded pages`() = runBlocking {
        val balances = List(5) {
            MerchantBalance(randomUuid(), "Merchant $it", BigDecimal("10.00"), 1)
        }
        val store = FakePayoutStore(balances = balances)

        assertEquals(
            5,
            PayoutCalculationJob(store, RecordingErrors(), batchSize = 2).run(date).size,
        )
        assertEquals(4, store.balancePageCalls)
        assertEquals(5, store.computed.size)
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
        private var computeFailures: Int = 0,
    ) : PayoutStore {

        val snapshots = mutableListOf<Pair<UUID, BigDecimal>>()
        val computed = mutableListOf<Pair<UUID, BigDecimal>>()
        val sent = mutableListOf<Pair<UUID, LocalDate>>()
        var computeCalls = 0
        var balancePageCalls = 0

        override suspend fun balancePage(afterMerchantId: UUID?, limit: Int, cutoff: Instant): List<MerchantBalance> {
            balancePageCalls++
            return balances.sortedBy { it.merchantId.toString() }
                .filter { afterMerchantId == null || it.merchantId.toString() > afterMerchantId.toString() }
                .take(limit)
        }

        override suspend fun recordDailyBalances(
            balances: List<MerchantBalance>,
            balanceDate: LocalDate,
        ) {
            snapshots += balances.map { it.merchantId to it.amount }
        }

        override suspend fun computePayouts(
            balances: List<MerchantBalance>,
            payoutDate: LocalDate,
            cutoff: Instant,
        ): List<MerchantBalance> {
            computeCalls++
            if (computeFailures > 0) {
                computeFailures--
                error("temporary database error")
            }
            if (alreadyComputed) return emptyList()
            computed += balances.map { it.merchantId to it.amount }
            return balances
        }

        override suspend fun consecutiveNegativeDays(merchantId: UUID, balanceDate: LocalDate) = negativeDays

        override suspend fun due(status: PayoutStatus, limit: Int) = due.take(limit)

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
