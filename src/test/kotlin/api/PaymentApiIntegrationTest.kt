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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentApiIntegrationTest : LedgerApiIntegrationTestSupport() {

    @Test
    fun `valid capture stores payment and balanced ledger entries`() = testApplication {
        application { ledgerModule(AppConfig(), dsl) }

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)

        val payment = dsl.selectFrom(PAYMENT).fetchSingle()
        assertEquals(pspReference, payment.pspReference)
        assertEquals(merchantId, payment.merchantId)
        assertEquals(12_100, payment.gross)
        assertEquals(2_100, payment.tax)
        assertEquals(500, payment.fee)
        assertEquals(9_500, payment.merchantNet)
        assertEquals("ES", payment.taxCountry)
        assertEquals(PaymentStatus.POSTED, enumById<PaymentStatus>(payment.status))
        assertNull(payment.holdReason)

        assertEquals(1, dsl.fetchCount(PAYMENT))
        assertEquals(1, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(4, dsl.fetchCount(LEDGER_ENTRY))

        val ledgerEntries = entries()
        assertEquals(12_100, ledgerEntries.balance(PaymentPurpose.PSP))
        assertEquals(-2_100, ledgerEntries.balance(PaymentPurpose.TAX))
        assertEquals(-500, ledgerEntries.balance(PaymentPurpose.REVENUE))
        assertEquals(-9_500, ledgerEntries.balance(PaymentPurpose.MERCHANT))
        assertEquals(0, ledgerEntries.sumOf { it.second })
    }

    @Test
    fun `duplicate capture returns OK and writes payment only once`() = testApplication {
        application { ledgerModule(AppConfig(), dsl) }

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
        application { ledgerModule(AppConfig(), dsl) }

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)
        val databaseAfterFirstCall = databaseSnapshot()

        val changedBody = paymentBody(amount = 24_200, billingCountry = "FR", cardIssuingCountry = "FR")
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, changedBody).status)

        assertEquals(databaseAfterFirstCall, databaseSnapshot())
        assertEquals(12_100, dsl.selectFrom(PAYMENT).fetchSingle().gross)
    }

    @Test
    fun `invalid payment request returns bad request and writes nothing`() = testApplication {
        application { ledgerModule(AppConfig(), dsl) }

        val databaseBeforeCall = databaseSnapshot()

        assertEquals(HttpStatusCode.BadRequest, send(PAYMENT_CAPTURE_ENDPOINT, "{}").status)

        assertEquals(databaseBeforeCall, databaseSnapshot())
    }

    @Test
    fun `unknown merchant is recorded and held`() = testApplication {
        val differentKnownMerchant = UUID.randomUUID()
        application {
            ledgerModule(
                config = AppConfig(),
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
        assertEquals(12_100, ledgerEntries.balance(PaymentPurpose.PSP))
        assertEquals(-2_100, ledgerEntries.balance(PaymentPurpose.TAX))
        assertEquals(-500, ledgerEntries.balance(PaymentPurpose.REVENUE))
        assertEquals(-9_500, ledgerEntries.balance(PaymentPurpose.HELD))
        assertEquals(0, ledgerEntries.sumOf { it.second })
    }

    @Test
    fun `insufficient tax evidence is recorded and held`() = testApplication {
        application { ledgerModule(AppConfig(), dsl) }

        val body = paymentBody(billingCountry = null, cardIssuingCountry = "ES", ipCountry = null)
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, body).status)

        assertTaxUnresolvedPayment(expectedTaxCountry = null)
    }

    @Test
    fun `unsupported tax country is recorded and held`() = testApplication {
        application { ledgerModule(AppConfig(), dsl) }

        val body = paymentBody(billingCountry = "JP", cardIssuingCountry = "JP", ipCountry = "JP")
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, body).status)

        assertTaxUnresolvedPayment(expectedTaxCountry = "JP")
    }

    @Test
    fun `business buyer in another country is reverse charged`() = testApplication {
        application { ledgerModule(AppConfig(), dsl) }

        val body = paymentBody(customerVatId = "ESB12345678")
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, body).status)

        val payment = dsl.selectFrom(PAYMENT).fetchSingle()
        assertEquals(PaymentStatus.POSTED, enumById<PaymentStatus>(payment.status))
        assertTrue(payment.reverseCharge!!)
        assertEquals(0, payment.tax)
        assertEquals(0, payment.taxRateBps)
        assertEquals(605, payment.fee)
        assertEquals(11_495, payment.merchantNet)

        val ledgerEntries = entries()
        assertEquals(12_100, ledgerEntries.balance(PaymentPurpose.PSP))
        assertEquals(0, ledgerEntries.balance(PaymentPurpose.TAX))
        assertEquals(-605, ledgerEntries.balance(PaymentPurpose.REVENUE))
        assertEquals(-11_495, ledgerEntries.balance(PaymentPurpose.MERCHANT))
        assertEquals(0, ledgerEntries.sumOf { it.second })
    }

    private fun assertTaxUnresolvedPayment(expectedTaxCountry: String?) {
        val payment = dsl.selectFrom(PAYMENT).fetchSingle()
        assertEquals(PaymentStatus.HELD, enumById<PaymentStatus>(payment.status))
        assertEquals(HoldReason.TAX_UNRESOLVED, enumById<HoldReason>(payment.holdReason!!))
        assertEquals(expectedTaxCountry, payment.taxCountry)
        assertEquals(0, payment.tax)
        assertEquals(0, payment.fee)
        assertEquals(12_100, payment.merchantNet)

        assertEquals(1, dsl.fetchCount(PAYMENT))
        assertEquals(1, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(2, dsl.fetchCount(LEDGER_ENTRY))

        val ledgerEntries = entries()
        assertEquals(12_100, ledgerEntries.balance(PaymentPurpose.PSP))
        assertEquals(-12_100, ledgerEntries.balance(PaymentPurpose.HELD))
        assertEquals(0, ledgerEntries.sumOf { it.second })
    }
}
