package org.example.model

import org.example.model.enums.Currency
import org.example.model.enums.HoldReason
import org.example.model.enums.PaymentStatus
import org.example.model.enums.TaxCategory
import java.time.Instant
import java.util.UUID

/**
 * A payment as it will be stored: the business fact, fully decided.
 *
 * Everything except status is frozen at capture - amounts, rate, category,
 * country. A rate change next year, or a merchant moving country, must never
 * rewrite what was actually charged.
 */
data class PaymentEntity(
    val id: UUID,
    val pspReference: String,
    val merchantId: UUID?,
    val gross: Long,
    val tax: Long,
    val fee: Long,
    val merchantNet: Long,
    val currency: Currency,
    val taxCountry: String?,
    val taxCategory: TaxCategory?,
    val taxRateBps: Int?,
    val reverseCharge: Boolean,
    /** Country codes only - GDPR data minimisation, no raw IP, no full address. */
    val evidence: Map<String, String?>,
    val status: PaymentStatus,
    val holdReason: HoldReason?,
    /** From the PSP. This is the tax point, and it decides the filing period. */
    val paymentTime: Instant,
) {
    init {
        require(gross > 0) { "gross must be positive, was $gross" }
        require(tax >= 0 && fee >= 0 && merchantNet >= 0) {
            "no part of a payment may be negative: tax=$tax fee=$fee merchantNet=$merchantNet"
        }
        require(gross == tax + fee + merchantNet) {
            "split does not reconcile: $gross != $tax + $fee + $merchantNet"
        }
        require((status == PaymentStatus.HELD) == (holdReason != null)) {
            "HELD and holdReason are the same fact"
        }
    }
}