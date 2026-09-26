package org.example.remittance

import org.example.model.enums.PayoutStatus
import java.math.BigDecimal
import java.time.LocalDate

data class TaxLiability(
    val country: String,
    val amount: BigDecimal,
    val payments: Int,
    val ratePercent: BigDecimal,
)

data class DueRemittance(
    val country: String,
    val periodStart: LocalDate,
    val amount: BigDecimal,
)

interface TaxRemittanceStore {

    /** Outstanding TAX balance per country. Refunds have already reduced it. */
    suspend fun liabilities(): List<TaxLiability>

    suspend fun recordDailyBalances(liabilities: List<TaxLiability>, balanceDate: LocalDate)

    /** How many days up to and including balanceDate this country has been negative. */
    suspend fun consecutiveNegativeDays(country: String, balanceDate: LocalDate): Int

    /** Remittance row plus its ledger transaction. False when the period is already filed. */
    suspend fun computeRemittance(liability: TaxLiability, periodStart: LocalDate): Boolean

    /** Atomically claims at most [limit] rows for one worker. */
    suspend fun due(status: PayoutStatus, limit: Int = 100): List<DueRemittance>

    suspend fun markSent(country: String, periodStart: LocalDate, reference: String)

    suspend fun release(country: String, periodStart: LocalDate) = Unit
}
