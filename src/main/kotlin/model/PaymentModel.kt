package org.example.model

import org.example.model.enums.Currency
import java.util.UUID

/**
 * Internal payment model.
 */
data class PaymentModel(
    val requestId: UUID,
    val merchantId: UUID,
    val customerId: UUID,
    val amount: Long,          // minor units
    val currency: Currency,
)