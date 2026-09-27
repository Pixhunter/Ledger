package org.example.service.scheduled

import org.example.model.TaxLiability
import repository.store.TaxRemittanceStore
import java.time.LocalDate
import java.time.ZoneId
import org.example.utils.Constants
import org.example.utils.logger

/**
 * Closes a filing period: what a country owes for it becomes one remittance
 * row and one ledger transaction.
 *
 * The period is bounded by its end, not by "everything outstanding". Only TAX
 * entries with a tax point before the period end are filed, so a sale made on
 * filing day belongs to the next return, not this one. Entries a previous
 * return already settled are excluded, so re-running is a no-op and a late
 * entry dated inside a closed period is picked up by the next return as an
 * adjustment rather than reopening a filed one.
 *
 * A country whose balance is zero or negative is skipped: more was refunded
 * than sold, the credit stays unsettled in the ledger and reduces the next
 * period.
 */
class TaxRemittanceCalculationJob(
    private val remittances: TaxRemittanceStore,
    private val reportingZone: ZoneId = Constants.Jobs.REPORTING_ZONE,
) {
    private val log = logger<TaxRemittanceCalculationJob>()

    suspend fun run(periodStart: LocalDate): List<TaxLiability> {
        val periodEnd = periodStart.plusMonths(1).atStartOfDay(reportingZone).toInstant()
        val computed = mutableListOf<TaxLiability>()

        remittances.liabilities(periodEnd)
            .filter { it.amount.signum() > 0 }
            .forEach { liability ->
                val filed = remittances.computeRemittance(liability.country, periodStart, periodEnd)
                if (filed == null) log.info("event=tax_filing country={} period={} outcome=skipped", liability.country, periodStart)
                else computed += liability.copy(amount = filed)
            }

        log.info("event=tax_run period={} computed={}", periodStart, computed.size)
        return computed
    }
}
