package org.example.remittance

import kotlinx.coroutines.runBlocking
import org.example.model.ProcessingError
import org.example.model.enums.EventType
import org.example.model.enums.PayoutStatus
import org.example.model.enums.ProcessingErrorCode
import org.example.repository.ProcessingErrorStore
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaxJobsTest {

    private val period = LocalDate.of(2026, 8, 1)
    private val today = LocalDate.of(2026, 9, 5)

    @Test
    fun `every country owing tax is filed once`() = runBlocking {
        val store = FakeRemittanceStore(
            liabilities = listOf(
                TaxLiability("DE", BigDecimal("19.00"), 2, BigDecimal("21.00")),
                TaxLiability("ES", BigDecimal("21.00"), 2, BigDecimal("21.00")),
            )
        )

        assertEquals(2, TaxRemittanceCalculationJob(store).run(period).size)
        assertEquals(listOf("DE" to BigDecimal("19.00"), "ES" to BigDecimal("21.00")), store.computed)
    }

    @Test
    fun `a country whose refunds cancelled its sales is not filed`() = runBlocking {
        val store = FakeRemittanceStore(
            liabilities = listOf(
                TaxLiability("DE", BigDecimal("19.00"), 2, BigDecimal("21.00")),
                TaxLiability("ES", BigDecimal.ZERO, 2, BigDecimal("21.00")),
                TaxLiability("FR", BigDecimal("-5.00"), 2, BigDecimal("21.00")),
            )
        )

        assertEquals(1, TaxRemittanceCalculationJob(store).run(period).size)
        assertEquals(listOf("DE" to BigDecimal("19.00")), store.computed)
    }

    @Test
    fun `a period already filed is not filed again`() = runBlocking {
        val store = FakeRemittanceStore(
            liabilities = listOf(TaxLiability("DE", BigDecimal("19.00"), 2, BigDecimal("21.00"))),
            alreadyFiled = true,
        )

        assertTrue(TaxRemittanceCalculationJob(store).run(period).isEmpty())
    }

    @Test
    fun `an accepted remittance is sent once per country and period`() = runBlocking {
        val store = FakeRemittanceStore(due = listOf(DueRemittance("ES", period, BigDecimal("21.00"))))
        val authority = RecordingAuthority()

        assertEquals(1, TaxRemittanceDisbursementJob(store, authority).run())

        val request = authority.requests.single()
        assertEquals("tax-ES-$period", request.reference)
        assertEquals("ES", request.country)
        assertEquals(BigDecimal("21.00"), request.amount)
        assertEquals(listOf("ES" to period), store.sent)
    }

    @Test
    fun `a rejected remittance stays computed for the next run`() = runBlocking {
        val store = FakeRemittanceStore(due = listOf(DueRemittance("ES", period, BigDecimal("21.00"))))
        val authority = RecordingAuthority(result = RemittanceResult.Failed("portal down"))

        assertEquals(0, TaxRemittanceDisbursementJob(store, authority).run())
        assertTrue(store.sent.isEmpty())
    }

    @Test
    fun `an over-remitted country is snapshotted but not reported before the limit`() = runBlocking {
        val store = FakeRemittanceStore(
            liabilities = listOf(TaxLiability("ES", BigDecimal("-8.00"), 2, BigDecimal("21.00"))),
            negativeDays = 2,
        )
        val errors = RecordingErrors()

        assertEquals(0, TaxBalanceMonitorJob(store, errors).run(today))
        assertEquals(1, store.snapshots.size)
        assertTrue(errors.saved.isEmpty())
    }

    @Test
    fun `a country negative for the limit is reported to the review queue`() = runBlocking {
        val store = FakeRemittanceStore(
            liabilities = listOf(TaxLiability("ES", BigDecimal("-8.00"), 2, BigDecimal("21.00"))),
            negativeDays = 3,
        )
        val errors = RecordingErrors()

        assertEquals(1, TaxBalanceMonitorJob(store, errors).run(today))

        val error = errors.saved.single()
        assertEquals(ProcessingErrorCode.NEGATIVE_TAX_BALANCE, error.code)
        assertEquals(EventType.TAX_REMITTANCE, error.eventType)
        assertTrue(error.detail.contains("-8.00"))
    }

    @Test
    fun `a country still owing tax is never reported`() = runBlocking {
        val store = FakeRemittanceStore(
            liabilities = listOf(TaxLiability("DE", BigDecimal("19.00"), 2, BigDecimal("21.00"))),
            negativeDays = 99,
        )
        val errors = RecordingErrors()

        assertEquals(0, TaxBalanceMonitorJob(store, errors).run(today))
        assertTrue(errors.saved.isEmpty())
    }

    private class FakeRemittanceStore(
        private val liabilities: List<TaxLiability> = emptyList(),
        private val due: List<DueRemittance> = emptyList(),
        private val alreadyFiled: Boolean = false,
        private val negativeDays: Int = 0,
    ) : TaxRemittanceStore {

        val snapshots = mutableListOf<Pair<String, BigDecimal>>()
        val computed = mutableListOf<Pair<String, BigDecimal>>()
        val sent = mutableListOf<Pair<String, LocalDate>>()

        override suspend fun liabilities() = liabilities

        override suspend fun recordDailyBalance(liability: TaxLiability, balanceDate: LocalDate) {
            snapshots += liability.country to liability.amount
        }

        override suspend fun consecutiveNegativeDays(country: String, balanceDate: LocalDate) = negativeDays

        override suspend fun computeRemittance(liability: TaxLiability, periodStart: LocalDate): Boolean {
            if (alreadyFiled) return false
            computed += liability.country to liability.amount
            return true
        }

        override suspend fun due(status: PayoutStatus) = due

        override suspend fun markSent(country: String, periodStart: LocalDate, reference: String) {
            sent += country to periodStart
        }
    }

    private class RecordingAuthority(
        private val result: RemittanceResult = RemittanceResult.Accepted("tax-1"),
    ) : TaxAuthorityClient {
        val requests = mutableListOf<RemittanceRequest>()

        override suspend fun remit(request: RemittanceRequest): RemittanceResult {
            requests += request
            return result
        }
    }

    private class RecordingErrors : ProcessingErrorStore {
        val saved = mutableListOf<ProcessingError>()
        override suspend fun save(error: ProcessingError) { saved += error }
    }
}
