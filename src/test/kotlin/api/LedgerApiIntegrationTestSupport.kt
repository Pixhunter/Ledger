package org.example.api

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.LEDGER_TRANSACTION
import org.example.jooq.tables.references.PAYMENT
import org.example.jooq.tables.references.REFUND
import org.example.api.security.PspSignature
import org.example.model.enumById
import org.example.model.enums.PaymentPurpose
import org.example.support.PostgresTest
import org.example.support.money
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.example.config.AppConfig
import org.example.config.PspConfig

abstract class LedgerApiIntegrationTestSupport : PostgresTest() {

    protected val merchantId: UUID = UUID.randomUUID()
    protected fun testConfig() = AppConfig(psp = PspConfig(PSP_SECRET))
    protected val pspReference = "psp-${UUID.randomUUID()}"
    protected val refundReference = "ref-${UUID.randomUUID()}"
    private val paymentTime: Instant = Instant.now().minus(1, ChronoUnit.HOURS)
    private val refundTime: Instant = Instant.now()

    protected fun paymentBody(
        pspReference: String = this.pspReference,
        merchantId: UUID = this.merchantId,
        amount: BigDecimal = money("121.00"),
        billingCountry: String? = "ES",
        cardIssuingCountry: String = "ES",
        ipCountry: String? = "ES",
        customerVatId: String? = null,
        success: Boolean = true,
    ) = """
        {
          "pspReference": "$pspReference",
          "merchantId": "$merchantId",
          "amount": $amount,
          "currency": "EUR",
          "success": $success,
          "billingAddress": ${billingCountry?.let { """{ "country": "$it" }""" } ?: "null"},
          "cardIssuingCountry": "$cardIssuingCountry",
          "ipCountry": ${ipCountry?.let { """"$it"""" } ?: "null"},
          "customerVatId": ${customerVatId?.let { """"$it"""" } ?: "null"},
          "paymentTime": "$paymentTime"
        }
    """.trimIndent()

    protected fun refundBody(
        refundReference: String = this.refundReference,
        pspReference: String = this.pspReference,
        amount: BigDecimal = money("121.00"),
        success: Boolean = true,
        reason: String? = "CUSTOMER_REQUEST",
    ) = """
        {
          "refundReference": "$refundReference",
          "pspReference": "$pspReference",
          "amount": $amount,
          "currency": "EUR",
          "success": $success,
          ${reason?.let { """"reason": "$it",""" } ?: ""}
          "refundedAt": "$refundTime"
        }
    """.trimIndent()

    protected fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(PSP_SECRET.toByteArray(), "HmacSHA256")) }
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    protected suspend fun ApplicationTestBuilder.send(
        path: String,
        body: String,
        signature: String? = sign(body),
    ): HttpResponse =
        client.post(path) {
            contentType(ContentType.Application.Json)
            signature?.let { header(PspSignature.HEADER, it) }
            setBody(body)
        }

    /**
     * Every column from every table affected by capture or refund. Comparing
     * snapshots proves a replay neither inserts rows nor mutates existing data.
     */
    protected fun databaseSnapshot() = DatabaseSnapshot(
        payments = dsl.selectFrom(PAYMENT)
            .orderBy(PAYMENT.ID)
            .fetch()
            .map { it.intoArray().toList() },
        refunds = dsl.selectFrom(REFUND)
            .orderBy(REFUND.ID)
            .fetch()
            .map { it.intoArray().toList() },
        transactions = dsl.selectFrom(LEDGER_TRANSACTION)
            .orderBy(LEDGER_TRANSACTION.ID)
            .fetch()
            .map { it.intoArray().toList() },
        entries = dsl.selectFrom(LEDGER_ENTRY)
            .orderBy(LEDGER_ENTRY.ID)
            .fetch()
            .map { it.intoArray().toList() },
    )

    protected fun entries(): List<Pair<PaymentPurpose, BigDecimal>> =
        dsl.select(LEDGER_ENTRY.PURPOSE, LEDGER_ENTRY.AMOUNT)
            .from(LEDGER_ENTRY)
            .fetch()
            .map { enumById<PaymentPurpose>(it.value1()!!) to it.value2()!! }

    protected fun List<Pair<PaymentPurpose, BigDecimal>>.balance(purpose: PaymentPurpose): BigDecimal =
        filter { it.first == purpose }.fold(money("0")) { total, entry -> total + entry.second }

    protected companion object {
        const val PSP_SECRET = "test-psp-secret"
    }

    protected data class DatabaseSnapshot(
        val payments: List<List<Any?>>,
        val refunds: List<List<Any?>>,
        val transactions: List<List<Any?>>,
        val entries: List<List<Any?>>,
    )

    protected companion object {
        const val PAYMENT_CAPTURE_ENDPOINT = "/v1/payment/capture"
        const val PAYMENT_REFUND_ENDPOINT = "/v1/payment/refund"
    }
}
