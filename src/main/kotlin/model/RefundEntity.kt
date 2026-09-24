package org.example.model

import org.example.model.enums.Currency
import org.example.model.enums.RefundReason
import java.time.Instant
import java.util.UUID

data class RefundEntity(
    val id: UUID,
    val refundReference: String,
    val paymentId: UUID,
    val amount: Long,
    val currency: Currency,
    val reason: RefundReason,
    val feeReturned: Boolean,
    val refundedAt: Instant,
) {
    init {
        require(amount > 0) { "refund amount must be positive, was $amount" }
    }
}
