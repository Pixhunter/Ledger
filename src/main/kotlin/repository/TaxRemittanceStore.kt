package org.example.repository

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
}
