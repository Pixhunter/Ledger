package org.example.api.adapter

import org.example.repository.DueTransfer
import org.example.repository.TransferClient
import org.example.repository.TransferResult
import org.slf4j.LoggerFactory

/**
 * TODO replace per kind: the PSP wants a signed HTTP call with retries and a
 * circuit breaker; each tax authority has its own return format and portal.
 */
class AcceptingTransferClient : TransferClient {

    private val log = LoggerFactory.getLogger(AcceptingTransferClient::class.java)

    override suspend fun send(transfer: DueTransfer): TransferResult {
        log.info(
            "{} to={} amount={} {}",
            transfer.reference, transfer.destination, transfer.amount, transfer.currency,
        )
        return TransferResult.Accepted("mock-${transfer.reference}")
    }
}