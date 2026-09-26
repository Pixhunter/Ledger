package org.example.payout

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.example.remittance.TaxBalanceMonitorJob
import org.example.remittance.TaxRemittanceCalculationJob
import org.example.remittance.TaxRemittanceDisbursementJob
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.example.Constants

/**
 * Closing the day and moving the money are an hour apart on purpose: if the
 * calculation is wrong or crashes, there is time to notice before any money
 * leaves. Europe/London as a zone, not a fixed offset, so the business date
 * does not shift twice a year.
 *
 * TODO one instance only. With several app instances every one of them fires;
 *      today the (merchant, date) key and the payout status make that
 *      harmless, but a real deploy needs a lock or an external scheduler.
 */
class PayoutScheduler(
    private val calculation: PayoutCalculationJob,
    private val disbursement: PayoutDisbursementJob,
    private val taxCalculation: TaxRemittanceCalculationJob,
    private val taxDisbursement: TaxRemittanceDisbursementJob,
    private val taxMonitor: TaxBalanceMonitorJob,
    private val zone: ZoneId = Constants.Jobs.REPORTING_ZONE,
) {
    private val log = LoggerFactory.getLogger(PayoutScheduler::class.java)

    fun start(scope: CoroutineScope) {
        scope.daily(Constants.Jobs.PAYOUT_CALCULATION_AT) {
            val businessDate = ZonedDateTime.now(zone).toLocalDate().minusDays(1)
            calculation.run(businessDate)
        }

        scope.daily(Constants.Jobs.PAYOUT_DISBURSEMENT_AT) { disbursement.run() }

        scope.daily(Constants.Jobs.TAX_MONITOR_AT) { taxMonitor.run(ZonedDateTime.now(zone).toLocalDate()) }

        // Tax is filed per country per month, on the 5th for the month before:
        // late refunds have a few days to land before the return is fixed.
        // Checked daily so a restart on the 6th does not skip a period.
        scope.daily(Constants.Jobs.TAX_CALCULATION_AT) {
            val today = ZonedDateTime.now(zone).toLocalDate()
            if (today.dayOfMonth == Constants.Jobs.TAX_FILING_DAY) taxCalculation.run(today.minusMonths(1).withDayOfMonth(1))
        }

        scope.daily(Constants.Jobs.TAX_DISBURSEMENT_AT) { taxDisbursement.run() }
    }

    private fun CoroutineScope.daily(at: LocalTime, block: suspend () -> Unit) = launch {
        while (isActive) {
            delay(until(at).toMillis())
            runCatching { block() }.onFailure { log.error("scheduled payout job at {} failed", at, it) }
        }
    }

    private fun until(at: LocalTime): Duration {
        val now = ZonedDateTime.now(zone)
        val today = now.with(at)
        return Duration.between(now, if (today.isAfter(now)) today else today.plusDays(1))
    }

}
