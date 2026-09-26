package org.example.balances

import java.util.UUID

interface BalancesStore {

    suspend fun taxBalance(country: String, from: java.time.Instant?, to: java.time.Instant): TaxBalance

    /** Live balance from the ledger. */
    suspend fun merchantBalances(merchantIds: List<UUID>): List<MerchantBalanceView>

    /** End-of-day balance from the daily snapshot. Empty when the job did not run that day. */
    suspend fun merchantBalancesOn(
        merchantIds: List<UUID>,
        date: java.time.LocalDate,
    ): List<MerchantBalanceView>
}
