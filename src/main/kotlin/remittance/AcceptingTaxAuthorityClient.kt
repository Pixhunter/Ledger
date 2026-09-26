package org.example.remittance

import org.slf4j.LoggerFactory

// TODO real filing: each country has its own return format, portal and deadline.
class AcceptingTaxAuthorityClient : TaxAuthorityClient {

    private val log = LoggerFactory.getLogger(AcceptingTaxAuthorityClient::class.java)

    override suspend fun remit(request: RemittanceRequest): RemittanceResult {
        log.info(
            "remit {} country={} period={} amount={} {}",
            request.reference, request.country, request.periodStart,
            request.amount, request.currency,
        )
        return RemittanceResult.Accepted("mock-${request.reference}")
    }
}
