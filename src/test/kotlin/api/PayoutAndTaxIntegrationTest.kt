package org.example.api

import io.ktor.http.HttpStatusCode
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.jooq.tables.references.MERCHANT
import org.example.jooq.tables.references.MERCHANT_DAILY_BALANCE
import org.example.jooq.tables.references.MERCHANT_PAYMENT_DETAILS
import org.example.jooq.tables.references.PAYOUT
import org.example.jooq.tables.references.PROCESSING_ERROR
import org.example.jooq.tables.references.TAX_REMITTANCE
import org.example.ledgerModule
import org.example.model.enumById
import org.example.model.enums.PaymentPurpose
import org.example.model.enums.PayoutStatus
import org.example.model.enums.ProcessingErrorCode
import org.example.model.enums.TaxCategory
import org.example.payout.PayoutCalculationJob
import org.example.payout.PayoutDisbursementJob
import org.example.psp.PayoutRequest
import org.example.psp.PayoutResult
import org.example.psp.PspPayoutClient
import org.example.remittance.RemittanceRequest
import org.example.remittance.RemittanceResult
import org.example.remittance.TaxAuthorityClient
import org.example.remittance.TaxBalanceMonitorJob
import org.example.remittance.TaxRemittanceCalculationJob
import org.example.remittance.TaxRemittanceDisbursementJob
import org.example.repository.PayoutRepository
import org.example.repository.ProcessingErrorRepository
import org.example.repository.TaxRemittanceRepository
import org.example.service.InMemoryMerchantRegistry
import org.example.support.money
import org.jooq.JSONB
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PayoutAndTaxIntegrationTest : LedgerApiIntegrationTestSupport() {

    private val payoutDate: LocalDate = LocalDate.of(2026, 9, 25)
    private val period: LocalDate = LocalDate.of(2026, 8, 1)

    private val errors by lazy { ProcessingErrorRepository(dsl) }
    private val payoutStore by lazy { PayoutRepository(dsl) }
    private val remittanceStore by lazy { TaxRemittanceRepository(dsl) }

    @Test
    fun `compute endpoints return serializable run details`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }
        seedMerchant()

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)

        val payoutResponse = client.post("/v1/payouts/compute")
        assertEquals(HttpStatusCode.OK, payoutResponse.status)
        val payoutJson = apiJson.parseToJsonElement(payoutResponse.bodyAsText()).jsonObject
        assertEquals(1, payoutJson.getValue("merchants").jsonPrimitive.content.toInt())
        assertEquals(1, payoutJson.getValue("payouts").jsonArray.size)

        val taxResponse = client.post("/v1/tax-remittances/compute")
        assertEquals(HttpStatusCode.OK, taxResponse.status)
        val taxJson = apiJson.parseToJsonElement(taxResponse.bodyAsText()).jsonObject
        assertEquals(1, taxJson.getValue("countries").jsonPrimitive.content.toInt())
        assertEquals(1, taxJson.getValue("remittances").jsonArray.size)
    }

    @Test
    fun `ten captures become one payout, sent once, leaving the merchant balance at zero`() =
        testApplication {
            application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }
            seedMerchant()

            repeat(10) { index ->
                val response = send(PAYMENT_CAPTURE_ENDPOINT, paymentBody(pspReference = "psp-cap-$index"))
                assertEquals(HttpStatusCode.OK, response.status)
            }

            val owed = entries().balance(PaymentPurpose.MERCHANT).negate()
            assertTrue(owed.signum() > 0)

            assertEquals(1, runBlocking { PayoutCalculationJob(payoutStore, errors).run(payoutDate) }.size)

            val payout = dsl.selectFrom(PAYOUT).fetchSingle()
            assertEquals(merchantId, payout.merchantId)
            assertEquals(payoutDate, payout.payoutDate)
            assertEquals(0, payout.amount.compareTo(owed))
            assertEquals(PayoutStatus.COMPUTED, enumById<PayoutStatus>(payout.status))

            // The PAYOUT transaction settles the merchant account.
            assertEquals(money("0"), entries().balance(PaymentPurpose.MERCHANT))

            assertEquals(1, dsl.fetchCount(MERCHANT_DAILY_BALANCE))

            val psp = RecordingPsp()
            assertEquals(1, runBlocking { PayoutDisbursementJob(payoutStore, psp).run() })

            val request = psp.requests.single()
            assertEquals("payout-$merchantId-$payoutDate", request.reference)
            assertEquals("acct-merchant-1", request.pspAccountId)
            assertEquals(0, request.amount.compareTo(owed))

            assertEquals(
                PayoutStatus.SENT,
                enumById<PayoutStatus>(dsl.selectFrom(PAYOUT).fetchSingle().status),
            )

            // A second run of either job pays nobody twice.
            assertTrue(runBlocking { PayoutCalculationJob(payoutStore, errors).run(payoutDate) }.isEmpty())
            assertEquals(0, runBlocking { PayoutDisbursementJob(payoutStore, psp).run() })
            assertEquals(1, dsl.fetchCount(PAYOUT))
        }

    @Test
    fun `captures in three countries are filed and paid per country`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }
        seedMerchant()

        listOf("DE", "ES", "FR").forEachIndexed { index, country ->
            val response = send(
                PAYMENT_CAPTURE_ENDPOINT,
                paymentBody(
                    pspReference = "psp-$country",
                    billingCountry = country,
                    cardIssuingCountry = country,
                    ipCountry = country,
                ),
            )
            assertEquals(HttpStatusCode.OK, response.status, "capture $index")
        }

        assertEquals(3, runBlocking { TaxRemittanceCalculationJob(remittanceStore).run(period) }.size)

        val filed = dsl.select(TAX_REMITTANCE.COUNTRY, TAX_REMITTANCE.AMOUNT)
            .from(TAX_REMITTANCE)
            .fetch()
            .associate { it.value1()!! to it.value2()!! }

        assertEquals(setOf("DE", "ES", "FR"), filed.keys)
        filed.values.forEach { assertTrue(it.signum() > 0) }

        // Every tax account is settled by the remittance transactions.
        assertEquals(money("0"), entries().balance(PaymentPurpose.TAX))

        val authority = RecordingAuthority()
        assertEquals(3, runBlocking { TaxRemittanceDisbursementJob(remittanceStore, authority).run() })

        assertEquals(
            setOf("tax-DE-$period", "tax-ES-$period", "tax-FR-$period"),
            authority.requests.map { it.reference }.toSet(),
        )

        assertTrue(runBlocking { TaxRemittanceCalculationJob(remittanceStore).run(period) }.isEmpty())
        assertEquals(3, dsl.fetchCount(TAX_REMITTANCE))
    }

    @Test
    fun `a refund after the period is filed leaves the country negative and is reported`() =
        testApplication {
            application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }
            seedMerchant()

            assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)
            runBlocking { TaxRemittanceCalculationJob(remittanceStore).run(period) }
            assertEquals(money("0"), entries().balance(PaymentPurpose.TAX))

            assertEquals(HttpStatusCode.OK, send(PAYMENT_REFUND_ENDPOINT, refundBody()).status)

            // The refund credited tax back after it was paid: we are owed, not owing.
            assertTrue(entries().balance(PaymentPurpose.TAX).signum() > 0)

            val monitor = TaxBalanceMonitorJob(remittanceStore, errors, negativeDaysLimit = 1)
            assertEquals(1, runBlocking { monitor.run(payoutDate) })

            val error = dsl.selectFrom(PROCESSING_ERROR).fetchSingle()
            assertEquals(
                ProcessingErrorCode.NEGATIVE_TAX_BALANCE,
                enumById<ProcessingErrorCode>(error.errorCode),
            )
            assertTrue(error.errorDetail.contains("ES"))
        }

    private fun seedMerchant() {
        dsl.transaction { cfg ->
            val db = org.jooq.impl.DSL.using(cfg)

            db.insertInto(MERCHANT)
                .set(MERCHANT.ID, merchantId)
                .set(MERCHANT.NAME, "Test merchant")
                .set(MERCHANT.CURRENCY, "EUR")
                .set(MERCHANT.FEE_RATE_BPS, 500)
                .set(MERCHANT.TAX_CATEGORY, TaxCategory.STANDARD.id)
                .set(MERCHANT.STATUS, 1.toShort())
                .onConflict(MERCHANT.ID)
                .doNothing()
                .execute()

            db.insertInto(MERCHANT_PAYMENT_DETAILS)
                .set(MERCHANT_PAYMENT_DETAILS.MERCHANT_ID, merchantId)
                .set(MERCHANT_PAYMENT_DETAILS.PSP_ACCOUNT_ID, "acct-merchant-1")
                .set(MERCHANT_PAYMENT_DETAILS.ACCOUNT_HOLDER, "Test merchant")
                .set(MERCHANT_PAYMENT_DETAILS.IBAN, "DE89370400440532013000")
                .set(MERCHANT_PAYMENT_DETAILS.BANK_COUNTRY, "DE")
                .set(MERCHANT_PAYMENT_DETAILS.ADDRESS, JSONB.valueOf("""{"country":"DE"}"""))
                .onConflict(MERCHANT_PAYMENT_DETAILS.MERCHANT_ID)
                .doNothing()
                .execute()
        }
    }

    private class RecordingPsp : PspPayoutClient {
        val requests = mutableListOf<PayoutRequest>()

        override suspend fun payout(request: PayoutRequest): PayoutResult {
            requests += request
            return PayoutResult.Accepted("psp-${request.reference}")
        }
    }

    private class RecordingAuthority : TaxAuthorityClient {
        val requests = mutableListOf<RemittanceRequest>()

        override suspend fun remit(request: RemittanceRequest): RemittanceResult {
            requests += request
            return RemittanceResult.Accepted("tax-${request.reference}")
        }
    }
}
