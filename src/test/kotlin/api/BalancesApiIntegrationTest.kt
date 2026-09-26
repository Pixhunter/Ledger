package org.example.api

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.example.api.dto.MerchantBalancesDto
import org.example.api.dto.TaxBalanceDto
import org.example.ledgerModule
import org.example.service.InMemoryMerchantRegistry
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class BalancesApiIntegrationTest : LedgerApiIntegrationTestSupport() {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `tax owed rises with a capture and falls with its refund`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        assertEquals(HttpStatusCode.OK, send("/v1/payment/capture", paymentBody()).status)

        val afterCapture = taxBalance("ES")
        assertEquals("ES", afterCapture.country)
        assertEquals("EUR", afterCapture.currency)
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
        assertEquals("19.3193", taxBalance("DE").owedAtEnd)
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
        val unknown = UUID.randomUUID()
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(emptySet())) }

        send("/v1/payment/capture", paymentBody(merchantId = unknown))

        val balance = merchantBalances(unknown).merchants.single()
        assertEquals("0.0000", balance.available)
        assertEquals("95.0000", balance.held)
    }

    @Test
    fun `asking for one merchant does not return the others`() = testApplication {
        val other = UUID.randomUUID()
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
    fun `a past date is answered from the nightly snapshot`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        send("/v1/payment/capture", paymentBody())

        val closeDate = java.time.LocalDate.of(2026, 9, 25)
        runBlocking {
            org.example.payout.PayoutCalculationJob(
                org.example.repository.PayoutRepository(dsl),
                org.example.repository.ProcessingErrorRepository(dsl),
            ).run(closeDate)
        }

        val snapshot = merchantBalances(merchantId, date = closeDate)
        assertEquals(closeDate.toString(), snapshot.date)

        val balance = snapshot.merchants.single()
        assertEquals("95.0000", balance.available)
        assertEquals(null, balance.held)
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

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.merchantBalances(
        vararg ids: UUID,
        date: java.time.LocalDate? = null,
    ): MerchantBalancesDto {
        val params = buildList {
            ids.forEach { add("merchantId=$it") }
            date?.let { add("date=$it") }
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
