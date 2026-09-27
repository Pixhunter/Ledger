package org.example.service.scheduled

import kotlinx.coroutines.runBlocking
import org.example.model.ProcessingError
import org.example.model.TaxLiability
import org.example.model.enums.EventType
import org.example.model.enums.ProcessingErrorCode
import org.example.repository.ProcessingErrorStore
import org.example.repository.TaxRemittanceStore
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
        private val alreadyFiled: Boolean = false,
        private val negativeDays: Int = 0,
    ) : TaxRemittanceStore {

        val snapshots = mutableListOf<Pair<String, BigDecimal>>()
        val computed = mutableListOf<Pair<String, BigDecimal>>()

        override suspend fun liabilities(cutoff: java.time.Instant) = liabilities
        override suspend fun recordDailyBalances(
            liabilities: List<TaxLiability>,
            balanceDate: LocalDate,
        ) {
            snapshots += liabilities.map { it.country to it.amount }
        }

        override suspend fun consecutiveNegativeDays(country: String, balanceDate: LocalDate) = negativeDays

        override suspend fun computeRemittance(
            country: String,
            periodStart: LocalDate,
            cutoff: java.time.Instant,
        ): java.math.BigDecimal? {
            if (alreadyFiled) return null
            val amount = liabilities.single { it.country == country }.amount
            computed += country to amount
            return amount
        }
    }

    private class RecordingErrors : ProcessingErrorStore {
        val saved = mutableListOf<ProcessingError>()
        override suspend fun save(error: ProcessingError) { saved += error }
    }
}
