package org.example.model

import org.example.model.enums.Currency
import org.example.model.enums.PaymentPurpose
import java.math.BigDecimal

data class LedgerEntry(
    val purpose: PaymentPurpose,
    val purposeKey: String?,
    val amount: BigDecimal,
    val currency: Currency,
) {
    init {
        require(amount.signum() != 0) { "a zero entry carries no information" }
    }
}
