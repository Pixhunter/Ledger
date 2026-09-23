package org.example.tax

import org.example.model.enums.Currency

/**
 * The result of splitting one captured payment. All amounts are minor units
 * of the same currency, and by construction:
 *
 *     gross == tax + fee + merchant
 *
 * The check is in the constructor on purpose: an unbalanced split cannot be
 * built, so it can never reach the ledger.
 */
data class Split(
    val gross: Long,
    val tax: Long,
    val fee: Long,
    val merchant: Long,
    val currency: Currency,
    val jurisdiction: String,
    val taxRate: BasisPoints,
    val reverseCharge: Boolean,
) {
    init {
        check(gross == tax + fee + merchant) {
            "split does not reconcile: $gross != $tax + $fee + $merchant"
        }
    }
}
