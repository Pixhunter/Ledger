package org.example.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.example.config.AppConfig
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.LEDGER_TRANSACTION
import org.example.jooq.tables.references.PAYMENT
import org.example.jooq.tables.references.REFUND
import org.example.ledgerModule
import org.example.model.enumById
import org.example.model.enums.HoldReason
import org.example.model.enums.LedgerTransactionType
import org.example.model.enums.PaymentPurpose
import org.example.model.enums.PaymentStatus
import org.example.service.MerchantRegistry
import org.example.support.money
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentApiIntegrationTest : LedgerApiIntegrationTestSupport() {

    @Test
    fun `valid capture stores payment and balanced ledger entries`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)

        val payment = dsl.selectFrom(PAYMENT).fetchSingle()
        assertEquals(pspReference, payment.pspReference)
        assertEquals(merchantId, payment.merchantId)
        assertEquals(money("121.00"), payment.gross)
        assertEquals(money("21.00"), payment.tax)
        assertEquals(money("5.00"), payment.fee)
        assertEquals(money("95.00"), payment.merchantNet)
        assertEquals("ES", payment.taxCountry)
        assertEquals(PaymentStatus.POSTED, enumById<PaymentStatus>(payment.status))
        assertNull(payment.holdReason)

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
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

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
    fun `same reference with different data does not overwrite original payment`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)
        val databaseAfterFirstCall = databaseSnapshot()

        val changedBody = paymentBody(amount = money("242.00"), billingCountry = "FR", cardIssuingCountry = "FR")
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, changedBody).status)

        assertEquals(databaseAfterFirstCall, databaseSnapshot())
        assertEquals(money("121.00"), dsl.selectFrom(PAYMENT).fetchSingle().gross)
    }

    @Test
    fun `invalid payment request returns bad request and writes nothing`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

        val databaseBeforeCall = databaseSnapshot()

        assertEquals(HttpStatusCode.BadRequest, send(PAYMENT_CAPTURE_ENDPOINT, "{}").status)

        assertEquals(databaseBeforeCall, databaseSnapshot())
    }

    @Test
    fun `payment amount with fractions of a cent is rejected`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

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
        val differentKnownMerchant = UUID.randomUUID()
        application {
            ledgerModule(
                config = testConfig(),
                dsl = dsl,
                merchants = MerchantRegistry(setOf(differentKnownMerchant)),
            )
        }

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)

        val payment = dsl.selectFrom(PAYMENT).fetchSingle()
        assertEquals(merchantId, payment.merchantId)
        assertEquals(PaymentStatus.HELD, enumById<PaymentStatus>(payment.status))
        assertEquals(HoldReason.UNKNOWN_MERCHANT, enumById<HoldReason>(payment.holdReason!!))

        val ledgerEntries = entries()
        assertEquals(money("121.00"), ledgerEntries.balance(PaymentPurpose.PSP))
        assertEquals(money("-21.00"), ledgerEntries.balance(PaymentPurpose.TAX))
        assertEquals(money("-5.00"), ledgerEntries.balance(PaymentPurpose.REVENUE))
        assertEquals(money("-95.00"), ledgerEntries.balance(PaymentPurpose.HELD))
        assertEquals(money("0.00"), ledgerEntries.sumOf { it.second })
    }

    @Test
    fun `insufficient tax evidence is recorded and held`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

        val body = paymentBody(billingCountry = null, cardIssuingCountry = "ES", ipCountry = null)
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, body).status)

        assertTaxUnresolvedPayment(expectedTaxCountry = null)
    }

    @Test
    fun `unsupported tax country is recorded and held`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

        val body = paymentBody(billingCountry = "JP", cardIssuingCountry = "JP", ipCountry = "JP")
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, body).status)

        assertTaxUnresolvedPayment(expectedTaxCountry = "JP")
    }

    @Test
    fun `business buyer in another country is reverse charged`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

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
        assertEquals(PaymentStatus.HELD, enumById<PaymentStatus>(payment.status))
        assertEquals(HoldReason.TAX_UNRESOLVED, enumById<HoldReason>(payment.holdReason!!))
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
}
