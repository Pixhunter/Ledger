package org.example.api.controller

import org.example.api.dto.MerchantBalanceDto
import org.example.api.dto.MerchantBalancesDto
import org.example.api.dto.TaxBalanceDto
import org.example.balances.BalancesStore
import org.example.model.enums.Currency
import org.example.toCountry
import org.example.toInstant
import org.example.toIntIn
import org.example.toLocalDate
import org.example.toUuid
import java.time.Instant
import org.example.Constants.Api.DEFAULT_PAGE_LIMIT
import org.example.Constants.Api.MAX_MERCHANT_IDS
import org.example.Constants.Api.MAX_PAGE_LIMIT

class BalancesController(private val balances: BalancesStore) {

    suspend fun taxBalance(country: String, from: String?, to: String?): TaxBalanceDto {
        val start = from?.takeIf { it.isNotBlank() }?.toInstant("from")
        val end = to?.takeIf { it.isNotBlank() }?.toInstant("to") ?: Instant.now()

        require(start == null || start.isBefore(end)) { "from must be before to" }

        val balance = balances.taxBalance(country.toCountry("country"), start, end)

        return TaxBalanceDto(
            country = balance.country,
            currency = Currency.EUR.name,
            from = balance.from?.toString(),
            to = balance.to.toString(),
            owedAtStart = balance.owedAtStart.toPlainString(),
            movement = balance.movement.toPlainString(),
            owedAtEnd = balance.owedAtEnd.toPlainString(),
        )
    }

    suspend fun merchantBalances(
        merchantIds: List<String>,
        date: String?,
        asOf: String?,
        after: String?,
        limit: String?,
    ): MerchantBalancesDto {
        require(merchantIds.size <= MAX_MERCHANT_IDS) { "at most $MAX_MERCHANT_IDS merchantId values" }

        val ids = merchantIds.map { it.trim().toUuid("merchantId") }
        val day = date?.takeIf { it.isNotBlank() }?.toLocalDate("date")
        val cursor = after?.takeIf { it.isNotBlank() }?.trim()?.toUuid("after")
        val size = limit?.takeIf { it.isNotBlank() }?.toIntIn("limit", 1..MAX_PAGE_LIMIT) ?: DEFAULT_PAGE_LIMIT
        val cutoff = asOf?.takeIf { it.isNotBlank() }?.toInstant("asOf") ?: Instant.now()

        // A past day is answered from the nightly snapshot: one indexed row,
        // and the number the payout job actually acted on.
        // One row beyond the page tells us whether a next page exists, so a
        // final page of exactly `size` rows does not hand out a cursor that
        // leads nowhere.
        val fetched = if (day == null) balances.merchantBalances(ids, cutoff, cursor, size + 1)
        else balances.merchantBalancesOn(ids, day, cursor, size + 1)

        val views = fetched.take(size)
        val hasMore = fetched.size > size

        return MerchantBalancesDto(
            currency = Currency.EUR.name,
            date = day?.toString(),
            asOf = cutoff.toString(),
            limit = size,
            nextCursor = if (hasMore) views.last().merchantId.toString() else null,
            merchants = views.map {
                MerchantBalanceDto(
                    merchantId = it.merchantId.toString(),
                    merchantName = it.merchantName,
                    available = it.available.toPlainString(),
                    held = it.held?.toPlainString(),
                )
            },
        )
    }
}
