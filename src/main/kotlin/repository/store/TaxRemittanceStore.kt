package repository.store

import org.example.model.TaxLiability
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

interface TaxRemittanceStore {

    /**
     * Unsettled TAX balance per country with a tax point before [cutoff].
     * Refunds have already reduced it. Entries already covered by a filed
     * remittance are excluded, so what is left is what is still owed.
     */
    suspend fun liabilities(cutoff: Instant): List<TaxLiability>

    suspend fun recordDailyBalances(liabilities: List<TaxLiability>, balanceDate: LocalDate)

    /** Consecutive negative days for all requested countries, loaded in one query. */
    suspend fun consecutiveNegativeDays(
        countries: Collection<String>,
        balanceDate: LocalDate,
    ): Map<String, Int>

    /** Files all supplied countries in one set-based transaction. */
    suspend fun computeRemittances(
        liabilities: Collection<TaxLiability>,
        periodStart: LocalDate,
        cutoff: Instant,
    ): Map<String, BigDecimal>
}
