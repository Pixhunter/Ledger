package org.example.model

import org.example.model.enums.Currency
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class PaymentModel(
    val pspReference: String,
    val merchantId: UUID,
    val amount: BigDecimal,           // major units, tax-inclusive
    val currency: Currency,
    val success: Boolean,
    val billingCountry: String?,
    val stateOrProvince: String?,
    val cardIssuingCountry: String,
    val ipCountry: String?,
    val customerVatId: String?,
    val paymentTime: Instant,
) {
    /** Presence of a VAT id means a business buyer: candidate for reverse charge. */
    val isBusiness: Boolean get() = !customerVatId.isNullOrBlank()
}
