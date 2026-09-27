package org.example.api.controller

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.MERCHANT
import org.example.jooq.tables.references.MERCHANT_PAYMENT_DETAILS
import org.example.jooq.tables.references.LEDGER_TRANSACTION
import org.example.jooq.tables.references.PAYMENT
import org.example.jooq.tables.references.PAYMENT_HOLD
import org.example.jooq.tables.references.REFUND
import org.example.api.security.PspSignature
import org.example.model.enumById
import org.example.model.enums.PaymentPurpose
import org.example.model.enums.TaxCategory
import org.jooq.JSONB
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
import org.example.api.randomUuid

abstract class LedgerApiIntegrationTestSupport : PostgresTest() {

    protected val merchantId: UUID = randomUuid()
    protected fun testConfig() = AppConfig(psp = PspConfig(PSP_SECRET))
    protected val pspReference = "psp-${randomUuid()}"
    protected val refundReference = "ref-${randomUuid()}"
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
        paymentTime: Instant = this.paymentTime,
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
        refundedAt: Instant = refundTime,
    ) = """
        {
          "refundReference": "$refundReference",
          "pspReference": "$pspReference",
          "amount": $amount,
          "currency": "EUR",
          "success": $success,
          ${reason?.let { """"reason": "$it",""" } ?: ""}
          "refundedAt": "$refundedAt"
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
        holds = dsl.selectFrom(PAYMENT_HOLD)
            .orderBy(PAYMENT_HOLD.PAYMENT_ID, PAYMENT_HOLD.REASON)
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

    protected fun seedMerchant(merchantId: UUID = this.merchantId) {
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

    protected companion object {
        const val PSP_SECRET = "test-psp-secret"
        const val PAYMENT_CAPTURE_ENDPOINT = "/v1/payment/capture"
        const val PAYMENT_REFUND_ENDPOINT = "/v1/payment/refund"
    }

    protected data class DatabaseSnapshot(
        val payments: List<List<Any?>>,
        val holds: List<List<Any?>>,
        val refunds: List<List<Any?>>,
        val transactions: List<List<Any?>>,
        val entries: List<List<Any?>>,
    )

}
