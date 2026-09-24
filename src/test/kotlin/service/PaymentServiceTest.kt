package org.example.service

import kotlinx.coroutines.runBlocking
import org.example.model.LedgerEntry
import org.example.model.PaymentEntity
import org.example.model.PaymentModel
import org.example.model.LedgerWrite
import org.example.model.enums.Currency
import org.example.model.enums.HoldReason
import org.example.model.enums.PaymentPurpose
import org.example.model.enums.PaymentStatus
import org.example.repository.PaymentStore
import org.example.tax.BasisPoints
import org.example.tax.TaxRates
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentServiceTest {

    private val merchantId = UUID.randomUUID()

    @Test
    fun `posts a payment for a known merchant in a supported country`() {
        val store = RecordingStore()

        val result = run(store, request())

        assertEquals(LedgerResult.Recorded(PaymentStatus.POSTED), result)
        val payment = store.single()
        assertEquals(PaymentStatus.POSTED, payment.status)
        assertNull(payment.holdReason)
        assertEquals(2_100, payment.tax)
        assertEquals(300, payment.fee)
        assertEquals(9_700, payment.merchantNet)
        assertEquals("ES", payment.taxCountry)
        assertEquals(2_100, payment.taxRateBps)
    }

    @Test
    fun `entries always sum to zero`() {
        val store = RecordingStore()

        run(store, request())

        assertEquals(0, store.entries.sumOf { it.amount })
        assertEquals(12_100, store.amountFor(PaymentPurpose.PSP))
        assertEquals(-2_100, store.amountFor(PaymentPurpose.TAX))
        assertEquals(-300, store.amountFor(PaymentPurpose.REVENUE))
        assertEquals(-9_700, store.amountFor(PaymentPurpose.MERCHANT))
    }

    @Test
    fun `holds a payment when no country has a rate`() {
        val store = RecordingStore()

        val result = run(store, request(country = "JP"))

        assertEquals(LedgerResult.Recorded(PaymentStatus.HELD), result)
        val payment = store.single()
        assertEquals(PaymentStatus.HELD, payment.status)
        assertEquals(HoldReason.TAX_UNRESOLVED, payment.holdReason)
        assertEquals(0, payment.tax)
        assertEquals(12_100, payment.merchantNet)
        assertEquals(-12_100, store.amountFor(PaymentPurpose.HELD))
    }

    @Test
    fun `holds a payment for an unknown merchant without losing the merchant id`() {
        val store = RecordingStore()

        val result = run(store, request(), known = setOf(UUID.randomUUID()))

        assertEquals(LedgerResult.Recorded(PaymentStatus.HELD), result)
        val payment = store.single()
        assertEquals(PaymentStatus.HELD, payment.status)
        assertEquals(HoldReason.UNKNOWN_MERCHANT, payment.holdReason)
        assertEquals(merchantId, payment.merchantId)
        assertEquals(-9_700, store.amountFor(PaymentPurpose.HELD))
        assertEquals(-2_100, store.amountFor(PaymentPurpose.TAX))
    }

    @Test
    fun `a business buyer abroad pays no tax`() {
        val store = RecordingStore()

        run(store, request(vatId = "ESB12345678"))

        val payment = store.single()
        assertTrue(payment.reverseCharge)
        assertEquals(0, payment.tax)
        assertEquals(PaymentStatus.POSTED, payment.status)
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
        known: Set<UUID> = emptySet(),
    ): LedgerResult = runBlocking {
        PaymentService(
            payments = store,
            rates = TaxRates(),
            merchants = MerchantRegistry(known),
            feeRate = BasisPoints(300),
            morCountry = "NL",
        ).createPayment(request)
    }

    private fun request(
        amount: Long = 12_100,
        country: String = "ES",
        vatId: String? = null,
    ) = PaymentModel(
        pspReference = "psp-${UUID.randomUUID()}",
        merchantId = merchantId,
        amount = amount,
        currency = Currency.EUR,
        billingCountry = country,
        stateOrProvince = null,
        cardIssuingCountry = country,
        ipCountry = country,
        customerVatId = vatId,
        paymentTime = Instant.parse("2026-09-22T10:15:30Z"),
    )

    private class RecordingStore(
        private val outcome: LedgerWrite? = null,
    ) : PaymentStore {

        val writes = mutableListOf<Pair<PaymentEntity, List<LedgerEntry>>>()

        val entries: List<LedgerEntry> get() = writes.single().second

        fun single(): PaymentEntity = writes.single().first

        fun amountFor(purpose: PaymentPurpose): Long =
            entries.filter { it.purpose == purpose }.sumOf { it.amount }

        override suspend fun insert(
            payment: PaymentEntity,
            entries: List<LedgerEntry>,
        ): LedgerWrite {
            writes += payment to entries
            return outcome ?: LedgerWrite.Inserted(payment.status)
        }
    }
}
