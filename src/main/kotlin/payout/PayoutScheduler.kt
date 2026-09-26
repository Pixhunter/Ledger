package org.example.payout

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

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
    private val zone: ZoneId = ZoneId.of("Europe/London"),
) {
    private val log = LoggerFactory.getLogger(PayoutScheduler::class.java)

    fun start(scope: CoroutineScope) {
        scope.daily(CALCULATION_AT) {
            val businessDate = ZonedDateTime.now(zone).toLocalDate().minusDays(1)
            calculation.run(businessDate)
        }

        scope.daily(DISBURSEMENT_AT) { disbursement.run() }
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

    private companion object {
        val CALCULATION_AT: LocalTime = LocalTime.MIDNIGHT
        val DISBURSEMENT_AT: LocalTime = LocalTime.of(1, 0)
    }
}
