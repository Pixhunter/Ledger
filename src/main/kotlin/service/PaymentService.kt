package org.example.service

import org.example.model.LedgerWrite
import org.example.model.PaymentEntity
import org.example.model.Money
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
import org.slf4j.LoggerFactory
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
// TODO tax category from merchant.tax_category, and the rate whose valid_from <= paymentTime
//      (README, "Tax rate"). Both are fixed here today.
class PaymentService(
    private val payments: PaymentStore,
    private val rates: TaxRates,
    private val merchants: MerchantRegistry,
    private val feeRate: BasisPoints,
    private val morCountry: String,
    private val taxCategory: TaxCategory = TaxCategory.STANDARD,
) {
    private val log = LoggerFactory.getLogger(PaymentService::class.java)

    suspend fun createPayment(request: PaymentModel): LedgerResult {
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

        val reverseCharge = taxCountry != null && request.isBusiness && taxCountry != morCountry

        val rate = when {
            taxCountry == null -> null
            reverseCharge -> BasisPoints.ZERO
            else -> rates.lookup(taxCountry)
        }

        if (taxCountry == null || rate == null) {
            return hold(request, evidence, HoldReason.TAX_UNRESOLVED, taxCountry)
        }

        val gross = request.amount
        val tax = TaxCalculator.tax(gross, rate, request.currency, reverseCharge)
        val fee = TaxCalculator.fee(gross - tax, feeRate, request.currency)

        val known = merchants.exists(request.merchantId)

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
            taxCategory = taxCategory,
            taxRateBps = rate.value,
            reverseCharge = reverseCharge,
            evidence = evidence,
            status = if (known) PaymentStatus.POSTED else PaymentStatus.HELD,
            holdReason = if (known) null else HoldReason.UNKNOWN_MERCHANT,
            paymentTime = request.paymentTime,
        )

        return write(
            payment,
            if (known) null else heldError(request, HoldReason.UNKNOWN_MERCHANT, "merchant ${request.merchantId}"),
        )
    }

    /** Recorded but frozen: money we hold and cannot yet attribute. */
    private suspend fun hold(
        request: PaymentModel,
        evidence: Map<String, String?>,
        reason: HoldReason,
        taxCountry: String?,
    ): LedgerResult {
        val payment = PaymentEntity(
            id = UUID.randomUUID(),
            pspReference = request.pspReference,
            merchantId = request.merchantId,
            gross = request.amount,
            tax = Money.ZERO,
            fee = Money.ZERO,
            merchantNet = request.amount,     // nothing is split until resolved
            currency = request.currency,
            taxCountry = taxCountry,
            taxCategory = null,
            taxRateBps = null,
            reverseCharge = false,
            evidence = evidence,
            status = PaymentStatus.HELD,
            holdReason = reason,
            paymentTime = request.paymentTime,
        )

        return write(payment, heldError(request, reason, "evidence=$evidence"))
    }

    private fun heldError(request: PaymentModel, reason: HoldReason, detail: String) = LedgerError(
        code = when (reason) {
            HoldReason.UNKNOWN_MERCHANT -> ProcessingErrorCode.UNKNOWN_MERCHANT
            HoldReason.TAX_UNRESOLVED -> ProcessingErrorCode.TAX_UNRESOLVED
        },
        reference = request.pspReference,
        detail = detail,
    )

    private suspend fun write(payment: PaymentEntity, error: LedgerError?): LedgerResult =
        when (val write = payments.insert(payment, PaymentEntries.of(payment))) {
            is LedgerWrite.Duplicate -> LedgerResult.Duplicate(write.paymentStatus)
            is LedgerWrite.Conflict -> LedgerResult.NotBookable(
                LedgerError(
                    ProcessingErrorCode.IDEMPOTENCY_CONFLICT,
                    payment.pspReference,
                    write.detail,
                )
            )
            is LedgerWrite.Inserted -> LedgerResult.Recorded(write.paymentStatus, error)
            is LedgerWrite.RecordedOverRefund -> error("a capture cannot answer $write")
        }
}
