package org.example.service

import org.example.model.LedgerWrite
import org.example.model.PaymentEntity
import org.example.model.Money
import org.example.model.ProcessingError
import org.example.model.enums.EventType
import org.example.model.enums.HoldReason
import org.example.model.PaymentModel
import org.example.model.enums.PaymentStatus
import org.example.model.enums.ProcessingErrorCode
import org.example.model.enums.TaxCategory
import org.example.repository.PaymentStore
import org.example.tax.BasisPoints
import org.example.tax.TaxCalculator
import org.example.tax.TaxCountryVote
import org.example.tax.TaxRates
import org.example.tax.VatIdRules
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Decides everything WITHOUT touching the database, then writes once.
 *
 * The governing rule after capture: the PSP has already taken the customer's
 * money, so valid money is never rejected. Anything we cannot attribute is
 * recorded and HELD, not refused - a refusal only makes the PSP retry while
 * the cash sits in our bank with no row against it.
 *
 *   1. shape        -> 400 INVALID_REQUEST       (controller)
 *   2. tax country  -> HELD (TAX_UNRESOLVED)
 *   3. tax rate     -> HELD (TAX_UNRESOLVED)
 *   4. merchant     -> HELD (UNKNOWN_MERCHANT)
 *   5. one write
 */
class PaymentService(
    private val payments: PaymentStore,
    private val rates: TaxRates,
    private val merchants: MerchantRegistry,
    private val feeRate: BasisPoints,
    private val morCountry: String,
    // TODO Load the merchant's category when reduced/special VAT rates enter scope.
    private val taxCategory: TaxCategory = TaxCategory.STANDARD,
    private val vatIdRules: VatIdRules = VatIdRules(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(PaymentService::class.java)

    suspend fun createPayment(request: PaymentModel, rawPayload: String = "{}"): LedgerResult {
        // TODO store failed payments for reconciliation and support history. Needs the
        //      idempotency key to be the per-attempt PSP id: with an intent or order id the
        //      same reference can arrive FAILED then SUCCESS, and a stored FAILED row would
        //      swallow the success.
        if (!request.success) {
            log.info("payment {} failed at the PSP, nothing stored", request.pspReference)
            return LedgerResult.NothingToRecord
        }

        val evidence = mapOf(
            "billing" to request.billingCountry,
            "card" to request.cardIssuingCountry,
            "ip" to request.ipCountry,
            "vatId" to request.customerVatId,
        )

        val vote = TaxCountryVote.decide(
            billingCountry = request.billingCountry,
            cardIssuingCountry = request.cardIssuingCountry,
            ipCountry = request.ipCountry,
        )

        val taxCountry = (vote as? TaxCountryVote.Result.Decided)?.country

        val suppliedVatId = request.customerVatId?.takeIf { it.isNotBlank() }
        val validVatId = suppliedVatId?.let { vatIdRules.isValid(taxCountry, it, request.paymentTime) } == true
        val invalidVatId = suppliedVatId != null && !validVatId
        val reverseCharge = taxCountry != null && validVatId && taxCountry != morCountry

        val historicalRate = taxCountry?.let { rates.lookup(it, request.currency, request.paymentTime) }
        val rate = historicalRate?.let { if (reverseCharge) BasisPoints.ZERO else it.rate }

        val taxUnresolved = taxCountry == null || rate == null
        val known = merchants.exists(request.merchantId)
        val holdReasons = buildSet {
            if (taxUnresolved) add(HoldReason.TAX_UNRESOLVED)
            if (!known) add(HoldReason.UNKNOWN_MERCHANT)
        }
        val gross = request.amount
        val tax = if (taxUnresolved) Money.ZERO else TaxCalculator.tax(gross, rate, request.currency, reverseCharge)
        val fee = if (taxUnresolved) Money.ZERO else TaxCalculator.fee(gross - tax, feeRate, request.currency)

        val payment = PaymentEntity(
            id = UUID.randomUUID(),
            pspReference = request.pspReference,
            merchantId = request.merchantId,
            gross = gross,
            tax = tax,
            fee = fee,
            merchantNet = gross - tax - fee,
            currency = request.currency,
            taxCountry = taxCountry,
            taxCategory = if (taxUnresolved) null else taxCategory,
            taxRateBps = rate?.value,
            reverseCharge = !taxUnresolved && reverseCharge,
            evidence = evidence,
            status = PaymentStatus.POSTED,
            holdReasons = holdReasons,
            paymentTime = request.paymentTime,
        )

        val errors = holdReasons.map { reason ->
            heldError(
                request,
                reason,
                if (reason == HoldReason.UNKNOWN_MERCHANT) "merchant ${request.merchantId}" else "evidence=$evidence",
            )
        }.toMutableList()

        if ((vote as? TaxCountryVote.Result.Decided)?.agreeing == 1) {
            errors += LedgerError(
                ProcessingErrorCode.WEAK_TAX_COUNTRY_EVIDENCE,
                request.pspReference,
                "tax country selected from billing address only; evidence=$evidence",
            )
        }

        val now = clock.instant()
        if (request.paymentTime.isBefore(now.minus(MAX_DATE_DRIFT)) ||
            request.paymentTime.isAfter(now.plus(MAX_DATE_DRIFT))
        ) {
            log.warn("payment {} has suspicious paymentTime {} (received at {})", request.pspReference, request.paymentTime, now)
            errors += LedgerError(
                ProcessingErrorCode.INVALID_DATE,
                request.pspReference,
                "paymentTime=${request.paymentTime}, receivedAt=$now, allowedDrift=$MAX_DATE_DRIFT",
            )
        }
        if (invalidVatId) {
            log.warn("payment {} contains an invalid VAT ID for {}", request.pspReference, taxCountry)
            errors += LedgerError(
                ProcessingErrorCode.INVALID_VAT_ID,
                request.pspReference,
                "VAT ID format is not valid for country=$taxCountry at paymentTime=${request.paymentTime}",
            )
        }

        return write(
            payment,
            errors,
            rawPayload,
        )
    }

    private fun heldError(request: PaymentModel, reason: HoldReason, detail: String) = LedgerError(
        code = when (reason) {
            HoldReason.UNKNOWN_MERCHANT -> ProcessingErrorCode.UNKNOWN_MERCHANT
            HoldReason.TAX_UNRESOLVED -> ProcessingErrorCode.TAX_UNRESOLVED
        },
        reference = request.pspReference,
        detail = detail,
    )

    private suspend fun write(
        payment: PaymentEntity,
        errors: List<LedgerError>,
        rawPayload: String,
    ): LedgerResult =
        when (
            val write = payments.insert(
                payment,
                PaymentEntries.of(payment),
                rawPayload,
                errors.map {
                    ProcessingError(
                        id = UUID.randomUUID(),
                        eventType = EventType.CAPTURE,
                        externalReference = it.reference,
                        payload = rawPayload,
                        code = it.code,
                        detail = it.detail,
                    )
                },
            )
        ) {
            is LedgerWrite.Duplicate -> LedgerResult.Duplicate(write.paymentStatus)
            is LedgerWrite.Conflict -> LedgerResult.NotBookable(
                LedgerError(
                    ProcessingErrorCode.IDEMPOTENCY_CONFLICT,
                    payment.pspReference,
                    write.detail,
                )
            )
            is LedgerWrite.Inserted -> LedgerResult.Recorded(write.paymentStatus, errors.firstOrNull())
            is LedgerWrite.RecordedOverRefund -> error("a capture cannot answer $write")
        }

    private companion object {
        val MAX_DATE_DRIFT: Duration = Duration.ofDays(7)
    }
}
