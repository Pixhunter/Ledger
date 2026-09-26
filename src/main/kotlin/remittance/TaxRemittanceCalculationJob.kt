package org.example.remittance

import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * Closes a filing period: the outstanding TAX balance per country becomes one
 * remittance row and one ledger transaction. Refunds inside the period have
 * already reduced that balance, so nothing here knows about them.
 *
 * A country whose balance is zero or negative is skipped: more was refunded
 * than sold, the credit stays in the ledger and reduces the next period.
 */
class TaxRemittanceCalculationJob(private val remittances: TaxRemittanceStore) {

    private val log = LoggerFactory.getLogger(TaxRemittanceCalculationJob::class.java)

    suspend fun run(periodStart: LocalDate): Int {
        var computed = 0

        remittances.liabilities()
            .filter { it.amount.signum() > 0 }
            .forEach { liability ->
                if (remittances.computeRemittance(liability, periodStart)) computed++
                else log.info("{} for {} already filed", liability.country, periodStart)
            }

        log.info("tax remittance {}: {} computed", periodStart, computed)
        return computed
    }
}
