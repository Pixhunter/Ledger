package org.example.service.scheduled

import org.example.repository.TransferClient
import org.example.repository.TransferKind
import org.example.repository.TransferResult
import org.example.repository.TransferStore
import org.example.utils.logger

/**
 * Sends what the calculation job computed. Separate from the calculation so a
 * slow or failing receiver never blocks closing the period, and so a failed
 * send is retried without recomputing anything.
 *
 * A failure releases the row back to COMPUTED for the next run. The reference
 * is the idempotency key, so a retry cannot pay twice.
 */
class DisbursementJob(
    private val kind: TransferKind,
    private val transfers: TransferStore,
    private val client: TransferClient,
) {
    private val log = logger<DisbursementJob>()

    suspend fun run(): Int {
        var sent = 0

        transfers.due().forEach { transfer ->
            when (val result = client.send(transfer)) {
                is TransferResult.Accepted -> {
                    transfers.markSent(transfer, result.reference)
                    sent++
                }

                is TransferResult.Failed -> {
                    transfers.release(transfer)
                    log.error("event=transfer outcome=rejected ref={} reason={}", transfer.reference, result.reason)
                }
            }
        }

        log.info("event=disbursement kind={} sent={}", kind.reference, sent)
        return sent
    }
}