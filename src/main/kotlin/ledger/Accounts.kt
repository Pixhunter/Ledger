package org.example.ledger

import java.util.UUID

/**
 * Account naming. Strings rather than an enum because merchants and
 * jurisdictions are open-ended, and the prefix makes reports groupable
 * without a join.
 */
object Accounts {

    /** Cash the MoR physically holds. An asset: debit to increase. */
    const val MOR_CASH = "mor:cash"

    /** The MoR's own fee income. */
    const val MOR_REVENUE = "mor:revenue"

    /** Received but not yet attributable - unknown jurisdiction, pending review. */
    const val SUSPENSE = "suspense:unallocated"

    /** Owed to a tax authority: "tax:DE:payable", "tax:US-CA:payable". */
    fun tax(jurisdiction: String) = "tax:$jurisdiction:payable"

    /** Owed to a merchant, not yet paid out. */
    fun merchantPayable(merchantId: UUID) = "merchant:$merchantId:payable"

    /** Payout sent, not yet confirmed landed. Still owed until it settles. */
    fun merchantInTransit(merchantId: UUID) = "merchant:$merchantId:in_transit"
}
