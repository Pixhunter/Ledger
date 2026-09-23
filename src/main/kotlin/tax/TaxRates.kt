package org.example.tax

/**
 * Standard rate per tax country.
 *
 * Illustrative only. A real MoR uses a rates service (Avalara, Stripe Tax,
 * Vertex): rates change, differ by product category, and in the US vary by
 * city and district. Returning null rather than throwing keeps "we do not
 * support this country" an ordinary decision the caller answers the PSP with,
 * not an exception.
 */
class TaxRates(
    private val rates: Map<String, BasisPoints> = DEFAULTS,
) {
    fun lookup(country: String): BasisPoints? = rates[country.uppercase()]

    fun supported(): Set<String> = rates.keys

    companion object {
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
