package org.example.model

/**
 * Where a sale is taxed. Captured per payment, never looked up later.
 *
 * This is deliberately NOT a property of the customer. A customer can move,
 * and a sale must keep the jurisdiction it had when it happened - if past
 * liabilities changed because someone edited an address, the filing history
 * would stop matching what was actually declared. So it is an immutable
 * snapshot stored with the transaction, exactly like the tax rate that applied.
 *
 * `country` is a plain ISO code rather than the Country enum on purpose: tax
 * jurisdictions are open-ended, and an enum is closed. Adding a market should
 * not need a code change and a migration.
 *
 * One shape covers every regime because everything past `country` is optional:
 *   EU VAT      country + taxId (reverse charge) + evidence
 *   US sales    country + subdivision + postalCode (rates vary by district)
 *   AU GST      country alone
 */
data class TaxLocation(
    /** ISO 3166-1 alpha-2, uppercase: "DE", "US", "AU". */
    val country: String,

    /** ISO 3166-2 subdivision without the country prefix: "CA", "NY", "SP". */
    val subdivision: String? = null,

    /** US sales tax can vary by ZIP, not only by state. */
    val postalCode: String? = null,

    /** VAT number / ABN. Its presence is what triggers B2B reverse charge. */
    val taxId: String? = null,

    val customerType: CustomerType = CustomerType.INDIVIDUAL,

    /**
     * What proved the country. EU rules require two non-contradictory pieces,
     * retained for audit - a tax authority may ask years later to prove a sale
     * really was German.
     */
    val evidence: List<TaxEvidence> = emptyList(),
) {
    /** The key a tax account is named by: "DE", "US-CA", "AU". */
    val jurisdiction: String
        get() = subdivision?.let { "$country-$it" } ?: country

    /**
     * B2B cross-border inside the EU: the seller charges nothing and the buyer
     * self-accounts. Domestic B2B is still taxed normally, so the merchant's
     * own country has to be compared before applying this.
     */
    val isBusiness: Boolean
        get() = customerType == CustomerType.BUSINESS && !taxId.isNullOrBlank()

    companion object {
        private val COUNTRY = Regex("^[A-Z]{2}$")
        private val SUBDIVISION = Regex("^[A-Z0-9]{1,3}$")

        fun of(
            country: String,
            subdivision: String? = null,
            postalCode: String? = null,
            taxId: String? = null,
            customerType: CustomerType = CustomerType.INDIVIDUAL,
            evidence: List<TaxEvidence> = emptyList(),
        ): TaxLocation {
            val c = country.trim().uppercase()
            require(COUNTRY.matches(c)) { "country must be ISO 3166-1 alpha-2, was '$country'" }

            val s = subdivision?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            require(s == null || SUBDIVISION.matches(s)) {
                "subdivision must be an ISO 3166-2 code without the country prefix, was '$subdivision'"
            }

            return TaxLocation(
                country = c,
                subdivision = s,
                postalCode = postalCode?.trim()?.takeIf { it.isNotEmpty() },
                taxId = taxId?.trim()?.takeIf { it.isNotEmpty() },
                customerType = customerType,
                evidence = evidence,
            )
        }
    }
}

enum class CustomerType { INDIVIDUAL, BUSINESS }

data class TaxEvidence(
    val type: TaxEvidenceType,
    val value: String,
)

enum class TaxEvidenceType {
    BILLING_ADDRESS,
    IP_COUNTRY,
    CARD_BIN,
    PHONE_CODE,
}
