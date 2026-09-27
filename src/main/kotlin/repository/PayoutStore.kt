package org.example.repository

import org.example.model.MerchantBalance
import java.time.LocalDate
import java.time.Instant
import java.util.UUID

interface PayoutStore {

    /** One database-clock boundary shared by every page in this run. */
    suspend fun processingCutoff(endOfDay: Instant): Instant = endOfDay

    suspend fun balancePage(afterMerchantId: UUID?, limit: Int, cutoff: Instant): List<MerchantBalance>

    suspend fun recordDailyBalances(balances: List<MerchantBalance>, balanceDate: LocalDate)

    /** How many business days up to and including balanceDate this merchant has been negative. */
    suspend fun consecutiveNegativeDays(merchantId: UUID, balanceDate: LocalDate): Int

    suspend fun computePayouts(
        balances: List<MerchantBalance>,
        payoutDate: LocalDate,
        cutoff: Instant,
    ): List<MerchantBalance>
}
