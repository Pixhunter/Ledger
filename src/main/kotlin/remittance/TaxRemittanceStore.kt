package org.example.remittance

import org.example.model.enums.PayoutStatus
import java.math.BigDecimal
import java.time.LocalDate

data class TaxLiability(val country: String, val amount: BigDecimal)

data class DueRemittance(
    val country: String,
    val periodStart: LocalDate,
    val amount: BigDecimal,
)

interface TaxRemittanceStore {

    /** Outstanding TAX balance per country. Refunds have already reduced it. */
    suspend fun liabilities(): List<TaxLiability>

    suspend fun recordDailyBalance(liability: TaxLiability, balanceDate: LocalDate)

    /** How many days up to and including balanceDate this country has been negative. */
    suspend fun consecutiveNegativeDays(country: String, balanceDate: LocalDate): Int

    /** Remittance row plus its ledger transaction. False when the period is already filed. */
    suspend fun computeRemittance(liability: TaxLiability, periodStart: LocalDate): Boolean

    suspend fun due(status: PayoutStatus): List<DueRemittance>

    suspend fun markSent(country: String, periodStart: LocalDate, reference: String)
}
