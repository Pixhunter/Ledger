package org.example.service.scheduled

import model.DueTransfer
import model.TransferClient
import model.TransferKind
import model.TransferResult
import model.TransferStore
import org.example.utils.Constants
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
    private val batchSize: Int = Constants.Jobs.CLAIM_LIMIT,
    private val maxBatches: Int = Constants.Jobs.MAX_DISBURSEMENT_BATCHES,
) {
    private val log = logger<DisbursementJob>()

    suspend fun run(): Int {
        log.info("Send all calculations")

        require(batchSize in 1..Constants.Jobs.MAX_BATCH_SIZE)
        require(maxBatches > 0)

        var sent = 0
        var batches = 0
        val failed = mutableListOf<DueTransfer>()

        try {
            while (batches < maxBatches) {
                val due = transfers.due(batchSize)
                if (due.isEmpty()) break
                batches++

                due.forEach { transfer ->
                    when (val result = client.send(transfer)) {
                        is TransferResult.Accepted -> {
                            transfers.markSent(transfer, result.reference)
                            sent++
                        }

                        is TransferResult.Failed -> {
                            failed += transfer
                            log.error("Transfer outcome rejected ref=${transfer.reference} reason=${result.reason}")
                        }
                    }
                }
            }
        } finally {
            failed.forEach { transfers.release(it) }
        }

        if (batches == maxBatches) {
            log.warn("Batches equal to maxBatches: $maxBatches")
        }

        log.info("Disbursement kind=${kind.reference} sent=$sent batches=$batches")
        return sent
    }
}
