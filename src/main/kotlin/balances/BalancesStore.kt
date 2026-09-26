package org.example.balances

import java.util.UUID

interface BalancesStore {

    suspend fun taxBalance(country: String, from: java.time.Instant?, to: java.time.Instant): TaxBalance

    /**
     * Live balance from the ledger, one keyset page ordered by merchant id.
     * Entries dated after [asOf] are excluded: a capture booked with a future
     * tax point is not payable yet, and the payout job already waits for it.
     */
    suspend fun merchantBalances(
        merchantIds: List<UUID>,
        asOf: java.time.Instant,
        after: UUID?,
        limit: Int,
    ): List<MerchantBalanceView>

    /** End-of-day balance from the daily snapshot. Empty when the job did not run that day. */
    suspend fun merchantBalancesOn(
        merchantIds: List<UUID>,
        date: java.time.LocalDate,
        after: UUID?,
        limit: Int,
    ): List<MerchantBalanceView>
}
