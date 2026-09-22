package org.example.tax

import org.example.model.TaxLocation

enum class TaxMode {
    /** `amount` already contains the tax. tax = gross - gross / (1 + rate) */
    INCLUSIVE,

    /** `amount` is the net price, tax is added on top. tax = net * rate */
    EXCLUSIVE,
}

/**
 * Basis points, not percent: integer percent cannot express 8.875% US sales
 * tax or 19.5% VAT. 2000 = 20.00%.
 */
@JvmInline
value class BasisPoints(val value: Int) {
    init {
        require(value in 0..10_000) { "basis points must be 0..10000, was $value" }
    }

    companion object {
        val ZERO = BasisPoints(0)
    }
}

/**
 * Resolves the rate for a sale.
 *
 * The brief says the rate may be communicated on the capture event, and that
 * is authoritative when present - a PSP knows the rate it actually charged,
 * and a sale must be taxed at the rate applied at the time, not at whatever
 * this table says today.
 *
 * The fallback table is illustrative only. A real MoR uses a rates service
 * (Avalara, Stripe Tax, Vertex) because rates change, differ by product
 * category, and in the US vary by city and district.
 */
class TaxRates(
    private val fallback: Map<String, BasisPoints> = DEFAULTS,
) {
    fun resolve(location: TaxLocation, fromEvent: BasisPoints?): BasisPoints {
        // B2B cross-border in the EU: the seller charges nothing and the buyer
        // self-accounts ("reverse charge"). Simplified here - a full
        // implementation compares the merchant's country of establishment,
        // since domestic B2B is still taxed normally.
        if (location.isBusiness) return BasisPoints.ZERO

        fromEvent?.let { return it }

        return fallback[location.jurisdiction]
            ?: fallback[location.country]
            ?: throw UnknownJurisdiction(location.jurisdiction)
    }

    companion object {
        /** Standard rates, approximate, for local runs only. Do not ship. */
        val DEFAULTS: Map<String, BasisPoints> = mapOf(
            "DE" to BasisPoints(1900),
            "FR" to BasisPoints(2000),
            "GB" to BasisPoints(2000),
            "ES" to BasisPoints(2100),
            "GR" to BasisPoints(2400),
            "AU" to BasisPoints(1000),
        )
    }
}

/**
 * Raised when a sale cannot be attributed to a jurisdiction. The caller books
 * it to suspense rather than guessing - a wrong jurisdiction means a wrong
 * tax return.
 */
class UnknownJurisdiction(val jurisdiction: String) :
    Exception("no tax rate for jurisdiction '$jurisdiction'")
