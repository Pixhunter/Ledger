package org.example.ledger

import org.example.model.enums.Currency

/**
 * One line = one change in whose money it is.
 *
 * Append-only: never updated, never deleted. A mistake is corrected with a
 * new transaction, so history always explains the balance - and busy accounts
 * (TAX:DE, REVENUE) never lock, because nothing ever does UPDATE balance.
 */
data class LedgerEntry(
    val accountType: AccountType,
    /** TAX: country. MERCHANT / HELD: merchant id. PSP / REVENUE: null. */
    val accountKey: String?,
    /** Signed: + debit, - credit. Minor units. Sums to zero per transaction. */
    val amount: Long,
    val currency: Currency,
) {
    init {
        require(amount != 0L) { "a zero entry carries no information" }
    }
}
