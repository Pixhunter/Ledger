package org.example.service

import kotlinx.coroutines.runBlocking
import org.example.model.LedgerEntry
import org.example.model.PaymentEntity
import org.example.model.ProcessingError
import org.example.model.PaymentModel
import org.example.model.LedgerWrite
import org.example.model.enums.Currency
import org.example.model.enums.HoldReason
import org.example.model.enums.PaymentPurpose
import org.example.model.enums.PaymentStatus
import org.example.model.enums.ProcessingErrorCode
import repository.store.PaymentStore
import model.BasisPoints
import model.LedgerResult
import org.example.support.money
import java.math.BigDecimal
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.example.api.randomUuid

class PaymentServiceTest {

    private val merchantId = randomUuid()

    @Test
    fun `posts a payment for a known merchant in a supported country`() {
        val store = RecordingStore()

        val result = run(store, request())

        assertEquals(LedgerResult.Recorded(PaymentStatus.POSTED), result)
        val payment = store.single()
        assertEquals(PaymentStatus.POSTED, payment.status)
        assertTrue(payment.holdReasons.isEmpty())
        assertEquals(money("21.00"), payment.tax)
        assertEquals(money("3.00"), payment.fee)
        assertEquals(money("97.00"), payment.merchantNet)
        assertEquals("ES", payment.taxCountry)
        assertEquals(2_100, payment.taxRateBps)
    }

    @Test
    fun `entries always sum to zero`() {
        val store = RecordingStore()

        run(store, request())

        assertEquals(money("0"), store.entries.sumOf { it.amount })
        assertEquals(money("121.00"), store.amountFor(PaymentPurpose.PSP))
        assertEquals(money("-21.00"), store.amountFor(PaymentPurpose.TAX))
        assertEquals(money("-3.00"), store.amountFor(PaymentPurpose.REVENUE))
        assertEquals(money("-97.00"), store.amountFor(PaymentPurpose.MERCHANT))
    }

    @Test
    fun `holds a payment when no country has a rate`() {
        val store = RecordingStore()

        val result = run(store, request(country = "JP"))

        val recorded = assertIs<LedgerResult.Recorded>(result)
        assertEquals(PaymentStatus.POSTED, recorded.paymentStatus)
        assertEquals(ProcessingErrorCode.TAX_UNRESOLVED, recorded.error?.code)
        val payment = store.single()
        assertEquals(PaymentStatus.POSTED, payment.status)
        assertEquals(setOf(HoldReason.TAX_UNRESOLVED), payment.holdReasons)
        assertEquals(money("0"), payment.tax)
        assertEquals(money("121.00"), payment.merchantNet)
        assertEquals(money("-121.00"), store.amountFor(PaymentPurpose.HELD))
    }

    @Test
    fun `holds a payment for an unknown merchant without losing the merchant id`() {
        val store = RecordingStore()

        val result = run(store, request(), known = setOf(randomUuid()))

        val recorded = assertIs<LedgerResult.Recorded>(result)
        assertEquals(PaymentStatus.POSTED, recorded.paymentStatus)
        assertEquals(ProcessingErrorCode.UNKNOWN_MERCHANT, recorded.error?.code)
        val payment = store.single()
        assertEquals(PaymentStatus.POSTED, payment.status)
        assertEquals(setOf(HoldReason.UNKNOWN_MERCHANT), payment.holdReasons)
        assertEquals(merchantId, payment.merchantId)
        assertEquals(money("-97.00"), store.amountFor(PaymentPurpose.HELD))
        assertEquals(money("-21.00"), store.amountFor(PaymentPurpose.TAX))
    }

    @Test
    fun `a business buyer abroad pays no tax`() {
        val store = RecordingStore()

        run(store, request(vatId = "ESB12345678"))

        val payment = store.single()
        assertTrue(payment.reverseCharge)
        assertEquals(money("0"), payment.tax)
        assertEquals(PaymentStatus.POSTED, payment.status)
    }

    @Test
    fun `an invalid VAT ID records an error and uses consumer tax`() {
        val store = RecordingStore()

        run(store, request(vatId = "ES-NOT-A-VAT-ID"))

        val payment = store.single()
        assertTrue(!payment.reverseCharge)
        assertEquals(money("21.00"), payment.tax)
        assertEquals(ProcessingErrorCode.INVALID_VAT_ID, store.errors.single().code)
    }

    @Test
    fun `billing fallback records weak tax-country evidence`() {
        val store = RecordingStore()

        run(store, request().copy(cardIssuingCountry = "DE", ipCountry = "FR"))

        assertEquals("ES", store.single().taxCountry)
        assertEquals(ProcessingErrorCode.WEAK_TAX_COUNTRY_EVIDENCE, store.errors.single().code)
    }

    @Test
    fun `a suspicious date is recorded as an error without holding the payment`() {
        val store = RecordingStore()

        val result = run(store, request(paymentTime = Instant.parse("2026-10-05T10:15:30Z")))

        assertIs<LedgerResult.Recorded>(result)
        assertTrue(store.single().holdReasons.isEmpty())
        assertEquals(ProcessingErrorCode.INVALID_DATE, store.errors.single().code)
    }

    @Test
    fun `a payment older than tax history is held for manual processing`() {
        val store = RecordingStore()

        val result = run(store, request(paymentTime = Instant.parse("2020-12-31T23:59:59Z")))

        assertIs<LedgerResult.Recorded>(result)
        assertEquals(setOf(HoldReason.TAX_UNRESOLVED), store.single().holdReasons)
        assertEquals(
            setOf(ProcessingErrorCode.TAX_UNRESOLVED, ProcessingErrorCode.INVALID_DATE),
            store.errors.map { it.code }.toSet(),
        )
    }

    @Test
    fun `a replay is reported as a duplicate and decided only once`() {
        val store = RecordingStore(LedgerWrite.Duplicate(PaymentStatus.POSTED))

        val result = run(store, request())

        assertEquals(LedgerResult.Duplicate(PaymentStatus.POSTED), result)
    }

    private fun run(
        store: PaymentStore,
        request: PaymentModel,
        known: Set<UUID> = setOf(merchantId),
    ): LedgerResult = runBlocking {
        PaymentService(
            payments = store,
            rates = TaxRates(),
            merchants = InMemoryMerchantRegistry(known),
            feeRate = BasisPoints(300),
            morCountry = "NL",
            clock = Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC),
        ).createPayment(request)
    }

    private fun request(
        amount: BigDecimal = money("121.00"),
        country: String = "ES",
        vatId: String? = null,
        paymentTime: Instant = Instant.parse("2026-09-22T10:15:30Z"),
    ) = PaymentModel(
        pspReference = "psp-${randomUuid()}",
        merchantId = merchantId,
        amount = amount,
        currency = Currency.EUR,
        success = true,
        billingCountry = country,
        stateOrProvince = null,
        cardIssuingCountry = country,
        ipCountry = country,
        customerVatId = vatId,
        paymentTime = paymentTime,
    )

    private class RecordingStore(
        private val outcome: LedgerWrite? = null,
    ) : PaymentStore {

        val writes = mutableListOf<Pair<PaymentEntity, List<LedgerEntry>>>()
        val errors = mutableListOf<ProcessingError>()

        val entries: List<LedgerEntry> get() = writes.single().second

        fun single(): PaymentEntity = writes.single().first

        fun amountFor(purpose: PaymentPurpose): BigDecimal =
            entries.filter { it.purpose == purpose }.sumOf { it.amount }

        override suspend fun insert(
            payment: PaymentEntity,
            entries: List<LedgerEntry>,
            rawPayload: String,
            errors: List<ProcessingError>,
        ): LedgerWrite {
            writes += payment to entries
            this.errors += errors
            return outcome ?: LedgerWrite.Inserted(payment.status)
        }
    }
}
