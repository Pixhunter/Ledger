package org.example.repository

import kotlinx.coroutines.runBlocking
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.LEDGER_TRANSACTION
import org.example.jooq.tables.references.PAYMENT
import org.example.jooq.tables.references.PAYMENT_HOLD
import org.example.jooq.tables.references.PROCESSING_ERROR
import org.example.model.LedgerEntry
import org.example.model.PaymentEntity
import org.example.model.ProcessingError
import org.example.model.LedgerWrite
import model.enums.enumById
import org.example.model.enums.Currency
import org.example.model.enums.EventType
import org.example.model.enums.HoldReason
import org.example.model.enums.LedgerTransactionType
import org.example.model.enums.PaymentPurpose
import org.example.model.enums.PaymentStatus
import org.example.model.enums.ProcessingErrorCode
import org.example.model.enums.TaxCategory
import org.example.support.PostgresTest
import org.example.support.money
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.example.api.randomUuid

class PaymentRepositoryTest : PostgresTest() {

    private val merchantId = randomUuid()
    private val paymentTime = Instant.parse("2026-09-22T10:15:30Z")
    private val repository by lazy { PaymentRepository(dsl) }

    @Test
    fun `stores the payment, its transaction and its entries in one go`() {
        val payment = posted()

        val outcome = runBlocking { repository.insert(payment, entries(payment)) }

        assertEquals(LedgerWrite.Inserted(PaymentStatus.POSTED), outcome)

        val row = dsl.selectFrom(PAYMENT).fetchSingle()
        assertEquals(payment.id, row.id)
        assertEquals("psp-1", row.pspReference)
        assertEquals(merchantId, row.merchantId)
        assertEquals(money("121.00"), row.gross)
        assertEquals(money("21.00"), row.tax)
        assertEquals(money("3.00"), row.fee)
        assertEquals(money("97.00"), row.merchantNet)
        assertEquals("EUR", row.currency)
        assertEquals("ES", row.taxCountry)
        assertEquals(2_100, row.taxRateBps)
        assertEquals(false, row.reverseCharge)
        assertEquals(PaymentStatus.POSTED, enumById<PaymentStatus>(row.status))
        assertEquals(0, dsl.fetchCount(PAYMENT_HOLD))
        assertEquals(paymentTime, row.paymentTime.toInstant())

        val transaction = dsl.selectFrom(LEDGER_TRANSACTION).fetchSingle()
        assertEquals(payment.id, transaction.paymentId)
        assertEquals(LedgerTransactionType.CAPTURE, enumById<LedgerTransactionType>(transaction.type))

        assertEquals(4, storedEntries().size)
        assertEquals(money("0"), storedEntries().sumOf { it.second })
        assertEquals(money("121.00"), amountFor(PaymentPurpose.PSP))
        assertEquals(money("-21.00"), amountFor(PaymentPurpose.TAX))
        assertEquals(money("-3.00"), amountFor(PaymentPurpose.REVENUE))
        assertEquals(money("-97.00"), amountFor(PaymentPurpose.MERCHANT))
    }

    @Test
    fun `evidence survives the round trip as jsonb`() {
        val payment = posted()

        runBlocking { repository.insert(payment, entries(payment)) }

        val stored = dsl.select(PAYMENT.EVIDENCE).from(PAYMENT).fetchSingle().value1()!!.data()
        assertTrue(stored.contains("\"billing\": \"ES\"") || stored.contains("\"billing\":\"ES\""))
    }

    @Test
    fun `the same psp reference is stored once however many times it arrives`() {
        val first = posted()
        val second = posted(id = randomUuid())

        val firstOutcome = runBlocking { repository.insert(first, entries(first)) }
        val secondOutcome = runBlocking { repository.insert(second, entries(second)) }

        assertEquals(LedgerWrite.Inserted(PaymentStatus.POSTED), firstOutcome)
        assertEquals(LedgerWrite.Duplicate(PaymentStatus.POSTED), secondOutcome)

        assertEquals(1, dsl.fetchCount(PAYMENT))
        assertEquals(1, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(4, dsl.fetchCount(LEDGER_ENTRY))
        assertEquals(first.id, dsl.selectFrom(PAYMENT).fetchSingle().id)
    }

    @Test
    fun `a replay answers with the decision taken the first time`() {
        val held = held()

        runBlocking { repository.insert(held, entries(held)) }
        val replay = runBlocking { repository.insert(held, entries(held)) }

        assertEquals(LedgerWrite.Duplicate(PaymentStatus.POSTED), replay)
    }

    @Test
    fun `entries that do not sum to zero never reach the database`() {
        val payment = posted()
        val unbalanced = entries(payment).dropLast(1)

        assertFailsWith<IllegalArgumentException> {
            runBlocking { repository.insert(payment, unbalanced) }
        }

        assertEquals(0, dsl.fetchCount(PAYMENT))
        assertEquals(0, dsl.fetchCount(LEDGER_ENTRY))
    }

    @Test
    fun `processing error failure rolls back the held payment and ledger`() {
        val payment = held()
        val error = ProcessingError(
            id = randomUuid(),
            eventType = EventType.CAPTURE,
            externalReference = payment.pspReference,
            payload = "not-json",
            code = ProcessingErrorCode.UNKNOWN_MERCHANT,
            detail = "unknown merchant",
        )

        assertFailsWith<Exception> {
            runBlocking { repository.insert(payment, entries(payment), error.payload, listOf(error)) }
        }

        assertEquals(0, dsl.fetchCount(PAYMENT))
        assertEquals(0, dsl.fetchCount(LEDGER_TRANSACTION))
        assertEquals(0, dsl.fetchCount(LEDGER_ENTRY))
        assertEquals(0, dsl.fetchCount(PROCESSING_ERROR))
    }

    private fun storedEntries(): List<Pair<PaymentPurpose, BigDecimal>> =
        dsl.select(LEDGER_ENTRY.PURPOSE, LEDGER_ENTRY.AMOUNT)
            .from(LEDGER_ENTRY)
            .fetch()
            .map { enumById<PaymentPurpose>(it.value1()!!) to it.value2()!! }

    private fun amountFor(purpose: PaymentPurpose): BigDecimal =
        storedEntries().filter { it.first == purpose }.sumOf { it.second }

    private fun posted(id: UUID = randomUuid()) = PaymentEntity(
        id = id,
        pspReference = "psp-1",
        merchantId = merchantId,
        gross = money("121.00"),
        tax = money("21.00"),
        fee = money("3.00"),
        merchantNet = money("97.00"),
        currency = Currency.EUR,
        taxCountry = "ES",
        taxCategory = TaxCategory.STANDARD,
        taxRateBps = 2_100,
        reverseCharge = false,
        evidence = mapOf("billing" to "ES", "card" to "ES", "ip" to null),
        status = PaymentStatus.POSTED,
        holdReasons = emptySet(),
        paymentTime = paymentTime,
    )

    private fun held() = posted().copy(
        holdReasons = setOf(HoldReason.UNKNOWN_MERCHANT),
    )

    private fun entries(payment: PaymentEntity): List<LedgerEntry> {
        val key = payment.merchantId?.toString()
        val held = payment.holdReasons.isNotEmpty()
        return listOf(
            LedgerEntry(PaymentPurpose.PSP, null, payment.gross, payment.currency),
            LedgerEntry(PaymentPurpose.TAX, payment.taxCountry, -payment.tax, payment.currency),
            LedgerEntry(PaymentPurpose.REVENUE, null, -payment.fee, payment.currency),
            LedgerEntry(
                if (held) PaymentPurpose.HELD else PaymentPurpose.MERCHANT,
                key,
                -payment.merchantNet,
                payment.currency,
            ),
        )
    }
}
