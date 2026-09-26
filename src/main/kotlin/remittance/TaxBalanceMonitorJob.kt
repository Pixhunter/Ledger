package org.example.remittance

import org.example.model.ProcessingError
import org.example.model.enums.EventType
import org.example.model.enums.ProcessingErrorCode
import org.example.repository.ProcessingErrorStore
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Snapshots every country's tax balance daily.
 *
 * Negative means we remitted more than we owed - a refund landed after the
 * period was filed. The government does not give it back; it is recovered by
 * offsetting future sales in that country. A country with refunds and no new
 * sales never recovers, so after NEGATIVE_DAYS_LIMIT days it goes to the
 * review queue.
 */
class TaxBalanceMonitorJob(
    private val remittances: TaxRemittanceStore,
    private val errors: ProcessingErrorStore,
    private val negativeDaysLimit: Int = NEGATIVE_DAYS_LIMIT,
) {
    suspend fun run(balanceDate: LocalDate): Int {
        var reported = 0

        remittances.liabilities().forEach { liability ->
            remittances.recordDailyBalance(liability, balanceDate)

            if (liability.amount.signum() >= 0) return@forEach

            val days = remittances.consecutiveNegativeDays(liability.country, balanceDate)
            if (days < negativeDaysLimit) return@forEach

            report(liability.country, liability.amount, days, balanceDate)
            reported++
        }

        return reported
    }

    private suspend fun report(country: String, balance: BigDecimal, days: Int, date: LocalDate) =
        errors.save(
            ProcessingError(
                id = UUID.randomUUID(),
                eventType = EventType.TAX_REMITTANCE,
                externalReference = "$country-$date",
                payload = """{"country":"$country","balance":"$balance","negativeDays":$days}""",
                code = ProcessingErrorCode.NEGATIVE_TAX_BALANCE,
                detail = "$days consecutive negative days, over-remitted $balance",
            )
        )

    private companion object {
        const val NEGATIVE_DAYS_LIMIT = 3
    }
}
