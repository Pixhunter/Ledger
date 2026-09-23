package org.example.model

import org.example.model.enums.Currency
import org.example.model.enums.PaymentPurpose

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
