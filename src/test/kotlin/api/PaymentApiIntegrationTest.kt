package org.example.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.example.config.AppConfig
import org.example.config.PspConfig
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.LEDGER_TRANSACTION
import org.example.jooq.tables.references.PAYMENT
import org.example.jooq.tables.references.PAYMENT_HOLD
import org.example.jooq.tables.references.PROCESSING_ERROR
import org.example.jooq.tables.references.REFUND
import org.example.ledgerModule
import org.example.model.enumById
import org.example.model.enums.HoldReason
import org.example.model.enums.LedgerTransactionType
import org.example.model.enums.PaymentPurpose
import org.example.model.enums.PaymentStatus
import org.example.service.InMemoryMerchantRegistry
import org.example.support.money
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.example.randomUuid

class PaymentApiIntegrationTest : LedgerApiIntegrationTestSupport() {

    @Test
    fun `valid capture stores payment and balanced ledger entries`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        val body = paymentBody()
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, body).status)

        val payment = dsl.selectFrom(PAYMENT).fetchSingle()
        assertEquals(pspReference, payment.pspReference)
        assertEquals(merchantId, payment.merchantId)
        assertEquals(money("121.00"), payment.gross)
        assertEquals(money("21.00"), payment.tax)
        assertEquals(money("5.00"), payment.fee)
        assertEquals(money("95.00"), payment.merchantNet)
        assertEquals("ES", payment.taxCountry)
        assertEquals(PaymentStatus.POSTED, enumById<PaymentStatus>(payment.status))
        assertEquals(0, dsl.fetchCount(PAYMENT_HOLD))

        assertEquals(1, dsl.fetchCount(PAYMENT))
        assertEquals(1, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(4, dsl.fetchCount(LEDGER_ENTRY))

        val ledgerEntries = entries()
        assertEquals(money("121.00"), ledgerEntries.balance(PaymentPurpose.PSP))
        assertEquals(money("-21.00"), ledgerEntries.balance(PaymentPurpose.TAX))
        assertEquals(money("-5.00"), ledgerEntries.balance(PaymentPurpose.REVENUE))
        assertEquals(money("-95.00"), ledgerEntries.balance(PaymentPurpose.MERCHANT))
        assertEquals(money("0.00"), ledgerEntries.sumOf { it.second })
    }

    @Test
    fun `duplicate capture returns OK and writes payment only once`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        val body = paymentBody()

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, body).status)
        val databaseAfterFirstCall = databaseSnapshot()

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, body).status)

        assertEquals(databaseAfterFirstCall, databaseSnapshot())
        assertEquals(1, dsl.fetchCount(PAYMENT))
        assertEquals(0, dsl.fetchCount(REFUND))
        assertEquals(1, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(4, dsl.fetchCount(LEDGER_ENTRY))

        val transactionType = enumById<LedgerTransactionType>(
            dsl.selectFrom(LEDGER_TRANSACTION).fetchSingle().type,
        )
        assertEquals(LedgerTransactionType.CAPTURE, transactionType)
    }

    @Test
    fun `concurrent duplicate captures write one payment`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }
        val body = paymentBody()

        val statuses = coroutineScope {
            List(4) { async { send(PAYMENT_CAPTURE_ENDPOINT, body).status } }.awaitAll()
        }

        assertTrue(statuses.all { it == HttpStatusCode.OK })
        assertEquals(1, dsl.fetchCount(PAYMENT))
        assertEquals(1, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(4, dsl.fetchCount(LEDGER_ENTRY))
    }

    @Test
    fun `failed payment followed by successful attempt with same reference is recorded`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody(success = false)).status)
        assertEquals(0, dsl.fetchCount(PAYMENT))

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody(success = true)).status)
        assertEquals(1, dsl.fetchCount(PAYMENT))
        assertEquals(1, dsl.fetchCount(LEDGER_TRANSACTION))
    }

    @Test
    fun `same reference with different data does not overwrite original payment`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        val body = paymentBody()
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, body).status)
        val databaseAfterFirstCall = databaseSnapshot()

        val changedBody = paymentBody(amount = money("242.00"), billingCountry = "FR", cardIssuingCountry = "FR")
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, changedBody).status)

        assertEquals(databaseAfterFirstCall, databaseSnapshot())
        assertEquals(money("121.00"), dsl.selectFrom(PAYMENT).fetchSingle().gross)
    }

    @Test
    fun `same reference with different tax evidence is a conflict`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)
        val changed = paymentBody(billingCountry = "FR", cardIssuingCountry = "ES", ipCountry = "ES")
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, changed).status)

        assertEquals("ES", dsl.selectFrom(PAYMENT).fetchSingle().taxCountry)
        assertEquals(1, dsl.fetchCount(PAYMENT))
        assertEquals(1, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(1, dsl.fetchCount(PROCESSING_ERROR))
    }

    @Test
    fun `same reference with different payment time is a conflict`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }
        val firstTime = Instant.parse("2026-09-22T10:15:30Z")

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody(paymentTime = firstTime)).status)
        assertEquals(
            HttpStatusCode.OK,
            send(PAYMENT_CAPTURE_ENDPOINT, paymentBody(paymentTime = firstTime.plusSeconds(1))).status,
        )

        assertEquals(firstTime, dsl.selectFrom(PAYMENT).fetchSingle().paymentTime.toInstant())
        assertEquals(1, dsl.fetchCount(PAYMENT))
        assertEquals(1, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(1, dsl.fetchCount(PROCESSING_ERROR))
    }

    @Test
    fun `invalid payment request returns bad request and writes nothing`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        val databaseBeforeCall = databaseSnapshot()

        assertEquals(HttpStatusCode.BadRequest, send(PAYMENT_CAPTURE_ENDPOINT, "{}").status)

        assertEquals(databaseBeforeCall, databaseSnapshot())
    }

    @Test
    fun `capture with bad signature writes nothing`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        assertEquals(
            HttpStatusCode.Unauthorized,
            send(PAYMENT_CAPTURE_ENDPOINT, paymentBody(), signature = "invalid").status,
        )
        assertEquals(0, dsl.fetchCount(PAYMENT))
    }

    @Test
    fun `capture fails closed when signature secret is missing`() = testApplication {
        application {
            ledgerModule(
                AppConfig(psp = PspConfig(secret = null)),
                dsl,
                InMemoryMerchantRegistry(setOf(merchantId)),
            )
        }

        assertEquals(HttpStatusCode.Unauthorized, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)
        assertEquals(0, dsl.fetchCount(PAYMENT))
    }

    @Test
    fun `payment amount with fractions of a cent is rejected`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        assertEquals(
            HttpStatusCode.BadRequest,
            send(PAYMENT_CAPTURE_ENDPOINT, paymentBody(amount = money("121.001"))).status,
        )
        assertEquals(0, dsl.fetchCount(PAYMENT))
        assertEquals(0, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(0, dsl.fetchCount(LEDGER_ENTRY))
    }

    @Test
    fun `unknown merchant is recorded and held`() = testApplication {
        val differentKnownMerchant = randomUuid()
        application {
            ledgerModule(
                config = testConfig(),
                dsl = dsl,
                merchants = InMemoryMerchantRegistry(setOf(differentKnownMerchant)),
            )
        }

        val body = paymentBody()
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, body).status)

        val payment = dsl.selectFrom(PAYMENT).fetchSingle()
        assertEquals(merchantId, payment.merchantId)
        assertEquals(PaymentStatus.POSTED, enumById<PaymentStatus>(payment.status))
        assertEquals(HoldReason.UNKNOWN_MERCHANT, storedHoldReasons().single())

        val ledgerEntries = entries()
        assertEquals(money("121.00"), ledgerEntries.balance(PaymentPurpose.PSP))
        assertEquals(money("-21.00"), ledgerEntries.balance(PaymentPurpose.TAX))
        assertEquals(money("-5.00"), ledgerEntries.balance(PaymentPurpose.REVENUE))
        assertEquals(money("-95.00"), ledgerEntries.balance(PaymentPurpose.HELD))
        assertEquals(money("0.00"), ledgerEntries.sumOf { it.second })
        assertEquals(1, dsl.fetchCount(PROCESSING_ERROR))

        // Exact retry is acknowledged as a duplicate and writes nothing.
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, body).status)
        assertEquals(1, dsl.fetchCount(PAYMENT))
        assertEquals(1, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(4, dsl.fetchCount(LEDGER_ENTRY))
        assertEquals(1, dsl.fetchCount(PROCESSING_ERROR))
    }

    @Test
    fun `insufficient tax evidence is recorded and held`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        val body = paymentBody(billingCountry = null, cardIssuingCountry = "ES", ipCountry = null)
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, body).status)

        assertTaxUnresolvedPayment(expectedTaxCountry = null)
        assertEquals(1, dsl.fetchCount(PROCESSING_ERROR))

        // Exact retry is acknowledged as a duplicate and writes nothing.
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, body).status)
        assertEquals(1, dsl.fetchCount(PAYMENT))
        assertEquals(1, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(2, dsl.fetchCount(LEDGER_ENTRY))
        assertEquals(1, dsl.fetchCount(PROCESSING_ERROR))
    }

    @Test
    fun `unsupported tax country is recorded and held`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        val body = paymentBody(billingCountry = "JP", cardIssuingCountry = "JP", ipCountry = "JP")
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, body).status)

        assertTaxUnresolvedPayment(expectedTaxCountry = "JP")
    }

    @Test
    fun `payment can have merchant and tax holds together`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry()) }

        val body = paymentBody(billingCountry = "JP", cardIssuingCountry = "JP", ipCountry = "JP")
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, body).status)

        assertEquals(
            setOf(HoldReason.UNKNOWN_MERCHANT, HoldReason.TAX_UNRESOLVED),
            storedHoldReasons(),
        )
        assertEquals(2, dsl.fetchCount(PROCESSING_ERROR))
        assertEquals(money("-121.00"), entries().balance(PaymentPurpose.HELD))
    }

    @Test
    fun `business buyer in another country is reverse charged`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }

        val body = paymentBody(customerVatId = "ESB12345678")
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, body).status)

        val payment = dsl.selectFrom(PAYMENT).fetchSingle()
        assertEquals(PaymentStatus.POSTED, enumById<PaymentStatus>(payment.status))
        assertTrue(payment.reverseCharge!!)
        assertEquals(money("0.00"), payment.tax)
        assertEquals(0, payment.taxRateBps)
        assertEquals(money("6.05"), payment.fee)
        assertEquals(money("114.95"), payment.merchantNet)

        val ledgerEntries = entries()
        assertEquals(money("121.00"), ledgerEntries.balance(PaymentPurpose.PSP))
        assertEquals(money("0.00"), ledgerEntries.balance(PaymentPurpose.TAX))
        assertEquals(money("-6.05"), ledgerEntries.balance(PaymentPurpose.REVENUE))
        assertEquals(money("-114.95"), ledgerEntries.balance(PaymentPurpose.MERCHANT))
        assertEquals(money("0.00"), ledgerEntries.sumOf { it.second })
    }

    private fun assertTaxUnresolvedPayment(expectedTaxCountry: String?) {
        val payment = dsl.selectFrom(PAYMENT).fetchSingle()
        assertEquals(PaymentStatus.POSTED, enumById<PaymentStatus>(payment.status))
        assertEquals(setOf(HoldReason.TAX_UNRESOLVED), storedHoldReasons())
        assertEquals(expectedTaxCountry, payment.taxCountry)
        assertEquals(money("0.00"), payment.tax)
        assertEquals(money("0.00"), payment.fee)
        assertEquals(money("121.00"), payment.merchantNet)

        assertEquals(1, dsl.fetchCount(PAYMENT))
        assertEquals(1, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(2, dsl.fetchCount(LEDGER_ENTRY))

        val ledgerEntries = entries()
        assertEquals(money("121.00"), ledgerEntries.balance(PaymentPurpose.PSP))
        assertEquals(money("-121.00"), ledgerEntries.balance(PaymentPurpose.HELD))
        assertEquals(money("0.00"), ledgerEntries.sumOf { it.second })
    }


    private fun storedHoldReasons(): Set<HoldReason> =
        dsl.select(PAYMENT_HOLD.REASON)
            .from(PAYMENT_HOLD)
            .where(PAYMENT_HOLD.RESOLVED_AT.isNull)
            .fetch(PAYMENT_HOLD.REASON)
            .filterNotNull()
            .map { enumById<HoldReason>(it) }
            .toSet()
}
