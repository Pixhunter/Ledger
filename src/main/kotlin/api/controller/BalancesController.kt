package org.example.api.controller

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.example.api.toCountry
import org.example.api.toInstant
import org.example.api.toIntIn
import org.example.api.toLocalDate
import org.example.api.toUuid
import java.time.Instant
import org.example.utils.Constants.Api.DEFAULT_PAGE_LIMIT
import org.example.utils.Constants.Api.MAX_MERCHANT_IDS
import org.example.utils.Constants.Api.MAX_PAGE_LIMIT
import org.example.api.DtoMapper.toDto
import org.example.api.generated.model.MerchantBalanceDto
import org.example.api.generated.model.MerchantBalancesDto
import org.example.api.generated.model.TaxBalanceDto
import org.example.model.enums.Currency
import org.example.repository.BalancesRepository
import org.example.utils.logger

/**
 * The finance-facing half of the production API. Owns its own routes: query
 * strings are parsed here, so nothing forwards them in from a routing layer.
 */
class BalancesController(private val balances: BalancesRepository) {
    private val log = logger<BalancesController>()

    fun routes(route: Route) = with(route) {
        get("/v1/balances/tax") {
            log.info("Got request to get tax balances")
            call.respond(
                getTaxBalance(
                    country = call.request.queryParameters["country"]
                        ?: throw IllegalArgumentException("country is required"),
                    from = call.request.queryParameters["from"],
                    to = call.request.queryParameters["to"],
                )
            )
        }

        get("/v1/balances/merchants") {
            log.info("Got request to get merchant balances")
            call.respond(
                getMerchantBalances(
                    merchantIds = call.request.queryParameters.getAll("merchantId").orEmpty(),
                    date = call.request.queryParameters["date"],
                    asOf = call.request.queryParameters["asOf"],
                    after = call.request.queryParameters["after"],
                    limit = call.request.queryParameters["limit"],
                )
            )
        }
    }

    private suspend fun getTaxBalance(country: String, from: String?, to: String?): TaxBalanceDto {
        val start = from?.takeIf { it.isNotBlank() }?.toInstant("from")
        val end = to?.takeIf { it.isNotBlank() }?.toInstant("to") ?: Instant.now()

        require(start == null || start.isBefore(end)) { "from must be before to" }

        val balance = balances.getTaxBalanceForCountry(country.toCountry("country"), start, end)

        return TaxBalanceDto(
            country = balance.country,
            // TODO: add logic when we got several currencies
            currency = Currency.EUR.toDto(),
            from = balance.from?.toString(),
            to = balance.to.toString(),
            owedAtStart = balance.owedAtStart.toPlainString(),
            movement = balance.movement.toPlainString(),
            owedAtEnd = balance.owedAtEnd.toPlainString(),
        )
    }

    private suspend fun getMerchantBalances(
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

        val fetched = if (day == null) balances.getMerchantBalances(ids, cutoff, cursor, size + 1)
        else balances.getMerchantBalancesOnDate(ids, day, cursor, size + 1)

        val views = fetched.take(size)
        val hasMore = fetched.size > size

        return MerchantBalancesDto(
            // TODO: add logic when we got several currencies
            currency = Currency.EUR.toDto(),
            date = day?.toString(),
            asOf = cutoff.toString(),
            limit = size,
            nextCursor = if (hasMore) views.last().merchantId.toString() else null,
            merchants = views.map {
                MerchantBalanceDto(
                    merchantId = it.merchantId.toString(),
                    merchantName = it.merchantName,
                    available = it.available.toPlainString(),
                    held = it.held?.toPlainString() ?: "",
                )
            },
        )
    }
}
