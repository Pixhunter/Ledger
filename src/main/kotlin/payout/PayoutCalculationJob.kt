package org.example.payout

import org.example.model.ProcessingError
import org.example.model.enums.EventType
import org.example.model.enums.ProcessingErrorCode
import org.example.repository.ProcessingErrorStore
import org.slf4j.LoggerFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Closes the day: snapshots every merchant's balance, then turns the positive
 * ones into payout rows. No external calls, so it can be re-run safely - the
 * (merchant, date) key makes a second run a no-op.
 *
 * A negative balance is carried forward, not paid: future sales settle it.
 * After NEGATIVE_DAYS_LIMIT days in the red it stops being a rounding artefact
 * and becomes a debt someone has to collect, so it goes to the review queue.
 */
class PayoutCalculationJob(
    private val payouts: PayoutStore,
    private val errors: ProcessingErrorStore,
    private val negativeDaysLimit: Int = NEGATIVE_DAYS_LIMIT,
    private val retryAttempts: Int = 3,
    private val retryDelayMs: Long = 100,
    private val batchSize: Int = 500,
    private val reportingZone: ZoneId = ZoneId.of("Europe/London"),
) {
    private val log = LoggerFactory.getLogger(PayoutCalculationJob::class.java)

    suspend fun run(payoutDate: LocalDate): List<MerchantBalance> {
        require(batchSize in 1..1000)
        val computed = mutableListOf<MerchantBalance>()
        val failures = mutableListOf<Throwable>()
        var cursor: UUID? = null
        val endOfDay = payoutDate.plusDays(1).atStartOfDay(reportingZone).toInstant()
        val cutoff = payouts.processingCutoff(endOfDay)

        while (true) {
            val balances = retry("load balance page after $cursor") {
                payouts.balancePage(cursor, batchSize, cutoff)
            }
            if (balances.isEmpty()) break

            try {
                retry("record daily balance batch") {
                    payouts.recordDailyBalances(balances, payoutDate)
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                failures += failure
                log.error("daily balance batch ending at {} failed", balances.last().merchantId, failure)
            }

            val positive = balances.filter { it.amount.signum() > 0 }
            try {
                computed += retry("compute payout batch") {
                    payouts.computePayouts(positive, payoutDate, cutoff)
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                failures += failure
                log.error("payout batch ending at {} failed", balances.last().merchantId, failure)
            }

            val processingErrors = mutableListOf<ProcessingError>()
            balances.filter { it.amount.signum() < 0 }.forEach { balance ->
                try {
                    retry("check negative balance ${balance.merchantId}") {
                        overdueError(balance.merchantId, balance.amount, payoutDate)
                    }?.let(processingErrors::add)
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    failures += failure
                }
            }
            try {
                retry("store payout errors") { errors.saveAll(processingErrors) }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                failures += failure
            }

            cursor = balances.last().merchantId
        }

        if (failures.isNotEmpty()) {
            throw PayoutCalculationFailed(payoutDate, failures)
        }

        log.info("payout run {}: {} computed", payoutDate, computed.size)
        return computed
    }

    private suspend fun overdueError(
        merchantId: UUID,
        balance: BigDecimal,
        payoutDate: LocalDate,
    ): ProcessingError? {
        val days = payouts.consecutiveNegativeDays(merchantId, payoutDate)
        if (days < negativeDaysLimit) return null

        return ProcessingError(
            id = UUID.randomUUID(),
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
                log.warn("{} failed (attempt {}/{})", name, attempt + 1, retryAttempts, failure)
                if (attempt + 1 < retryAttempts && retryDelayMs > 0) delay(retryDelayMs)
            }
        }

        throw lastFailure!!
    }

    private class PayoutCalculationFailed(date: LocalDate, failures: List<Throwable>) :
        RuntimeException("payout calculation $date failed for ${failures.size} operation(s)", failures.first())

    private companion object {
        const val NEGATIVE_DAYS_LIMIT = 14
    }
}
