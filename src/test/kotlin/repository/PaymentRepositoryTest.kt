package org.example.repository

import kotlinx.coroutines.runBlocking
import org.example.db.Database
import org.example.db.Migrations
import org.example.config.DatabaseConfig
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.LEDGER_TRANSACTION
import org.example.jooq.tables.references.PAYMENT
import org.example.model.LedgerEntry
import org.example.model.PaymentEntity
import org.example.model.PaymentWrite
import org.example.model.enumById
import org.example.model.enums.Currency
import org.example.model.enums.HoldReason
import org.example.model.enums.LedgerTransactionType
import org.example.model.enums.PaymentPurpose
import org.example.model.enums.PaymentStatus
import org.example.model.enums.TaxCategory
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the real migration against a real Postgres. An in-memory database
 * would not have jsonb, partial indexes, or the CHECK constraints that carry
 * half the invariants, so it would prove nothing about what actually ships.
 *
 * Needs Docker. One container is shared by every test in the class and the
 * tables are truncated between them - starting a container per test costs
 * seconds each.
 */
class PaymentRepositoryTest {

    private val merchantId = UUID.randomUUID()
    private val paymentTime = Instant.parse("2026-09-22T10:15:30Z")

    /**
     * In a transaction on purpose: the pool runs with autoCommit off, so an
     * uncommitted statement is rolled back when the connection goes back to
     * Hikari and the truncate would silently do nothing.
     */
    @BeforeTest
    fun start() {
        assumeTrue(dockerAvailable, "Docker is not running - database tests skipped")
        clean()
    }

    private fun clean() = dsl.transaction { cfg ->
        val db = DSL.using(cfg)
        db.truncate(LEDGER_ENTRY).cascade().execute()
        db.truncate(LEDGER_TRANSACTION).cascade().execute()
        db.truncate(PAYMENT).cascade().execute()
    }

    @Test
    fun `stores the payment, its transaction and its entries in one go`() {
        val payment = posted()

        val outcome = runBlocking { repository.insert(payment, entries(payment)) }

        assertEquals(PaymentWrite.Inserted, outcome)

        val row = dsl.selectFrom(PAYMENT).fetchSingle()
        assertEquals(payment.id, row.id)
        assertEquals("psp-1", row.pspReference)
        assertEquals(merchantId, row.merchantId)
        assertEquals(12_100, row.gross)
        assertEquals(2_100, row.tax)
        assertEquals(300, row.fee)
        assertEquals(9_700, row.merchantNet)
        assertEquals("EUR", row.currency)
        assertEquals("ES", row.taxCountry)
        assertEquals(2_100, row.taxRateBps)
        assertEquals(false, row.reverseCharge)
        assertEquals(PaymentStatus.POSTED, enumById<PaymentStatus>(row.status))
        assertNull(row.holdReason)
        assertEquals(paymentTime, row.paymentTime.toInstant())

        val transaction = dsl.selectFrom(LEDGER_TRANSACTION).fetchSingle()
        assertEquals(payment.id, transaction.paymentId)
        assertEquals(LedgerTransactionType.CAPTURE, enumById<LedgerTransactionType>(transaction.type))

        assertEquals(4, storedEntries().size)
        assertEquals(0, storedEntries().sumOf { it.second })
        assertEquals(12_100, amountFor(PaymentPurpose.PSP))
        assertEquals(-2_100, amountFor(PaymentPurpose.TAX))
        assertEquals(-300, amountFor(PaymentPurpose.REVENUE))
        assertEquals(-9_700, amountFor(PaymentPurpose.MERCHANT))
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
        val second = posted(id = UUID.randomUUID())

        val firstOutcome = runBlocking { repository.insert(first, entries(first)) }
        val secondOutcome = runBlocking { repository.insert(second, entries(second)) }

        assertEquals(PaymentWrite.Inserted, firstOutcome)
        assertEquals(PaymentWrite.Duplicate(PaymentStatus.POSTED, null), secondOutcome)

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

        assertEquals(
            PaymentWrite.Duplicate(PaymentStatus.HELD, HoldReason.UNKNOWN_MERCHANT),
            replay,
        )
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
    fun `a second currency must balance on its own`() {
        val payment = posted()
        val strayCurrency = entries(payment) + LedgerEntry(PaymentPurpose.REVENUE, null, 1, Currency.USD)

        assertFailsWith<IllegalArgumentException> {
            runBlocking { repository.insert(payment, strayCurrency) }
        }

        assertEquals(0, dsl.fetchCount(PAYMENT))
        assertEquals(0, dsl.fetchCount(LEDGER_TRANSACTION))
    }

    private fun storedEntries(): List<Pair<PaymentPurpose, Long>> =
        dsl.select(LEDGER_ENTRY.PURPOSE, LEDGER_ENTRY.AMOUNT)
            .from(LEDGER_ENTRY)
            .fetch()
            .map { enumById<PaymentPurpose>(it.value1()!!) to it.value2()!! }

    private fun amountFor(purpose: PaymentPurpose): Long =
        storedEntries().filter { it.first == purpose }.sumOf { it.second }

    private fun posted(id: UUID = UUID.randomUUID()) = PaymentEntity(
        id = id,
        pspReference = "psp-1",
        merchantId = merchantId,
        gross = 12_100,
        tax = 2_100,
        fee = 300,
        merchantNet = 9_700,
        currency = Currency.EUR,
        taxCountry = "ES",
        taxCategory = TaxCategory.STANDARD,
        taxRateBps = 2_100,
        reverseCharge = false,
        evidence = mapOf("billing" to "ES", "card" to "ES", "ip" to null),
        status = PaymentStatus.POSTED,
        holdReason = null,
        paymentTime = paymentTime,
    )

    private fun held() = posted().copy(
        status = PaymentStatus.HELD,
        holdReason = HoldReason.UNKNOWN_MERCHANT,
    )

    private fun entries(payment: PaymentEntity): List<LedgerEntry> {
        val key = payment.merchantId?.toString()
        val held = payment.status == PaymentStatus.HELD
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

    companion object {

        /**
         * Everything below is lazy so that a clone without Docker reports
         * these tests as skipped rather than failing the whole build. A
         * container started in an initialiser throws before any assumption
         * can run.
         */
        private val dockerAvailable: Boolean =
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)

        private val postgres: PostgreSQLContainer<*> by lazy {
            PostgreSQLContainer("postgres:16-alpine").apply { start() }
        }

        private val dsl: DSLContext by lazy {
            val dataSource = Database.dataSource(
                DatabaseConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    poolSize = 2,
                )
            )
            Migrations.run(dataSource)
            Database.dslContext(dataSource)
        }

        private val repository: PaymentRepository by lazy { PaymentRepository(dsl) }
    }
}
