package org.example.service.scheduled

import kotlinx.coroutines.runBlocking
import org.example.model.ProcessingError
import org.example.model.enums.EventType
import org.example.model.enums.ProcessingErrorCode
import org.example.repository.PayoutStore
import org.example.repository.ProcessingErrorStore
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.api.randomUuid
import org.example.model.MerchantBalance

class PayoutJobsTest {

    private val merchant = randomUuid()
    private val date = LocalDate.of(2026, 9, 25)

    @Test
    fun `a positive balance is snapshotted and paid out`() = runBlocking {
        val store = FakePayoutStore(balances = listOf(
            MerchantBalance(
                merchant,
                "Test merchant",
                BigDecimal("97.00"),
                3
            )
        ))
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

    private class FakePayoutStore(
        private val balances: List<MerchantBalance> = emptyList(),
        private val alreadyComputed: Boolean = false,
        private val negativeDays: Int = 0,
        private var computeFailures: Int = 0,
    ) : PayoutStore {

        val snapshots = mutableListOf<Pair<UUID, BigDecimal>>()
        val computed = mutableListOf<Pair<UUID, BigDecimal>>()
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
    }

    private class RecordingErrors : ProcessingErrorStore {
        val saved = mutableListOf<ProcessingError>()
        override suspend fun save(error: ProcessingError) { saved += error }
    }
}
