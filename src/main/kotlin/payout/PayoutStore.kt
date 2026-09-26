package org.example.payout

import org.example.model.enums.PayoutStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class MerchantBalance(val merchantId: UUID, val amount: BigDecimal)

data class DuePayout(
    val merchantId: UUID,
    val payoutDate: LocalDate,
    val pspAccountId: String,
    val amount: BigDecimal,
)

interface PayoutStore {

    /** Every merchant's MERCHANT balance, any sign. HELD and SUSPENSE are separate accounts. */
    suspend fun balances(): List<MerchantBalance>

    suspend fun recordDailyBalance(balance: MerchantBalance, balanceDate: LocalDate)

    /** How many business days up to and including balanceDate this merchant has been negative. */
    suspend fun consecutiveNegativeDays(merchantId: UUID, balanceDate: LocalDate): Int

    /**
     * Payout row plus its PAYOUT ledger transaction, in one transaction.
     * Returns false when (merchant, date) is already there - a rerun pays nobody twice.
     */
    suspend fun computePayout(balance: MerchantBalance, payoutDate: LocalDate): Boolean

    suspend fun due(status: PayoutStatus): List<DuePayout>

    suspend fun markSent(merchantId: UUID, payoutDate: LocalDate, pspReference: String)
}
