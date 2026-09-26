package org.example.payout

import org.example.model.enums.Currency
import org.example.model.enums.PayoutStatus
import org.example.psp.PayoutRequest
import org.example.psp.PayoutResult
import org.example.psp.PspPayoutClient
import org.slf4j.LoggerFactory

/**
 * Sends computed payouts to the PSP. Separate from the calculation so a slow
 * or failing bank never blocks closing the day, and so a failed send can be
 * retried without recomputing anything.
 */
class PayoutDisbursementJob(
    private val payouts: PayoutStore,
    private val psp: PspPayoutClient,
) {
    private val log = LoggerFactory.getLogger(PayoutDisbursementJob::class.java)

    suspend fun run(): Int {
        var sent = 0

        payouts.due(PayoutStatus.COMPUTED).forEach { payout ->
            val reference = "payout-${payout.merchantId}-${payout.payoutDate}"

            when (
                val result = psp.payout(
                    PayoutRequest(
                        reference = reference,
                        pspAccountId = payout.pspAccountId,
                        amount = payout.amount,
                        currency = Currency.EUR,
                    )
                )
            ) {
                is PayoutResult.Accepted -> {
                    payouts.markSent(payout.merchantId, payout.payoutDate, result.pspReference)
                    sent++
                }

                // Stays COMPUTED, so the next run retries it. reference is the
                // idempotency key, so a retry cannot pay twice.
                is PayoutResult.Failed ->
                    log.error("payout {} rejected by the PSP: {}", reference, result.reason)
            }
        }

        log.info("payout disbursement: {} sent", sent)
        return sent
    }
}
