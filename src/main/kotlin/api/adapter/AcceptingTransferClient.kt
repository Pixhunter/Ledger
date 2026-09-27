package org.example.api.adapter

import model.DueTransfer
import model.TransferClient
import model.TransferResult
import org.example.utils.logger

/**
 * TODO replace per kind: the PSP wants a signed HTTP call with retries and a
 * circuit breaker; each tax authority has its own return format and portal.
 */
class AcceptingTransferClient : TransferClient {

    private val log = logger<AcceptingTransferClient>()

    override suspend fun send(transfer: DueTransfer): TransferResult {
        log.info(
            "Transfer money for ${transfer.kind} ref=${transfer.reference}} " +
                    "amount=${transfer.amount} currency=${transfer.currency}"
        )
        return TransferResult.Accepted("mock-${transfer.reference}")
    }
}