package org.example.payout

import org.example.model.enums.PayoutStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Instant
import java.util.UUID

data class MerchantBalance(
    val merchantId: UUID,
    val merchantName: String,
    val amount: BigDecimal,
    val payments: Int,
)

data class DuePayout(
    val merchantId: UUID,
    val payoutDate: LocalDate,
    val pspAccountId: String,
    val amount: BigDecimal,
)

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

    /** Atomically claims at most [limit] rows for one worker. */
    suspend fun due(status: PayoutStatus, limit: Int = 100): List<DuePayout>

    suspend fun markSent(merchantId: UUID, payoutDate: LocalDate, pspReference: String)

    suspend fun release(merchantId: UUID, payoutDate: LocalDate) = Unit
}
