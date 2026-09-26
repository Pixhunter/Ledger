package org.example.remittance

import org.example.model.enums.Currency
import org.example.model.enums.PayoutStatus
import org.slf4j.LoggerFactory

class TaxRemittanceDisbursementJob(
    private val remittances: TaxRemittanceStore,
    private val authority: TaxAuthorityClient,
) {
    private val log = LoggerFactory.getLogger(TaxRemittanceDisbursementJob::class.java)

    suspend fun run(): Int {
        var sent = 0

        remittances.due(PayoutStatus.COMPUTED).forEach { remittance ->
            val reference = "tax-${remittance.country}-${remittance.periodStart}"

            when (
                val result = authority.remit(
                    RemittanceRequest(
                        reference = reference,
                        country = remittance.country,
                        periodStart = remittance.periodStart,
                        amount = remittance.amount,
                        currency = Currency.EUR,
                    )
                )
            ) {
                is RemittanceResult.Accepted -> {
                    remittances.markSent(remittance.country, remittance.periodStart, result.reference)
                    sent++
                }

                // Stays COMPUTED for the next run. reference is the idempotency
                // key, so a retry cannot pay the same period twice.
                is RemittanceResult.Failed ->
                    run {
                        remittances.release(remittance.country, remittance.periodStart)
                        log.error("remittance {} rejected: {}", reference, result.reason)
                    }
            }
        }

        log.info("tax remittance disbursement: {} sent", sent)
        return sent
    }
}
