package org.example.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.example.config.AppConfig
import org.example.config.PspConfig
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.LEDGER_TRANSACTION
import org.example.jooq.tables.references.PAYMENT
import org.example.jooq.tables.references.REFUND
import org.example.ledgerModule
import org.example.service.MerchantRegistry
import org.example.model.enumById
import org.example.model.enums.HoldReason
import org.example.model.enums.LedgerTransactionType
import org.example.model.enums.PaymentPurpose
import org.example.model.enums.PaymentStatus
import org.example.model.enums.RefundReason
import org.example.support.money
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class RefundApiIntegrationTest : LedgerApiIntegrationTestSupport() {

    @Test
    fun `a captured payment refunded in full leaves two ledger transactions and a zero balance`() = testApplication {
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

        val ledgerEntries = entries()
        assertEquals(1, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(4, dsl.fetchCount(LEDGER_ENTRY))
        assertEquals(money("121.00"), ledgerEntries.balance(PaymentPurpose.PSP))
        assertEquals(money("-21.00"), ledgerEntries.balance(PaymentPurpose.TAX))
        assertEquals(money("-5.00"), ledgerEntries.balance(PaymentPurpose.REVENUE))
        assertEquals(money("-95.00"), ledgerEntries.balance(PaymentPurpose.MERCHANT))

        assertEquals(HttpStatusCode.OK, send(PAYMENT_REFUND_ENDPOINT, refundBody()).status)

        val refund = dsl.selectFrom(REFUND).fetchSingle()
        assertEquals(refundReference, refund.refundReference)
        assertEquals(payment.id, refund.paymentId)
        assertEquals(money("121.00"), refund.amount)
        assertEquals(false, refund.feeReturned)

        assertEquals(1, dsl.fetchCount(PAYMENT))
        assertEquals(1, dsl.fetchCount(REFUND))
        assertEquals(2, dsl.fetchCount(LEDGER_TRANSACTION))

        val types = dsl.select(LEDGER_TRANSACTION.TYPE)
            .from(LEDGER_TRANSACTION)
            .fetch()
            .map { enumById<LedgerTransactionType>(it.value1()!!) }
        assertEquals(
            setOf(LedgerTransactionType.CAPTURE, LedgerTransactionType.REFUND),
            types.toSet(),
        )

        assertEquals(
            PaymentStatus.REFUNDED,
            enumById<PaymentStatus>(dsl.selectFrom(PAYMENT).fetchSingle().status),
        )

        val ledgerEntriesAfterRefund = entries()
        assertEquals(money("0.00"), ledgerEntriesAfterRefund.balance(PaymentPurpose.PSP))
        assertEquals(money("0.00"), ledgerEntriesAfterRefund.balance(PaymentPurpose.TAX))
        assertEquals(money("0.00"), ledgerEntriesAfterRefund.sumOf { it.second })
        assertEquals(money("-5.00"), ledgerEntriesAfterRefund.balance(PaymentPurpose.REVENUE))
        assertEquals(money("5.00"), ledgerEntriesAfterRefund.balance(PaymentPurpose.MERCHANT))
    }

    @Test
    fun `refund before payment is rejected and succeeds when retried after payment arrives`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

        val body = refundBody()
        val databaseBeforeRefund = databaseSnapshot()

        assertEquals(HttpStatusCode.NotFound, send(PAYMENT_REFUND_ENDPOINT, body).status)

        assertEquals(databaseBeforeRefund, databaseSnapshot())
        assertEquals(0, dsl.fetchCount(PAYMENT))
        assertEquals(0, dsl.fetchCount(REFUND))
        assertEquals(0, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(0, dsl.fetchCount(LEDGER_ENTRY))

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)
        assertEquals(HttpStatusCode.OK, send(PAYMENT_REFUND_ENDPOINT, body).status)

        val payment = dsl.selectFrom(PAYMENT).fetchSingle()
        val refund = dsl.selectFrom(REFUND).fetchSingle()

        assertEquals(refundReference, refund.refundReference)
        assertEquals(payment.id, refund.paymentId)
        assertEquals(PaymentStatus.REFUNDED, enumById<PaymentStatus>(payment.status))
        assertEquals(1, dsl.fetchCount(PAYMENT))
        assertEquals(1, dsl.fetchCount(REFUND))
        assertEquals(2, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(7, dsl.fetchCount(LEDGER_ENTRY))

        val transactionTypes = dsl.select(LEDGER_TRANSACTION.TYPE)
            .from(LEDGER_TRANSACTION)
            .fetch()
            .map { enumById<LedgerTransactionType>(it.value1()!!) }

        assertEquals(1, transactionTypes.count { it == LedgerTransactionType.CAPTURE })
        assertEquals(1, transactionTypes.count { it == LedgerTransactionType.REFUND })

        val ledgerEntries = entries()
        assertEquals(money("0.00"), ledgerEntries.balance(PaymentPurpose.PSP))
        assertEquals(money("0.00"), ledgerEntries.balance(PaymentPurpose.TAX))
        assertEquals(money("-5.00"), ledgerEntries.balance(PaymentPurpose.REVENUE))
        assertEquals(money("5.00"), ledgerEntries.balance(PaymentPurpose.MERCHANT))
        assertEquals(money("0.00"), ledgerEntries.sumOf { it.second })
    }

    @Test
    fun `duplicate refund returns OK and writes refund only once`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)

        val body = refundBody()

        assertEquals(HttpStatusCode.OK, send(PAYMENT_REFUND_ENDPOINT, body).status)
        val databaseAfterFirstCall = databaseSnapshot()

        assertEquals(HttpStatusCode.OK, send(PAYMENT_REFUND_ENDPOINT, body).status)

        assertEquals(databaseAfterFirstCall, databaseSnapshot())
        assertEquals(1, dsl.fetchCount(PAYMENT))
        assertEquals(1, dsl.fetchCount(REFUND))
        assertEquals(2, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(7, dsl.fetchCount(LEDGER_ENTRY))

        val transactionTypes = dsl.select(LEDGER_TRANSACTION.TYPE)
            .from(LEDGER_TRANSACTION)
            .fetch()
            .map { enumById<LedgerTransactionType>(it.value1()!!) }

        assertEquals(1, transactionTypes.count { it == LedgerTransactionType.CAPTURE })
        assertEquals(1, transactionTypes.count { it == LedgerTransactionType.REFUND })
        assertEquals(PaymentStatus.REFUNDED, enumById(dsl.selectFrom(PAYMENT).fetchSingle().status))

        val ledgerEntries = entries()
        assertEquals(money("0.00"), ledgerEntries.balance(PaymentPurpose.PSP))
        assertEquals(money("0.00"), ledgerEntries.balance(PaymentPurpose.TAX))
        assertEquals(money("-5.00"), ledgerEntries.balance(PaymentPurpose.REVENUE))
        assertEquals(money("5.00"), ledgerEntries.balance(PaymentPurpose.MERCHANT))
        assertEquals(money("0.00"), ledgerEntries.sumOf { it.second })
    }

    @Test
    fun `partial refund updates status and reverses tax proportionally`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)
        assertEquals(
            HttpStatusCode.OK,
            send(PAYMENT_REFUND_ENDPOINT, refundBody(amount = money("60.50"))).status,
        )

        val payment = dsl.selectFrom(PAYMENT).fetchSingle()
        val refund = dsl.selectFrom(REFUND).fetchSingle()
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, enumById<PaymentStatus>(payment.status))
        assertEquals(money("60.50"), refund.amount)
        assertEquals(1, dsl.fetchCount(REFUND))
        assertEquals(2, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(7, dsl.fetchCount(LEDGER_ENTRY))

        val ledgerEntries = entries()
        assertEquals(money("60.50"), ledgerEntries.balance(PaymentPurpose.PSP))
        assertEquals(money("-10.50"), ledgerEntries.balance(PaymentPurpose.TAX))
        assertEquals(money("-5.00"), ledgerEntries.balance(PaymentPurpose.REVENUE))
        assertEquals(money("-45.00"), ledgerEntries.balance(PaymentPurpose.MERCHANT))
        assertEquals(money("0.00"), ledgerEntries.sumOf { it.second })
    }

    @Test
    fun `partial fraud refund returns revenue proportionally`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)
        assertEquals(
            HttpStatusCode.OK,
            send(
                PAYMENT_REFUND_ENDPOINT,
                refundBody(amount = money("60.50"), reason = "FRAUD"),
            ).status,
        )

        val refund = dsl.selectFrom(REFUND).fetchSingle()
        assertEquals(true, refund.feeReturned)
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, enumById(dsl.selectFrom(PAYMENT).fetchSingle().status))

        val ledgerEntries = entries()
        assertEquals(money("60.50"), ledgerEntries.balance(PaymentPurpose.PSP))
        assertEquals(money("-10.50"), ledgerEntries.balance(PaymentPurpose.TAX))
        assertEquals(money("-2.50"), ledgerEntries.balance(PaymentPurpose.REVENUE))
        assertEquals(money("-47.50"), ledgerEntries.balance(PaymentPurpose.MERCHANT))
        assertEquals(money("0.00"), ledgerEntries.sumOf { it.second })
    }

    @Test
    fun `multiple partial refunds finish as fully refunded without rounding remainder`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)

        val firstReference = "ref-${UUID.randomUUID()}"
        val secondReference = "ref-${UUID.randomUUID()}"
        val finalReference = "ref-${UUID.randomUUID()}"

        assertEquals(
            HttpStatusCode.OK,
            send(
                PAYMENT_REFUND_ENDPOINT,
                refundBody(refundReference = firstReference, amount = money("40.00"), reason = "DUPLICATE"),
            ).status,
        )
        assertEquals(
            PaymentStatus.PARTIALLY_REFUNDED,
            enumById<PaymentStatus>(dsl.selectFrom(PAYMENT).fetchSingle().status),
        )

        assertEquals(
            HttpStatusCode.OK,
            send(
                PAYMENT_REFUND_ENDPOINT,
                refundBody(refundReference = secondReference, amount = money("40.00"), reason = "DUPLICATE"),
            ).status,
        )
        assertEquals(
            PaymentStatus.PARTIALLY_REFUNDED,
            enumById<PaymentStatus>(dsl.selectFrom(PAYMENT).fetchSingle().status),
        )

        assertEquals(
            HttpStatusCode.OK,
            send(
                PAYMENT_REFUND_ENDPOINT,
                refundBody(refundReference = finalReference, amount = money("41.00"), reason = "DUPLICATE"),
            ).status,
        )

        assertEquals(PaymentStatus.REFUNDED, enumById(dsl.selectFrom(PAYMENT).fetchSingle().status))
        assertEquals(3, dsl.fetchCount(REFUND))
        assertEquals(4, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(16, dsl.fetchCount(LEDGER_ENTRY))
        assertEquals(true, dsl.selectFrom(REFUND).fetch().all { it.feeReturned })

        val ledgerEntries = entries()
        assertEquals(money("0.00"), ledgerEntries.balance(PaymentPurpose.PSP))
        assertEquals(money("0.00"), ledgerEntries.balance(PaymentPurpose.TAX))
        assertEquals(money("0.00"), ledgerEntries.balance(PaymentPurpose.REVENUE))
        assertEquals(money("0.00"), ledgerEntries.balance(PaymentPurpose.MERCHANT))
        assertEquals(money("0.00"), ledgerEntries.sumOf { it.second })
    }

    @Test
    fun `one thousand one-cent refunds cumulatively return all tax on a ten-euro payment`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

        assertEquals(
            HttpStatusCode.OK,
            send(PAYMENT_CAPTURE_ENDPOINT, paymentBody(amount = money("10.00"))).status,
        )
        assertEquals(money("1.74"), dsl.selectFrom(PAYMENT).fetchSingle().tax)

        repeat(999) { index ->
            assertEquals(
                HttpStatusCode.OK,
                send(
                    PAYMENT_REFUND_ENDPOINT,
                    refundBody(
                        refundReference = "cent-${index + 1}-${UUID.randomUUID()}",
                        amount = money("0.01"),
                    ),
                ).status,
            )
        }

        assertEquals(999, dsl.fetchCount(REFUND))
        assertEquals(money("0.00"), entries().balance(PaymentPurpose.TAX))
        assertEquals(money("0.01"), entries().balance(PaymentPurpose.PSP))
        assertEquals(
            PaymentStatus.PARTIALLY_REFUNDED,
            enumById<PaymentStatus>(dsl.selectFrom(PAYMENT).fetchSingle().status),
        )

        assertEquals(
            HttpStatusCode.OK,
            send(
                PAYMENT_REFUND_ENDPOINT,
                refundBody(refundReference = "cent-1000-${UUID.randomUUID()}", amount = money("0.01")),
            ).status,
        )

        assertEquals(1_000, dsl.fetchCount(REFUND))
        assertEquals(PaymentStatus.REFUNDED, enumById<PaymentStatus>(dsl.selectFrom(PAYMENT).fetchSingle().status))
        val ledgerEntries = entries()
        assertEquals(money("0.00"), ledgerEntries.balance(PaymentPurpose.PSP))
        assertEquals(money("0.00"), ledgerEntries.balance(PaymentPurpose.TAX))
        assertEquals(money("-0.41"), ledgerEntries.balance(PaymentPurpose.REVENUE))
        assertEquals(money("0.41"), ledgerEntries.balance(PaymentPurpose.MERCHANT))
        assertEquals(money("0.00"), ledgerEntries.sumOf { it.second })
    }

    @Test
    fun `unsuccessful PSP refund returns OK and writes nothing`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)
        val databaseBeforeRefund = databaseSnapshot()

        assertEquals(
            HttpStatusCode.OK,
            send(PAYMENT_REFUND_ENDPOINT, refundBody(success = false)).status,
        )

        assertEquals(databaseBeforeRefund, databaseSnapshot())
        assertEquals(0, dsl.fetchCount(REFUND))
    }

    @Test
    fun `fraud refund returns the MoR fee`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)
        assertEquals(
            HttpStatusCode.OK,
            send(PAYMENT_REFUND_ENDPOINT, refundBody(reason = "FRAUD")).status,
        )

        val refund = dsl.selectFrom(REFUND).fetchSingle()
        assertEquals(RefundReason.FRAUD, enumById<RefundReason>(refund.reason))
        assertEquals(true, refund.feeReturned)

        val ledgerEntries = entries()
        assertEquals(money("0.00"), ledgerEntries.balance(PaymentPurpose.PSP))
        assertEquals(money("0.00"), ledgerEntries.balance(PaymentPurpose.TAX))
        assertEquals(money("0.00"), ledgerEntries.balance(PaymentPurpose.REVENUE))
        assertEquals(money("0.00"), ledgerEntries.balance(PaymentPurpose.MERCHANT))
    }

    @Test
    fun `missing refund reason defaults to OTHER and keeps the fee`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)
        assertEquals(
            HttpStatusCode.OK,
            send(PAYMENT_REFUND_ENDPOINT, refundBody(reason = null)).status,
        )

        val refund = dsl.selectFrom(REFUND).fetchSingle()
        assertEquals(RefundReason.OTHER, enumById<RefundReason>(refund.reason))
        assertEquals(false, refund.feeReturned)
        assertEquals(money("-5.00"), entries().balance(PaymentPurpose.REVENUE))
    }

    @Test
    fun `product issue refund keeps the MoR fee`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)
        assertEquals(
            HttpStatusCode.OK,
            send(PAYMENT_REFUND_ENDPOINT, refundBody(reason = "PRODUCT_ISSUE")).status,
        )

        val refund = dsl.selectFrom(REFUND).fetchSingle()
        assertEquals(RefundReason.PRODUCT_ISSUE, enumById<RefundReason>(refund.reason))
        assertEquals(false, refund.feeReturned)
        assertEquals(money("-5.00"), entries().balance(PaymentPurpose.REVENUE))
    }

    @Test
    fun `refund of held payment reverses the held balance`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }

        val heldPaymentBody = paymentBody(
            billingCountry = "JP",
            cardIssuingCountry = "JP",
            ipCountry = "JP",
        )
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, heldPaymentBody).status)

        val heldPayment = dsl.selectFrom(PAYMENT).fetchSingle()
        assertEquals(PaymentStatus.HELD, enumById<PaymentStatus>(heldPayment.status))
        assertEquals(HoldReason.TAX_UNRESOLVED, enumById<HoldReason>(heldPayment.holdReason!!))

        assertEquals(HttpStatusCode.OK, send(PAYMENT_REFUND_ENDPOINT, refundBody()).status)

        val refundedPayment = dsl.selectFrom(PAYMENT).fetchSingle()
        assertEquals(PaymentStatus.REFUNDED, enumById<PaymentStatus>(refundedPayment.status))
        assertEquals(HoldReason.TAX_UNRESOLVED, enumById<HoldReason>(refundedPayment.holdReason!!))

        val ledgerEntries = entries()
        assertEquals(money("0.00"), ledgerEntries.balance(PaymentPurpose.PSP))
        assertEquals(money("0.00"), ledgerEntries.balance(PaymentPurpose.HELD))
        assertEquals(money("0.00"), ledgerEntries.sumOf { it.second })
    }

    @Test
    fun `bad signature returns unauthorized and writes nothing`() = testApplication {
        application {
            ledgerModule(
                config = AppConfig(psp = PspConfig(secret = "test-secret")),
                dsl = dsl,
            )
        }

        val databaseBeforeCall = databaseSnapshot()

        assertEquals(
            HttpStatusCode.Unauthorized,
            send(PAYMENT_REFUND_ENDPOINT, refundBody(), signature = "invalid").status,
        )

        assertEquals(databaseBeforeCall, databaseSnapshot())
    }

    @Test
    fun `refund amount with fractions of a cent is rejected`() = testApplication {
        application { ledgerModule(testConfig(), dsl, MerchantRegistry(setOf(merchantId))) }
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)
        val databaseBeforeRefund = databaseSnapshot()

        assertEquals(
            HttpStatusCode.BadRequest,
            send(PAYMENT_REFUND_ENDPOINT, refundBody(amount = money("1.001"))).status,
        )
        assertEquals(databaseBeforeRefund, databaseSnapshot())
    }
}
