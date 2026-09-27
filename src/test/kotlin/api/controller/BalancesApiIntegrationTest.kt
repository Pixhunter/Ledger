package org.example.api.controller

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.api.generated.model.MerchantBalancesDto
import org.example.api.generated.model.TaxBalanceDto
import org.example.bootstrap.ledgerModule
import org.example.repository.PayoutRepository
import org.example.repository.ProcessingErrorRepository
import org.example.repository.TaxRemittanceRepository
import org.example.service.InMemoryMerchantRegistry
import org.example.service.scheduled.PayoutCalculationJob
import org.example.service.scheduled.TaxRemittanceCalculationJob
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.api.randomUuid
import org.example.jooq.tables.references.PAYOUT
import org.example.jooq.tables.references.TAX_REMITTANCE
import org.example.support.money

class BalancesApiIntegrationTest : LedgerApiIntegrationTestSupport() {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `tax owed rises with a capture and falls with its refund`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        assertEquals(HttpStatusCode.OK, send("/v1/payment/capture", paymentBody()).status)

        val afterCapture = taxBalance("ES")
        assertEquals("ES", afterCapture.country)
        assertEquals("EUR", afterCapture.currency.name)
        assertEquals("21.0000", afterCapture.owedAtEnd)

        assertEquals(HttpStatusCode.OK, send("/v1/payment/refund", refundBody()).status)

        assertEquals("0.0000", taxBalance("ES").owedAtEnd)
    }

    @Test
    fun `each country is owed only its own tax`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        send("/v1/payment/capture", paymentBody(pspReference = "psp-es"))
        send(
            "/v1/payment/capture",
            paymentBody(
                pspReference = "psp-de",
                billingCountry = "DE",
                cardIssuingCountry = "DE",
                ipCountry = "DE",
            ),
        )

        // 121.00 at 21% -> 21.00 ; 121.00 at 19% -> 19.32
        assertEquals("21.0000", taxBalance("ES").owedAtEnd)
        assertEquals("19.3200", taxBalance("DE").owedAtEnd)
        assertEquals("0.0000", taxBalance("FR").owedAtEnd)
    }

    @Test
    fun `a window reports the movement inside it, not the whole history`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        val old = Instant.now().minus(40, ChronoUnit.DAYS)
        val recent = Instant.now().minus(1, ChronoUnit.DAYS)

        send("/v1/payment/capture", paymentBody(pspReference = "psp-old", paymentTime = old))
        send("/v1/payment/capture", paymentBody(pspReference = "psp-new", paymentTime = recent))

        val window = taxBalance(
            country = "ES",
            from = Instant.now().minus(10, ChronoUnit.DAYS),
            to = Instant.now(),
        )

        // The older capture is before the window: it counts in the opening
        // balance, not in the movement.
        assertEquals("21.0000", window.owedAtStart)
        assertEquals("21.0000", window.movement)
        assertEquals("42.0000", window.owedAtEnd)
    }

    @Test
    fun `a merchant balance is what the payout job would pay`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        send("/v1/payment/capture", paymentBody())

        val balance = merchantBalances(merchantId).merchants.single()
        assertEquals(merchantId.toString(), balance.merchantId)
        assertEquals("95.0000", balance.available)
        assertEquals("0.0000", balance.held)
    }

    @Test
    fun `an unknown merchant is held, not available`() = testApplication {
        val unknown = randomUuid()
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(emptySet())) }

        send("/v1/payment/capture", paymentBody(merchantId = unknown))

        val balance = merchantBalances(unknown).merchants.single()
        assertEquals("0.0000", balance.available)
        assertEquals("95.0000", balance.held)
    }

    @Test
    fun `asking for one merchant does not return the others`() = testApplication {
        val other = randomUuid()
        application {
            ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId, other)))
        }

        send("/v1/payment/capture", paymentBody(pspReference = "psp-a"))
        send("/v1/payment/capture", paymentBody(pspReference = "psp-b", merchantId = other))

        assertEquals(2, merchantBalances().merchants.size)
        assertEquals(
            listOf(merchantId.toString()),
            merchantBalances(merchantId).merchants.map { it.merchantId },
        )
    }

    @Test
    fun `a dated query is answered from the nightly snapshot`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        seedMerchant()
        send("/v1/payment/capture", paymentBody())

        val closeDate = java.time.LocalDate.now(java.time.ZoneId.of("Europe/London"))
        runBlocking {
            PayoutCalculationJob(
                PayoutRepository(dsl),
                ProcessingErrorRepository(dsl),
            ).run(closeDate)
        }

        val snapshot = merchantBalances(merchantId, date = closeDate)
        assertEquals(closeDate.toString(), snapshot.date)

        val balance = snapshot.merchants.single()
        assertEquals("95.0000", balance.available)
    }

    @Test
    fun `merchant report keeps its calculated balance after payout computation`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }
        seedMerchant()
        send("/v1/payment/capture", paymentBody())

        assertEquals("95.0000", merchantBalances(merchantId).merchants.single().available)

        val date = LocalDate.now(ZoneId.of("Europe/London"))
        assertEquals(
            1,
            runBlocking {
                PayoutCalculationJob(PayoutRepository(dsl), ProcessingErrorRepository(dsl)).run(date)
            }.size,
        )

        assertEquals("95.0000", merchantBalances(merchantId).merchants.single().available)
        assertEquals(
            0,
            dsl.selectFrom(PAYOUT).fetchSingle().amount.compareTo(money("95.00")),
        )

        send("/v1/payment/capture", paymentBody(pspReference = "psp-after-payout"))
        assertEquals("190.0000", merchantBalances(merchantId).merchants.single().available)
    }

    @Test
    fun `tax report keeps its calculated balance after remittance computation`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }
        send("/v1/payment/capture", paymentBody())

        assertEquals("21.0000", taxBalance("ES").owedAtEnd)

        val period = LocalDate.now(ZoneId.of("Europe/London")).withDayOfMonth(1)
        assertEquals(
            1,
            runBlocking { TaxRemittanceCalculationJob(TaxRemittanceRepository(dsl)).run(period) }.size,
        )

        assertEquals("21.0000", taxBalance("ES").owedAtEnd)
        assertEquals(
            0,
            dsl.selectFrom(TAX_REMITTANCE).fetchSingle().amount.compareTo(money("21.00")),
        )

        send("/v1/payment/capture", paymentBody(pspReference = "psp-after-filing"))
        assertEquals("42.0000", taxBalance("ES").owedAtEnd)
    }

    @Test
    fun `a day the close job never ran has no snapshot`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        send("/v1/payment/capture", paymentBody())

        val never = java.time.LocalDate.of(2020, 1, 1)
        assertEquals(0, merchantBalances(merchantId, date = never).merchants.size)
    }

    @Test
    fun `a missing country is a bad request`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/balances/tax").status)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.taxBalance(
        country: String,
        from: Instant? = null,
        to: Instant? = null,
    ): TaxBalanceDto {
        val query = buildString {
            append("/v1/balances/tax?country=").append(country)
            from?.let { append("&from=").append(it) }
            to?.let { append("&to=").append(it) }
        }
        return json.decodeFromString(ok(client.get(query)))
    }

    @Test
    fun `a future dated capture is not available until its tax point`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        val future = Instant.now().plus(2, ChronoUnit.DAYS)
        send("/v1/payment/capture", paymentBody(paymentTime = future))

        assertEquals(0, merchantBalances(merchantId).merchants.size)
        assertEquals(
            "95.0000",
            merchantBalances(merchantId, asOf = future.plusSeconds(1)).merchants.single().available,
        )
    }

    @Test
    fun `a page is capped by limit and continued by the cursor`() = testApplication {
        val ids = List(3) { randomUuid() }
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(ids.toSet())) }

        ids.forEachIndexed { index, id ->
            send("/v1/payment/capture", paymentBody(pspReference = "psp-$index", merchantId = id))
        }

        val ordered = ids.map { it.toString() }.sorted()

        val first = merchantBalances(limit = 2)
        assertEquals(ordered.take(2), first.merchants.map { it.merchantId })
        assertEquals(ordered[1], first.nextCursor)

        val second = merchantBalances(after = UUID.fromString(first.nextCursor!!), limit = 2)
        assertEquals(ordered.drop(2), second.merchants.map { it.merchantId })
        assertEquals(null, second.nextCursor)
    }

    @Test
    fun `a final page of exactly limit rows has no cursor`() = testApplication {
        val ids = List(2) { randomUuid() }
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(ids.toSet())) }

        ids.forEachIndexed { index, id ->
            send("/v1/payment/capture", paymentBody(pspReference = "psp-$index", merchantId = id))
        }

        val page = merchantBalances(limit = 2)
        assertEquals(2, page.merchants.size)
        assertEquals(null, page.nextCursor)
    }

    @Test
    fun `a malformed query parameter is a bad request, not a server error`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        listOf(
            "/v1/balances/merchants?limit=5000",
            "/v1/balances/merchants?limit=none",
            "/v1/balances/merchants?date=yesterday",
            "/v1/balances/merchants?asOf=noon",
            "/v1/balances/merchants?after=not-a-uuid",
            "/v1/balances/merchants?merchantId=not-a-uuid",
            "/v1/balances/tax?country=ESP",
            "/v1/balances/tax?country=ES&from=yesterday",
        ).forEach { query ->
            assertEquals(HttpStatusCode.BadRequest, client.get(query).status, query)
        }
    }

    @Test
    fun `too many merchant ids is a bad request`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        val query = (1..101).joinToString("&") { "merchantId=${randomUuid()}" }

        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/v1/balances/merchants?$query").status,
        )
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.merchantBalances(
        vararg ids: UUID,
        date: java.time.LocalDate? = null,
        asOf: Instant? = null,
        after: UUID? = null,
        limit: Int? = null,
    ): MerchantBalancesDto {
        val params = buildList {
            ids.forEach { add("merchantId=$it") }
            date?.let { add("date=$it") }
            asOf?.let { add("asOf=$it") }
            after?.let { add("after=$it") }
            limit?.let { add("limit=$it") }
        }
        val query = "/v1/balances/merchants" +
            if (params.isEmpty()) "" else params.joinToString("&", prefix = "?")
        return json.decodeFromString(ok(client.get(query)))
    }

    private suspend fun ok(response: HttpResponse): String {
        assertEquals(HttpStatusCode.OK, response.status)
        return response.bodyAsText()
    }
}
