package repository.store

import org.example.model.MerchantBalance
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface PayoutStore {

    /** One database-clock boundary shared by every page in this run. */
    suspend fun processingCutoff(endOfDay: Instant): Instant = endOfDay

    suspend fun balancePage(afterMerchantId: UUID?, limit: Int, cutoff: Instant): List<MerchantBalance>

    suspend fun recordDailyBalances(balances: List<MerchantBalance>, balanceDate: LocalDate)

    /** Consecutive negative days for all requested merchants, loaded in one query. */
    suspend fun consecutiveNegativeDays(
        merchantIds: Collection<UUID>,
        balanceDate: LocalDate,
    ): Map<UUID, Int>

    suspend fun computePayouts(
        balances: List<MerchantBalance>,
        payoutDate: LocalDate,
        cutoff: Instant,
    ): List<MerchantBalance>
}
