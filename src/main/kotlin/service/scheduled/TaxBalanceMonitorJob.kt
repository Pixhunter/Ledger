package org.example.service.scheduled

import org.example.model.ProcessingError
import org.example.model.enums.EventType
import org.example.model.enums.ProcessingErrorCode
import repository.store.TaxRemittanceStore
import repository.store.ProcessingErrorStore
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.example.utils.Constants
import org.example.api.randomUuid

/**
 * Snapshots every country's tax balance daily.
 *
 * Negative means we remitted more than we owed - a refund landed after the
 * period was filed. The government does not give it back; it is recovered by
 * offsetting future sales in that country. A country with refunds and no new
 * sales never recovers, so after TAX_NEGATIVE_DAYS_LIMIT days it goes to the
 * review queue.
 */
class TaxBalanceMonitorJob(
    private val remittances: TaxRemittanceStore,
    private val errors: ProcessingErrorStore,
    private val negativeDaysLimit: Int = Constants.Jobs.TAX_NEGATIVE_DAYS_LIMIT,
    private val reportingZone: ZoneId = Constants.Jobs.REPORTING_ZONE,
) {
    suspend fun run(balanceDate: LocalDate): Int {
        var reported = 0
        val processingErrors = mutableListOf<ProcessingError>()

        val endOfDay = balanceDate.plusDays(1).atStartOfDay(reportingZone).toInstant()
        val liabilities = remittances.liabilities(endOfDay)
        remittances.recordDailyBalances(liabilities, balanceDate)
        val negative = liabilities.filter { it.amount.signum() < 0 }
        val negativeDays = remittances.consecutiveNegativeDays(negative.map { it.country }, balanceDate)

        negative.forEach { liability ->
            val days = negativeDays[liability.country] ?: 0
            if (days < negativeDaysLimit) return@forEach

            processingErrors += error(liability.country, liability.amount, days, balanceDate)
            reported++
        }
        errors.saveAll(processingErrors)

        return reported
    }

    private fun error(country: String, balance: BigDecimal, days: Int, date: LocalDate) =
        ProcessingError(
            id = randomUuid(),
            eventType = EventType.TAX_REMITTANCE,
            externalReference = "$country-$date",
            payload = """{"country":"$country","balance":"$balance","negativeDays":$days}""",
            code = ProcessingErrorCode.NEGATIVE_TAX_BALANCE,
            detail = "$days consecutive negative days, over-remitted $balance",
        )

}
