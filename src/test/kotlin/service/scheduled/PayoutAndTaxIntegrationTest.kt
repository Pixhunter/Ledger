package org.example.service.scheduled

import io.ktor.http.HttpStatusCode
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.LEDGER_TRANSACTION
import org.example.jooq.tables.references.MERCHANT_DAILY_BALANCE
import org.example.jooq.tables.references.MERCHANT
import org.example.jooq.tables.references.PAYOUT
import org.example.jooq.tables.references.PAYMENT
import org.example.jooq.tables.references.PAYMENT_HOLD
import org.example.jooq.tables.references.PROCESSING_ERROR
import org.example.jooq.tables.references.TAX_REMITTANCE
import org.example.bootstrap.ledgerModule
import model.enums.enumById
import org.example.model.enums.PaymentPurpose
import org.example.model.enums.LedgerTransactionType
import org.example.model.enums.PayoutStatus
import org.example.model.enums.ProcessingErrorCode
import org.example.repository.PayoutRepository
import org.example.repository.ProcessingErrorRepository
import org.example.repository.TaxRemittanceRepository
import org.example.service.InMemoryMerchantRegistry
import org.example.support.money
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.api.randomUuid
import model.TransferKind
import model.SentTransfer
import org.example.support.RecordingTransferClient
import org.example.model.enums.MerchantStatus
import org.example.api.controller.LedgerApiIntegrationTestSupport
import org.example.api.apiJson
import org.jooq.impl.DSL
import kotlin.test.assertNotEquals

class PayoutAndTaxIntegrationTest : LedgerApiIntegrationTestSupport() {

    private val payoutDate: LocalDate = LocalDate.now(ZoneId.of("Europe/London"))
    // Captures are dated now, so the filing period has to be the month they
    // fall in: the job only files tax points before the period end.
    private val period: LocalDate = LocalDate.now(ZoneId.of("Europe/London")).withDayOfMonth(1)

    private val errors by lazy { ProcessingErrorRepository(dsl) }
    private val payoutStore by lazy { PayoutRepository(dsl) }
    private val remittanceStore by lazy { TaxRemittanceRepository(dsl) }

    @Test
    fun `compute endpoints return serializable run details`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }
        seedMerchant()

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)

        val payoutResponse = client.post("/v1/payouts/compute")
        assertEquals(HttpStatusCode.OK, payoutResponse.status)
        val payoutJson = apiJson.parseToJsonElement(payoutResponse.bodyAsText()).jsonObject
        assertEquals(1, payoutJson.getValue("merchants").jsonPrimitive.content.toInt())
        assertEquals(1, payoutJson.getValue("payouts").jsonArray.size)

        val taxResponse = client.post("/v1/tax-remittances/compute")
        assertEquals(HttpStatusCode.OK, taxResponse.status)
        val taxJson = apiJson.parseToJsonElement(taxResponse.bodyAsText()).jsonObject
        assertEquals(1, taxJson.getValue("countries").jsonPrimitive.content.toInt())
        assertEquals(1, taxJson.getValue("remittances").jsonArray.size)
    }

    @Test
    fun `ten captures become one payout, sent once, leaving the merchant balance at zero`() =
        testApplication {
            application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }
            seedMerchant()

            repeat(10) { index ->
                val response = send(PAYMENT_CAPTURE_ENDPOINT, paymentBody(pspReference = "psp-cap-$index"))
                assertEquals(HttpStatusCode.OK, response.status)
            }

            val owed = entries().balance(PaymentPurpose.MERCHANT).negate()
            assertTrue(owed.signum() > 0)

            assertEquals(1, runBlocking { PayoutCalculationJob(payoutStore, errors).run(payoutDate) }.size)

            val payout = dsl.selectFrom(PAYOUT).fetchSingle()
            assertEquals(merchantId, payout.merchantId)
            assertEquals(payoutDate, payout.payoutDate)
            assertEquals(0, payout.amount.compareTo(owed))
            assertEquals(PayoutStatus.COMPUTED, enumById<PayoutStatus>(payout.status))

            // The PAYOUT transaction settles the merchant account.
            assertEquals(money("0"), entries().balance(PaymentPurpose.MERCHANT))

            assertEquals(1, dsl.fetchCount(MERCHANT_DAILY_BALANCE))

            val psp = RecordingTransferClient()
            assertEquals(1, runBlocking { DisbursementJob(TransferKind.PAYOUT, payoutStore, psp).run() })

            val request = psp.sent.single()
            assertEquals("payout-$merchantId-$payoutDate", request.reference)
            assertEquals("acct-merchant-1", request.destination.reveal())
            assertEquals(0, request.amount.compareTo(owed))

            assertEquals(
                PayoutStatus.SENT,
                enumById<PayoutStatus>(dsl.selectFrom(PAYOUT).fetchSingle().status),
            )

            // A second run of either job pays nobody twice.
            assertTrue(runBlocking { PayoutCalculationJob(payoutStore, errors).run(payoutDate) }.isEmpty())
            assertEquals(0, runBlocking { DisbursementJob(TransferKind.PAYOUT, payoutStore, psp).run() })
            assertEquals(1, dsl.fetchCount(PAYOUT))
        }

    @Test
    fun `an expired worker cannot complete a payout reclaimed by another worker`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }
        seedMerchant()
        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)
        assertEquals(1, runBlocking { PayoutCalculationJob(payoutStore, errors).run(payoutDate) }.size)

        val expiredWorker = runBlocking { payoutStore.due(1) }.single()
        dsl.transaction { cfg ->
            DSL.using(cfg).update(PAYOUT)
                .set(PAYOUT.CLAIMED_AT, expiredWorker.claimedAt.minusMinutes(10))
                .where(PAYOUT.MERCHANT_ID.eq(merchantId))
                .and(PAYOUT.PAYOUT_DATE.eq(payoutDate))
                .execute()
        }

        val currentWorker = runBlocking { payoutStore.due(1) }.single()
        assertNotEquals(expiredWorker.claimedAt, currentWorker.claimedAt)

        assertEquals(
            0,
            runBlocking { payoutStore.markSent(listOf(SentTransfer(expiredWorker, "stale-reference"))) },
        )
        assertEquals(PayoutStatus.PROCESSING, enumById<PayoutStatus>(dsl.selectFrom(PAYOUT).fetchSingle().status))

        assertEquals(
            1,
            runBlocking { payoutStore.markSent(listOf(SentTransfer(currentWorker, "current-reference"))) },
        )
        val payout = dsl.selectFrom(PAYOUT).fetchSingle()
        assertEquals(PayoutStatus.SENT, enumById<PayoutStatus>(payout.status))
        assertEquals("current-reference", payout.pspReference)
    }

    @Test
    fun `a merchant batch is computed without losing or duplicating payouts`() = testApplication {
        val merchants = List(40) { randomUuid() }
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(merchants.toSet())) }

        merchants.forEachIndexed { index, id ->
            seedMerchant(id)
            assertEquals(
                HttpStatusCode.OK,
                send(PAYMENT_CAPTURE_ENDPOINT, paymentBody(pspReference = "batch-$index", merchantId = id)).status,
            )
        }

        val cutoff = Instant.now().plusSeconds(1)
        val balances = runBlocking { payoutStore.balancePage(null, merchants.size, cutoff) }
        val computed = runBlocking { payoutStore.computePayouts(balances, payoutDate, cutoff) }

        assertEquals(merchants.toSet(), computed.map { it.merchantId }.toSet())
        assertEquals(merchants.size, dsl.fetchCount(PAYOUT))
        assertEquals(
            merchants.size,
            dsl.fetchCount(
                LEDGER_TRANSACTION,
                LEDGER_TRANSACTION.TYPE.eq(LedgerTransactionType.PAYOUT.id),
            ),
        )
        assertEquals(money("0"), entries().balance(PaymentPurpose.MERCHANT))

        assertTrue(runBlocking { payoutStore.computePayouts(balances, payoutDate, cutoff) }.isEmpty())
        assertEquals(merchants.size, dsl.fetchCount(PAYOUT))
    }

    @Test
    fun `payment arriving after balance read is included in payout amount when settled`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }
        seedMerchant()

        assertEquals(
            HttpStatusCode.OK,
            send(PAYMENT_CAPTURE_ENDPOINT, paymentBody(pspReference = "before-balance-read")).status,
        )

        val cutoff = Instant.now().plusSeconds(60)
        val staleBalances = runBlocking { payoutStore.balancePage(null, 100, cutoff) }
        assertEquals(money("95.00"), staleBalances.single().amount)

        assertEquals(
            HttpStatusCode.OK,
            send(PAYMENT_CAPTURE_ENDPOINT, paymentBody(pspReference = "after-balance-read")).status,
        )

        val computed = runBlocking { payoutStore.computePayouts(staleBalances, payoutDate, cutoff) }

        assertEquals(money("190.00"), computed.single().amount)
        assertEquals(money("190.00"), dsl.selectFrom(PAYOUT).fetchSingle().amount)
        assertEquals(money("0.00"), entries().balance(PaymentPurpose.MERCHANT))
    }

    @Test
    fun `payout without payment details is not claimed`() {
        val transactionId = randomUuid()
        dsl.transaction { cfg ->
            val db = org.jooq.impl.DSL.using(cfg)
            db.insertInto(MERCHANT)
                .set(MERCHANT.ID, merchantId)
                .set(MERCHANT.NAME, "Merchant without payment details")
                .set(MERCHANT.CURRENCY, "EUR")
                .set(MERCHANT.FEE_RATE_BPS, 500)
                .set(MERCHANT.TAX_CATEGORY, 1.toShort())
                .set(MERCHANT.STATUS, MerchantStatus.ACTIVE.id)
                .execute()
            db.insertInto(LEDGER_TRANSACTION)
                .set(LEDGER_TRANSACTION.ID, transactionId)
                .set(LEDGER_TRANSACTION.TYPE, LedgerTransactionType.PAYOUT.id)
                .execute()
            db.insertInto(PAYOUT)
                .set(PAYOUT.MERCHANT_ID, merchantId)
                .set(PAYOUT.PAYOUT_DATE, payoutDate)
                .set(PAYOUT.AMOUNT, money("95.00"))
                .set(PAYOUT.CURRENCY, "EUR")
                .set(PAYOUT.LEDGER_TRANSACTION_ID, transactionId)
                .set(PAYOUT.STATUS, PayoutStatus.COMPUTED.id)
                .execute()
        }

        val claimed = runBlocking { payoutStore.due() }
        assertTrue(claimed.none { it.key == merchantId.toString() }, "claimed=$claimed")
        val payout = dsl.selectFrom(PAYOUT).fetchSingle()
        assertEquals(PayoutStatus.COMPUTED, enumById<PayoutStatus>(payout.status))
        assertEquals(null, payout.claimedAt)
    }

    @Test
    fun `captures in three countries are filed and paid per country`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }
        seedMerchant()

        listOf("DE", "ES", "FR").forEachIndexed { index, country ->
            val response = send(
                PAYMENT_CAPTURE_ENDPOINT,
                paymentBody(
                    pspReference = "psp-$country",
                    billingCountry = country,
                    cardIssuingCountry = country,
                    ipCountry = country,
                ),
            )
            assertEquals(HttpStatusCode.OK, response.status, "capture $index")
        }

        assertEquals(3, runBlocking { TaxRemittanceCalculationJob(remittanceStore).run(period) }.size)

        val filed = dsl.select(TAX_REMITTANCE.COUNTRY, TAX_REMITTANCE.AMOUNT)
            .from(TAX_REMITTANCE)
            .fetch()
            .associate { it.value1()!! to it.value2()!! }

        assertEquals(setOf("DE", "ES", "FR"), filed.keys)
        filed.values.forEach { assertTrue(it.signum() > 0) }

        // Every tax account is settled by the remittance transactions.
        assertEquals(money("0"), entries().balance(PaymentPurpose.TAX))

        val authority = RecordingTransferClient()
        assertEquals(3, runBlocking { DisbursementJob(TransferKind.TAX, remittanceStore, authority).run() })

        assertEquals(
            setOf("tax-DE-$period", "tax-ES-$period", "tax-FR-$period"),
            authority.references.toSet(),
        )

        assertTrue(runBlocking { TaxRemittanceCalculationJob(remittanceStore).run(period) }.isEmpty())
        assertEquals(3, dsl.fetchCount(TAX_REMITTANCE))
    }

    @Test
    fun `a suspended merchant is snapshotted but not paid`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }
        seedMerchant()
        dsl.transaction { cfg ->
            org.jooq.impl.DSL.using(cfg)
                .update(org.example.jooq.tables.references.MERCHANT)
                .set(org.example.jooq.tables.references.MERCHANT.STATUS, MerchantStatus.SUSPENDED.id)
                .execute()
        }

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)

        assertTrue(runBlocking { PayoutCalculationJob(payoutStore, errors).run(payoutDate) }.isEmpty())

        assertEquals(0, dsl.fetchCount(PAYOUT))
        assertEquals(1, dsl.fetchCount(MERCHANT_DAILY_BALANCE))
        assertTrue(entries().balance(PaymentPurpose.MERCHANT).signum() < 0)
    }

    @Test
    fun `a sale after the period end is not in that return`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }
        seedMerchant()

        assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)

        val owed = entries().balance(PaymentPurpose.TAX)
        assertTrue(owed.signum() < 0)

        val lastMonth = period.minusMonths(1)
        assertTrue(runBlocking { TaxRemittanceCalculationJob(remittanceStore).run(lastMonth) }.isEmpty())

        assertEquals(0, dsl.fetchCount(TAX_REMITTANCE))
        assertEquals(owed, entries().balance(PaymentPurpose.TAX))

        // The same sale is filed by the period it actually belongs to.
        assertEquals(1, runBlocking { TaxRemittanceCalculationJob(remittanceStore).run(period) }.size)
        assertEquals(money("0"), entries().balance(PaymentPurpose.TAX))
    }

    @Test
    fun `a refund after the period is filed leaves the country negative and is reported`() =
        testApplication {
            application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }
            seedMerchant()

            assertEquals(HttpStatusCode.OK, send(PAYMENT_CAPTURE_ENDPOINT, paymentBody()).status)
            runBlocking { TaxRemittanceCalculationJob(remittanceStore).run(period) }
            assertEquals(money("0"), entries().balance(PaymentPurpose.TAX))

            assertEquals(HttpStatusCode.OK, send(PAYMENT_REFUND_ENDPOINT, refundBody()).status)

            // The refund credited tax back after it was paid: we are owed, not owing.
            assertTrue(entries().balance(PaymentPurpose.TAX).signum() > 0)

            val monitor = TaxBalanceMonitorJob(remittanceStore, errors, negativeDaysLimit = 1)
            assertEquals(1, runBlocking { monitor.run(payoutDate) })

            val error = dsl.selectFrom(PROCESSING_ERROR).fetchSingle()
            assertEquals(
                ProcessingErrorCode.NEGATIVE_TAX_BALANCE,
                enumById<ProcessingErrorCode>(error.errorCode),
            )
            assertTrue(error.externalReference.startsWith("ES-"))
        }

    @Test
    fun `payment released today is included in payout regardless of capture date`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry()) }
        seedMerchant()
        assertEquals(
            HttpStatusCode.OK,
            send(
                PAYMENT_CAPTURE_ENDPOINT,
                paymentBody(paymentTime = Instant.now().minusSeconds(2 * 24 * 60 * 60L)),
            ).status,
        )

        val payment = dsl.selectFrom(PAYMENT).fetchSingle()
        val releaseId = randomUuid()
        val releasedAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
        dsl.transaction { cfg ->
            val db = org.jooq.impl.DSL.using(cfg)
            db.update(PAYMENT_HOLD)
                .set(PAYMENT_HOLD.RESOLVED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                .where(PAYMENT_HOLD.PAYMENT_ID.eq(payment.id))
                .execute()
            db.insertInto(LEDGER_TRANSACTION)
                .set(LEDGER_TRANSACTION.ID, releaseId)
                .set(LEDGER_TRANSACTION.TYPE, LedgerTransactionType.RELEASE.id)
                .set(LEDGER_TRANSACTION.PAYMENT_ID, payment.id)
                .execute()
            db.insertInto(
                LEDGER_ENTRY,
                LEDGER_ENTRY.TRANSACTION_ID,
                LEDGER_ENTRY.PURPOSE,
                LEDGER_ENTRY.PURPOSE_KEY,
                LEDGER_ENTRY.AMOUNT,
                LEDGER_ENTRY.CURRENCY,
                LEDGER_ENTRY.OCCURRED_AT,
            )
                .values(releaseId, PaymentPurpose.HELD.id, merchantId.toString(), money("95.00"), "EUR", releasedAt)
                .values(releaseId, PaymentPurpose.MERCHANT.id, merchantId.toString(), money("-95.00"), "EUR", releasedAt)
                .execute()
        }

        val computed = runBlocking { PayoutCalculationJob(payoutStore, errors).run(payoutDate) }
        assertEquals(1, computed.size)
        assertEquals(0, computed.single().amount.compareTo(money("95.00")))
        assertEquals(0, dsl.selectFrom(PAYOUT).fetchSingle().amount.compareTo(money("95.00")))
    }

    @Test
    fun `future-dated payment is not included in the current payout period`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }
        seedMerchant()

        assertEquals(
            HttpStatusCode.OK,
            send(
                PAYMENT_CAPTURE_ENDPOINT,
                paymentBody(paymentTime = payoutDate.plusDays(3).atStartOfDay(ZoneId.of("Europe/London")).toInstant()),
            ).status,
        )

        assertTrue(runBlocking { PayoutCalculationJob(payoutStore, errors).run(payoutDate) }.isEmpty())
        assertEquals(0, dsl.fetchCount(PAYOUT))
    }

    @Test
    fun `a late old payment is settled by the next eligible batch`() = testApplication {
        application { ledgerModule(testConfig(), dsl, InMemoryMerchantRegistry(setOf(merchantId))) }
        seedMerchant()
        assertEquals(
            HttpStatusCode.OK,
            send(
                PAYMENT_CAPTURE_ENDPOINT,
                paymentBody(paymentTime = Instant.now().minusSeconds(10 * 24 * 60 * 60L)),
            ).status,
        )

        assertTrue(runBlocking { PayoutCalculationJob(payoutStore, errors).run(payoutDate.minusDays(1)) }.isEmpty())
        assertEquals(1, runBlocking { PayoutCalculationJob(payoutStore, errors).run(payoutDate) }.size)
        assertEquals(
            0,
            dsl.fetchCount(
                org.jooq.impl.DSL.table("mor.ledger_entry"),
                org.jooq.impl.DSL.condition(
                    "purpose = ? AND settled_by_transaction_id IS NULL AND transaction_id IN " +
                        "(SELECT id FROM mor.ledger_transaction WHERE type <> ?)",
                    PaymentPurpose.MERCHANT.id,
                    LedgerTransactionType.PAYOUT.id,
                ),
            ),
        )
    }
}
