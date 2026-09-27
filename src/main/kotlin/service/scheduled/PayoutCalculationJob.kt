package org.example.service.scheduled

import org.example.model.ProcessingError
import org.example.model.enums.EventType
import org.example.model.enums.ProcessingErrorCode
import repository.store.ProcessingErrorStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import repository.store.PayoutStore
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.example.utils.Constants
import org.example.api.randomUuid
import org.example.model.MerchantBalance
import org.example.utils.logger

/**
 * Closes the day: snapshots every merchant's balance, then turns the positive
 * ones into payout rows. No external calls, so it can be re-run safely - the
 * (merchant, date) key makes a second run a no-op.
 *
 * A suspended merchant is snapshotted like any other but never paid: the
 * balance keeps accruing until the suspension is resolved.
 *
 * A negative balance is carried forward, not paid: future sales settle it.
 * After MERCHANT_NEGATIVE_DAYS_LIMIT days in the red it stops being a rounding artefact
 * and becomes a debt someone has to collect, so it goes to the review queue.
 */
class PayoutCalculationJob(
    private val payouts: PayoutStore,
    private val errors: ProcessingErrorStore,
    private val negativeDaysLimit: Int = Constants.Jobs.MERCHANT_NEGATIVE_DAYS_LIMIT,
    private val retryAttempts: Int = Constants.Jobs.RETRY_ATTEMPTS,
    private val retryDelayMs: Long = Constants.Jobs.RETRY_DELAY_MS,
    private val batchSize: Int = Constants.Jobs.BATCH_SIZE,
    private val reportingZone: ZoneId = Constants.Jobs.REPORTING_ZONE,
) {
    private val log = logger<PayoutCalculationJob>()

    suspend fun run(payoutDate: LocalDate): List<MerchantBalance> {
        log.info("Start payout on payoutDate=$payoutDate")

        require(batchSize in 1..Constants.Jobs.MAX_BATCH_SIZE)
        val computed = mutableListOf<MerchantBalance>()
        val failures = mutableListOf<Throwable>()
        var cursor: UUID? = null
        val endOfDay = payoutDate.plusDays(1).atStartOfDay(reportingZone).toInstant()
        val cutoff = payouts.processingCutoff(endOfDay)

        while (true) {
            val balances = retry("Load balance page after $cursor") {
                payouts.balancePage(cursor, batchSize, cutoff)
            }
            if (balances.isEmpty()) break

            try {
                retry("Record daily balance batch") {
                    payouts.recordDailyBalances(balances, payoutDate)
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) {
                    log.error("CancellationException on payoutDate=$payoutDate")
                    throw failure
                }

                failures += failure
                log.error("Failed to payout on payoutDate=$payoutDate and lastMerchantId=${balances.last().merchantId}")
            }

            val payable = balances.filter { it.amount.signum() > 0 && !it.suspended }
            try {
                computed += retry("compute payout batch") {
                    payouts.computePayouts(payable, payoutDate, cutoff)
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) {
                    log.error("CancellationException on payoutDate=$payoutDate")
                    throw failure
                }

                failures += failure
                log.error("Failed to payout on payoutDate=$payoutDate and lastMerchantId=${balances.last().merchantId}")
            }

            val processingErrors = mutableListOf<ProcessingError>()
            val negativeBalances = balances.filter { it.amount.signum() < 0 }
            val negativeDays = try {
                retry("load negative-balance history") {
                    payouts.consecutiveNegativeDays(negativeBalances.map { it.merchantId }, payoutDate)
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                failures += failure
                emptyMap()
            }
            negativeBalances.forEach { balance ->
                try {
                    overdueError(
                        balance.merchantId,
                        balance.amount,
                        payoutDate,
                        negativeDays[balance.merchantId] ?: 0,
                    )?.let(processingErrors::add)
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    failures += failure
                }
            }
            try {
                retry("store payout errors") { errors.saveAll(processingErrors) }
            } catch (failure: Throwable) {
                if (failure is CancellationException) {
                    log.error("CancellationException on payoutDate=$payoutDate")
                    throw failure
                }

                failures += failure
            }

            cursor = balances.last().merchantId
        }

        if (failures.isNotEmpty()) {
            throw PayoutCalculationFailed(payoutDate, failures)
        }

        log.info("Payout run date=$payoutDate computed=${computed.size}")
        return computed
    }

    private suspend fun overdueError(
        merchantId: UUID,
        balance: BigDecimal,
        payoutDate: LocalDate,
        days: Int,
    ): ProcessingError? {
        if (days < negativeDaysLimit) return null

        return ProcessingError(
            id = randomUuid(),
            eventType = EventType.PAYOUT,
            externalReference = "$merchantId-$payoutDate",
            payload = """{"merchantId":"$merchantId","balance":"$balance","negativeDays":$days}""",
            code = ProcessingErrorCode.NEGATIVE_BALANCE,
            detail = "$days consecutive negative days, balance $balance",
        )
    }

    private suspend fun <T> retry(name: String, block: suspend () -> T): T {
        require(retryAttempts > 0)
        var lastFailure: Throwable? = null

        repeat(retryAttempts) { attempt ->
            try {
                return block()
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                lastFailure = failure
                log.warn("event=retry step={} attempt={}/{}", name, attempt + 1, retryAttempts, failure)
                if (attempt + 1 < retryAttempts && retryDelayMs > 0) delay(retryDelayMs)
            }
        }

        throw lastFailure!!
    }

    private class PayoutCalculationFailed(date: LocalDate, failures: List<Throwable>) :
        RuntimeException("payout calculation $date failed for ${failures.size} operation(s)", failures.first())

}
