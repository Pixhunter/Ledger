package org.example.model

import org.example.model.enums.Currency
import org.example.model.enums.HoldReason
import org.example.model.enums.PaymentStatus
import org.example.model.enums.TaxCategory
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class PaymentEntity(
    val id: UUID,
    val pspReference: String,
    val merchantId: UUID?,
    val gross: BigDecimal,
    val tax: BigDecimal,
    val fee: BigDecimal,
    val merchantNet: BigDecimal,
    val currency: Currency,
    val taxCountry: String?,
    val taxCategory: TaxCategory?,
    val taxRateBps: Int?,
    val reverseCharge: Boolean,
    /** Country codes only - GDPR data minimisation, no raw IP, no full address. */
    val evidence: Map<String, String?>,
    val status: PaymentStatus,
    val holdReasons: Set<HoldReason>,
    /** From the PSP. This is the tax point, and it decides the filing period. */
    val paymentTime: Instant,
) {
    init {
        require(gross.signum() > 0) { "gross must be positive, was $gross" }
        require(tax.signum() >= 0 && fee.signum() >= 0 && merchantNet.signum() >= 0) {
            "no part of a payment may be negative: tax=$tax fee=$fee merchantNet=$merchantNet"
        }
        require(gross.compareTo(tax + fee + merchantNet) == 0) {
            "split does not reconcile: $gross != $tax + $fee + $merchantNet"
        }
    }
}
