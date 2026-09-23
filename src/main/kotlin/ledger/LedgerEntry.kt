package org.example.ledger

import org.example.model.enums.Currency

data class LedgerEntry(
    val purpose: PaymentPurpose,
    val purposeKey: String?,
    val amount: Long,
    val currency: Currency,
) {
    init {
        require(amount != 0L) { "a zero entry carries no information" }
    }
}
