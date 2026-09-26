package org.example.api.controller

import org.example.api.dto.MerchantBalanceDto
import org.example.api.dto.MerchantBalancesDto
import org.example.api.dto.TaxBalanceDto
import org.example.balances.BalancesStore
import org.example.model.enums.Currency
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class BalancesController(private val balances: BalancesStore) {

    suspend fun taxBalance(country: String, from: String?, to: String?): TaxBalanceDto {
        val start = from?.takeIf { it.isNotBlank() }?.let { Instant.parse(it) }
        val end = to?.takeIf { it.isNotBlank() }?.let { Instant.parse(it) } ?: Instant.now()

        require(start == null || start.isBefore(end)) { "from must be before to" }

        val balance = balances.taxBalance(country.uppercase(), start, end)

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

    suspend fun merchantBalances(merchantIds: List<String>, date: String?): MerchantBalancesDto {
        val ids = merchantIds.map { UUID.fromString(it.trim()) }
        val day = date?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }

        // A past day is answered from the nightly snapshot: one indexed row,
        // and the number the payout job actually acted on.
        val views = if (day == null) balances.merchantBalances(ids)
        else balances.merchantBalancesOn(ids, day)

        return MerchantBalancesDto(
            currency = Currency.EUR.name,
            date = day?.toString(),
            asOf = Instant.now().toString(),
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
