package org.example.psp

import org.slf4j.LoggerFactory

// TODO call the PSP over HTTP: signed body, retries on 5xx, timeout, circuit breaker.
class AcceptingPspPayoutClient : PspPayoutClient {

    private val log = LoggerFactory.getLogger(AcceptingPspPayoutClient::class.java)

    override suspend fun payout(request: PayoutRequest): PayoutResult {
        log.info(
            "payout {} account={} amount={} {}",
            request.reference, request.pspAccountId, request.amount, request.currency,
        )
        return PayoutResult.Accepted("mock-${request.reference}")
    }
}
