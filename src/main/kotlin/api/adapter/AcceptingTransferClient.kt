package org.example.api.adapter

import org.example.repository.DueTransfer
import org.example.repository.TransferClient
import org.example.repository.TransferResult
import org.example.utils.maskTail
import org.example.utils.logger

/**
 * TODO replace per kind: the PSP wants a signed HTTP call with retries and a
 * circuit breaker; each tax authority has its own return format and portal.
 */
class AcceptingTransferClient : TransferClient {

    private val log = logger<AcceptingTransferClient>()

    override suspend fun send(transfer: DueTransfer): TransferResult {
        log.info(
            "event=transfer kind={} ref={} amount={} currency={} destination={}",
            transfer.kind, transfer.reference, transfer.amount, transfer.currency,
            transfer.destination.reveal().maskTail(),
        )
        return TransferResult.Accepted("mock-${transfer.reference}")
    }
}