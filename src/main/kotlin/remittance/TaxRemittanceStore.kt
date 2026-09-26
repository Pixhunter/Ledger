package org.example.remittance

import org.example.model.enums.PayoutStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.example.Constants

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

    /**
     * Unsettled TAX balance per country with a tax point before [cutoff].
     * Refunds have already reduced it. Entries already covered by a filed
     * remittance are excluded, so what is left is what is still owed.
     */
    suspend fun liabilities(cutoff: Instant): List<TaxLiability>

    suspend fun recordDailyBalances(liabilities: List<TaxLiability>, balanceDate: LocalDate)

    /** How many days up to and including balanceDate this country has been negative. */
    suspend fun consecutiveNegativeDays(country: String, balanceDate: LocalDate): Int

    /**
     * Files one country's period: settles every unsettled TAX entry dated
     * before [cutoff] and books the remittance against exactly that amount.
     * Returns what was filed, or null when the period is already filed or
     * nothing is owed. The settle and the sum are one statement, so an entry
     * arriving mid-run is either fully inside this filing or fully outside it.
     */
    suspend fun computeRemittance(
        country: String,
        periodStart: LocalDate,
        cutoff: Instant,
    ): BigDecimal?

    /** Atomically claims at most [limit] rows for one worker. */
    suspend fun due(status: PayoutStatus, limit: Int = Constants.Jobs.CLAIM_LIMIT): List<DueRemittance>

    suspend fun markSent(country: String, periodStart: LocalDate, reference: String)

    suspend fun release(country: String, periodStart: LocalDate) = Unit
}
