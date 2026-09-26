package org.example.payout

import org.example.model.ProcessingError
import org.example.model.enums.EventType
import org.example.model.enums.ProcessingErrorCode
import org.example.repository.ProcessingErrorStore
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate
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
) {
    private val log = LoggerFactory.getLogger(PayoutCalculationJob::class.java)

    suspend fun run(payoutDate: LocalDate): List<MerchantBalance> {
        val computed = mutableListOf<MerchantBalance>()
        val processingErrors = mutableListOf<ProcessingError>()
        val balances = payouts.balances()

        payouts.recordDailyBalances(balances, payoutDate)

        balances.forEach { balance ->
            when {
                balance.amount.signum() > 0 ->
                    if (payouts.computePayout(balance, payoutDate)) computed += balance
                    else log.info("payout for {} on {} already computed", balance.merchantId, payoutDate)

                balance.amount.signum() < 0 ->
                    overdueError(balance.merchantId, balance.amount, payoutDate)?.let(processingErrors::add)
            }
        }
        errors.saveAll(processingErrors)

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

    private companion object {
        const val NEGATIVE_DAYS_LIMIT = 14
    }
}
