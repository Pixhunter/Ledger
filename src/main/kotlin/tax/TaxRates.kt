package org.example.tax

// Stub by choice: the task allows the rate on the capture webhook. Production reads
// mor.tax_rate - keyed by (country, category, valid_from), so a rate change never
// reprices past payments - which is a repository swap, not a redesign.
class TaxRates(
    private val rates: Map<String, BasisPoints> = EU_STANDARD,
) {
    fun lookup(country: String): BasisPoints? = rates[country.uppercase()]

    fun supported(): Set<String> = rates.keys

    companion object {
        val EU_STANDARD: Map<String, BasisPoints> = mapOf(
            "AT" to BasisPoints(2000),
            "BE" to BasisPoints(2100),
            "BG" to BasisPoints(2000),
            "CY" to BasisPoints(1900),
            "CZ" to BasisPoints(2100),
            "DE" to BasisPoints(1900),
            "DK" to BasisPoints(2500),
            "EE" to BasisPoints(2400),
            "ES" to BasisPoints(2100),
            "FI" to BasisPoints(2550),
            "FR" to BasisPoints(2000),
            "GR" to BasisPoints(2400),
            "HR" to BasisPoints(2500),
            "HU" to BasisPoints(2700),
            "IE" to BasisPoints(2300),
            "IT" to BasisPoints(2200),
            "LT" to BasisPoints(2100),
            "LU" to BasisPoints(1700),
            "LV" to BasisPoints(2100),
            "MT" to BasisPoints(1800),
            "NL" to BasisPoints(2100),
            "PL" to BasisPoints(2300),
            "PT" to BasisPoints(2300),
            "RO" to BasisPoints(2100),
            "SE" to BasisPoints(2500),
            "SI" to BasisPoints(2200),
            "SK" to BasisPoints(2300),
        )
    }
}
