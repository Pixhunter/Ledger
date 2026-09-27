package org.example.load

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.runTestApplication
import io.ktor.test.dispatcher.runTestWithRealTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.example.api.security.PspSignature
import org.example.bootstrap.ledgerModule
import org.example.config.AppConfig
import org.example.config.DatabaseConfig
import org.example.config.PspConfig
import org.example.database.Database
import org.example.database.Migrations
import org.example.model.enums.PaymentPurpose
import org.example.repository.PayoutRepository
import org.example.repository.ProcessingErrorRepository
import org.example.repository.TaxRemittanceRepository
import org.example.service.InMemoryMerchantRegistry
import org.example.service.scheduled.PayoutCalculationJob
import org.example.service.scheduled.TaxRemittanceCalculationJob
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in benchmark. Run only with `./gradlew mixedLoadTest`. */
class MixedLedgerLoadTest {

    @Test
    fun `mixed captures duplicates and concurrent refunds preserve the ledger`() =
        runTestWithRealTime(timeout = 3.minutes) {
            runTestApplication {
        val seedPayments = setting("load.seedPayments", 10_000)
        val merchantCount = setting("load.merchants", 1_000)
        val workers = setting("load.workers", 32)
        val seconds = setting("load.seconds", 60)
        require(seedPayments >= merchantCount && merchantCount > 0 && workers > 0 && seconds > 0)

        val runId = System.currentTimeMillis().toString()
        val merchants = seed(seedPayments, merchantCount)
        val hotMerchant = merchants.first()
        val hotPaymentReference = "mixed-$runId-hot-payment"
        val duplicateRefundReference = "mixed-$runId-duplicate-refund"
        val paymentTime = Instant.now().minusSeconds(60)
        val refundTime = Instant.now()
        val hotPayment = paymentBody(hotPaymentReference, hotMerchant, "999999.99", paymentTime)
        val duplicateRefund = refundBody(duplicateRefundReference, hotPaymentReference, refundTime)

        application {
            ledgerModule(
                AppConfig(psp = PspConfig(SECRET)),
                dsl,
                InMemoryMerchantRegistry(merchants.toSet()),
            )
        }

        assertEquals(HttpStatusCode.OK, send("/v1/payment/capture", hotPayment))
        assertEquals(HttpStatusCode.OK, send("/v1/payment/refund", duplicateRefund))

        val attempts = AtomicInteger()
        val successful = AtomicInteger()
        val failed = AtomicInteger()
        val newPayments = AtomicInteger()
        val uniqueRefunds = AtomicInteger()
        val latencies = ConcurrentLinkedQueue<Long>()
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        val started = System.nanoTime()

        coroutineScope {
            List(workers) {
                async(Dispatchers.Default) {
                    while (System.nanoTime() < deadline) {
                        val sequence = attempts.getAndIncrement()
                        val operation = sequence % 10
                        val body: String
                        val path: String

                        when (operation) {
                            in 0..5 -> {
                                val id = newPayments.getAndIncrement()
                                path = "/v1/payment/capture"
                                body = paymentBody(
                                    "mixed-$runId-pay-$id",
                                    merchants[id % merchants.size],
                                    "121.00",
                                    paymentTime,
                                )
                            }
                            6, 7 -> {
                                val id = uniqueRefunds.getAndIncrement()
                                path = "/v1/payment/refund"
                                body = refundBody("mixed-$runId-ref-$id", hotPaymentReference, refundTime)
                            }
                            8 -> {
                                path = "/v1/payment/capture"
                                body = hotPayment
                            }
                            else -> {
                                path = "/v1/payment/refund"
                                body = duplicateRefund
                            }
                        }

                        val requestStarted = System.nanoTime()
                        val status = send(path, body)
                        latencies += System.nanoTime() - requestStarted
                        if (status == HttpStatusCode.OK) successful.incrementAndGet() else failed.incrementAndGet()
                    }
                }
            }.awaitAll()
        }

        val elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0
        assertEquals(0, failed.get(), "every submitted money event must be acknowledged")
        assertEquals(newPayments.get(), count("mor.payment", "psp_reference LIKE 'mixed-$runId-pay-%'"))
        assertEquals(uniqueRefunds.get(), count("mor.refund", "refund_reference LIKE 'mixed-$runId-ref-%'"))
        assertEquals(1, count("mor.payment", "psp_reference = '$hotPaymentReference'"))
        assertEquals(1, count("mor.refund", "refund_reference = '$duplicateRefundReference'"))
        assertEquals(0, scalarInt("""
            SELECT count(*) FROM (
                SELECT transaction_id, currency
                FROM mor.ledger_entry
                GROUP BY transaction_id, currency
                HAVING sum(amount) <> 0
            ) unbalanced
        """))
        assertEquals(0, scalarInt("""
            SELECT count(*) FROM mor.payment
            WHERE gross <> tax + fee + merchant_net
        """))
        assertEquals(0, count("mor.processing_error", "external_reference LIKE 'mixed-$runId-%'"))

        val payoutStarted = System.nanoTime()
        val payoutDate = LocalDate.now(ZoneId.of("Europe/London"))
        PayoutCalculationJob(PayoutRepository(dsl), ProcessingErrorRepository(dsl)).run(payoutDate)
        val payoutMillis = (System.nanoTime() - payoutStarted) / 1_000_000

        val taxStarted = System.nanoTime()
        TaxRemittanceCalculationJob(TaxRemittanceRepository(dsl))
            .run(payoutDate.withDayOfMonth(1))
        val taxMillis = (System.nanoTime() - taxStarted) / 1_000_000

        assertEquals(merchantCount, count("mor.payout", "TRUE"))
        assertEquals(1, count("mor.tax_remittance", "country = 'ES'"))
        assertEquals(0, balance(PaymentPurpose.MERCHANT.id))
        assertEquals(0, balance(PaymentPurpose.TAX.id))
        assertEquals(0, scalarInt("""
            SELECT count(*) FROM (
                SELECT transaction_id, currency
                FROM mor.ledger_entry
                GROUP BY transaction_id, currency
                HAVING sum(amount) <> 0
            ) unbalanced
        """))
        assertTrue(scalarBoolean("""
            SELECT -COALESCE(sum(le.amount), 0) = COALESCE((SELECT sum(fee) FROM mor.payment), 0)
            FROM mor.ledger_entry le WHERE le.purpose = ${PaymentPurpose.REVENUE.id}
        """), "retained revenue must equal the fees frozen on payments")

        val sortedMillis = latencies.map { it / 1_000_000.0 }.sorted()
        val result = Result(
            seedPayments, merchantCount, workers, elapsedSeconds,
            attempts.get(), successful.get(), failed.get(),
            percentile(sortedMillis, 0.50), percentile(sortedMillis, 0.95),
            percentile(sortedMillis, 0.99), payoutMillis, taxMillis,
        )
        writeResult(runId, result)
                println(result.render())
            }
    }

    private fun seed(paymentCount: Int, merchantCount: Int): List<UUID> =
        dsl.transactionResult { configuration ->
        val db = DSL.using(configuration)
        db.execute("""
            INSERT INTO mor.merchant (id, name, currency, fee_rate_bps, tax_category, status)
            SELECT md5('load-merchant-' || n)::uuid, 'Load merchant ' || n, 'EUR', 500, 1, 1
            FROM generate_series(1, ?) n
        """.trimIndent(), merchantCount)
        db.execute("""
            INSERT INTO mor.merchant_payment_details
                (merchant_id, psp_account_id, account_holder, iban, bank_country, address)
            SELECT md5('load-merchant-' || n)::uuid, 'load-account-' || n,
                   'Load merchant ' || n, 'DE89370400440532013000', 'DE', '{"country":"DE"}'::jsonb
            FROM generate_series(1, ?) n
        """.trimIndent(), merchantCount)
        db.execute("""
            INSERT INTO mor.payment
                (id, psp_reference, merchant_id, gross, tax, fee, merchant_net, currency,
                 tax_country, tax_category, tax_rate_bps, reverse_charge, evidence, status, payment_time)
            SELECT md5('load-payment-' || n)::uuid, 'seed-' || n,
                   md5('load-merchant-' || (((n - 1) % ?) + 1))::uuid,
                   121.0000, 21.0000, 5.0000, 95.0000, 'EUR', 'ES', 1, 2100, false,
                   '{"billing":"ES","card":"ES","ip":"ES"}'::jsonb, 1, now() - interval '1 hour'
            FROM generate_series(1, ?) n
        """.trimIndent(), merchantCount, paymentCount)
        db.execute("""
            INSERT INTO mor.ledger_transaction (id, type, payment_id)
            SELECT md5('load-transaction-' || n)::uuid, 1, md5('load-payment-' || n)::uuid
            FROM generate_series(1, ?) n
        """.trimIndent(), paymentCount)
        db.execute("""
            INSERT INTO mor.ledger_entry
                (transaction_id, purpose, purpose_key, amount, currency, occurred_at)
            SELECT md5('load-transaction-' || n)::uuid, x.purpose,
                   CASE x.purpose
                       WHEN 2 THEN 'ES'
                       WHEN 4 THEN md5('load-merchant-' || (((n - 1) % ?) + 1))::uuid::text
                       ELSE NULL
                   END,
                   x.amount, 'EUR', now() - interval '1 hour'
            FROM generate_series(1, ?) n
            CROSS JOIN (VALUES (1, 121.0000), (2, -21.0000), (3, -5.0000), (4, -95.0000)) x(purpose, amount)
        """.trimIndent(), merchantCount, paymentCount)

        db.select(org.example.jooq.tables.references.MERCHANT.ID)
            .from(org.example.jooq.tables.references.MERCHANT)
            .orderBy(org.example.jooq.tables.references.MERCHANT.ID)
            .fetch(org.example.jooq.tables.references.MERCHANT.ID)
            .filterNotNull()
    }

    private suspend fun ApplicationTestBuilder.send(path: String, body: String): HttpStatusCode {
        val signature = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(SECRET.toByteArray(), "HmacSHA256"))
            doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        return client.post(path) {
            contentType(ContentType.Application.Json)
            header(PspSignature.HEADER, signature)
            setBody(body)
        }.status
    }

    private fun paymentBody(reference: String, merchant: UUID, amount: String, at: Instant) =
        """{"pspReference":"$reference","merchantId":"$merchant","amount":$amount,"currency":"EUR","success":true,"billingAddress":{"country":"ES"},"cardIssuingCountry":"ES","ipCountry":"ES","paymentTime":"$at"}"""

    private fun refundBody(reference: String, payment: String, at: Instant) =
        """{"refundReference":"$reference","pspReference":"$payment","amount":0.01,"currency":"EUR","success":true,"reason":"CUSTOMER_REQUEST","refundedAt":"$at"}"""

    private fun count(table: String, where: String) = scalarInt("SELECT count(*) FROM $table WHERE $where")
    private fun scalarInt(sql: String) = dsl.fetchOne(sql)?.get(0, Int::class.java) ?: 0
    private fun scalarBoolean(sql: String) = dsl.fetchOne(sql)?.get(0, Boolean::class.java) ?: false
    private fun balance(purpose: Short) = dsl.fetchValue(
        "SELECT COALESCE(sum(amount), 0) = 0 FROM mor.ledger_entry WHERE purpose = ?",
        purpose,
        Boolean::class.java,
    ).let { if (it == true) 0 else 1 }

    private fun percentile(values: List<Double>, fraction: Double): Double =
        values[(ceil(values.size * fraction).toInt() - 1).coerceIn(0, values.lastIndex)]

    private fun writeResult(runId: String, result: Result) {
        val directory = Path.of("load-test-results")
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("mixed-load-$runId.md"), result.render())
    }

    private fun setting(name: String, default: Int) = System.getProperty(name)?.toIntOrNull() ?: default

    private data class Result(
        val seedPayments: Int,
        val merchants: Int,
        val workers: Int,
        val seconds: Double,
        val attempts: Int,
        val successful: Int,
        val failed: Int,
        val p50: Double,
        val p95: Double,
        val p99: Double,
        val payoutMillis: Long,
        val taxMillis: Long,
    ) {
        fun render() = """
            # Mixed ledger load result

            - Existing payments: $seedPayments
            - Merchants: $merchants
            - Workers: $workers
            - Duration: ${String.format(Locale.ROOT, "%.2f", seconds)} seconds
            - Requests: $attempts ($successful successful, $failed failed)
            - Throughput: ${String.format(Locale.ROOT, "%.1f", attempts / seconds)} requests/second
            - Latency: p50 ${String.format(Locale.ROOT, "%.1f", p50)} ms, p95 ${String.format(Locale.ROOT, "%.1f", p95)} ms, p99 ${String.format(Locale.ROOT, "%.1f", p99)} ms
            - Payout calculation: $payoutMillis ms
            - Tax calculation: $taxMillis ms
            - Database invariants: PASS
        """.trimIndent() + "\n"
    }

    companion object {
        private const val SECRET = "mixed-load-secret"
        private lateinit var container: PostgreSQLContainer<*>
        private lateinit var dataSource: HikariDataSource
        private lateinit var dsl: DSLContext

        @JvmStatic
        @BeforeAll
        fun startDatabase() {
            container = PostgreSQLContainer("postgres:16-alpine").apply { start() }
            dataSource = Database.dataSource(
                DatabaseConfig(
                    url = container.jdbcUrl,
                    user = container.username,
                    password = container.password,
                    poolSize = 40,
                    statementTimeoutMs = 120_000,
                    lockTimeoutMs = 30_000,
                )
            ) as HikariDataSource
            Migrations.run(dataSource)
            dsl = Database.dslContext(dataSource)
        }

        @JvmStatic
        @AfterAll
        fun stopDatabase() {
            dataSource.close()
            container.stop()
        }
    }
}
