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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

abstract class LedgerApiIntegrationTestSupport : PostgresTest() {

    protected val merchantId: UUID = UUID.randomUUID()
    protected val pspReference = "psp-${UUID.randomUUID()}"
    protected val refundReference = "ref-${UUID.randomUUID()}"
    private val paymentTime: Instant = Instant.now().minus(1, ChronoUnit.HOURS)
    private val refundTime: Instant = Instant.now()

    protected fun paymentBody(
        pspReference: String = this.pspReference,
        merchantId: UUID = this.merchantId,
        amount: Long = 12_100,
        billingCountry: String? = "ES",
        cardIssuingCountry: String = "ES",
        ipCountry: String? = "ES",
        customerVatId: String? = null,
    ) = """
        {
          "pspReference": "$pspReference",
          "merchantId": "$merchantId",
          "amount": $amount,
          "currency": "EUR",
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
        amount: Long = 12_100,
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

    protected suspend fun ApplicationTestBuilder.send(
        path: String,
        body: String,
        signature: String? = null,
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

    protected fun entries(): List<Pair<PaymentPurpose, Long>> =
        dsl.select(LEDGER_ENTRY.PURPOSE, LEDGER_ENTRY.AMOUNT)
            .from(LEDGER_ENTRY)
            .fetch()
            .map { enumById<PaymentPurpose>(it.value1()!!) to it.value2()!! }

    protected fun List<Pair<PaymentPurpose, Long>>.balance(purpose: PaymentPurpose): Long =
        filter { it.first == purpose }.sumOf { it.second }

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
