package org.example.service

import org.example.model.RejectReason
import org.example.model.PaymentWrite
import org.example.model.PaymentEntity
import org.example.model.enums.HoldReason
import org.example.model.PaymentModel
import org.example.model.enums.PaymentStatus
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
 * Rejections are therefore limited to requests that are malformed, where
 * nothing was owed to anyone in the first place.
 *
 *   1. shape        -> 400 INVALID_REQUEST       (controller)
 *   2. tax country  -> HELD (TAX_UNRESOLVED)
 *   3. tax rate     -> HELD (TAX_UNRESOLVED)
 *   4. split maths  -> 400 INVALID_REQUEST       (nothing to record)
 *   5. merchant     -> HELD (UNKNOWN_MERCHANT)
 *   6. one write
 */
class PaymentService(
    private val payments: PaymentStore,
    private val rates: TaxRates,
    private val merchants: MerchantRegistry,
    private val feeRate: BasisPoints,
    private val morCountry: String,
    private val taxCategory: TaxCategory = TaxCategory.STANDARD,
) {
    private val log = LoggerFactory.getLogger(PaymentService::class.java)

    suspend fun createPayment(request: PaymentModel): PaymentResult {
        val evidence = mapOf(
            "billing" to request.billingCountry,
            "card" to request.cardIssuingCountry,
            "ip" to request.ipCountry,
            "vatId" to request.customerVatId,
        )

        // 2. Which country taxes this sale. Two of three signals must agree;
        //    otherwise billing wins and the conflict is logged.
        val vote = TaxCountryVote.decide(
            billingCountry = request.billingCountry,
            cardIssuingCountry = request.cardIssuingCountry,
            ipCountry = request.ipCountry,
        )

        val taxCountry = (vote as? TaxCountryVote.Result.Decided)?.country

        // 3. A country with no rate is the same situation as no country: we
        //    must not guess, because a guessed rate becomes a wrong tax return.
        val reverseCharge = taxCountry != null && request.isBusiness && taxCountry != morCountry

        val rate = when {
            taxCountry == null -> null
            reverseCharge -> BasisPoints.ZERO
            else -> rates.lookup(taxCountry)
        }

        if (taxCountry == null || rate == null) {
            log.warn("HELD TAX_UNRESOLVED for {}: evidence={}", request.pspReference, evidence)
            return hold(request, evidence, HoldReason.TAX_UNRESOLVED, taxCountry)
        }

        // 4. Last guard before the write. Nothing here can fail through the
        //    API - the mapper already rejects a non-positive amount, and the
        //    fee is taken from the net, so the merchant share cannot go
        //    negative. It stays because the service is called directly too.
        val gross = request.amount
        val tax = runCatching { TaxCalculator.tax(gross, rate, reverseCharge) }
            .getOrElse { e ->
                log.warn("split failed for {}: {}", request.pspReference, e.message)
                return PaymentResult.Rejected(RejectReason.INVALID_REQUEST)
            }
        val fee = TaxCalculator.fee(gross - tax, feeRate)

        // 5. Unknown merchant: the split is correct, nobody to owe it to yet.
        val known = merchants.exists(request.merchantId)
        if (!known) {
            log.error("HELD UNKNOWN_MERCHANT {} on {}", request.merchantId, request.pspReference)
        }

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

        return write(payment)
    }

    /** Recorded but frozen: money we hold and cannot yet attribute. */
    private suspend fun hold(
        request: PaymentModel,
        evidence: Map<String, String?>,
        reason: HoldReason,
        taxCountry: String?,
    ): PaymentResult {
        val payment = PaymentEntity(
            id = UUID.randomUUID(),
            pspReference = request.pspReference,
            merchantId = request.merchantId,
            gross = request.amount,
            tax = 0,
            fee = 0,
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

        return write(payment)
    }

    private suspend fun write(payment: PaymentEntity): PaymentResult =
        when (payments.insert(payment, PaymentEntries.of(payment))) {
            is PaymentWrite.Duplicate -> PaymentResult.Duplicate
            PaymentWrite.Inserted -> when (payment.status) {
                PaymentStatus.HELD -> PaymentResult.Held
                else -> PaymentResult.Posted
            }
        }
}
