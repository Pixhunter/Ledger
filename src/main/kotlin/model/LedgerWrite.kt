package org.example.model

import org.example.model.enums.PaymentStatus
import java.math.BigDecimal

sealed interface LedgerWrite {

    data class Inserted(val paymentStatus: PaymentStatus) : LedgerWrite

    data class Duplicate(val paymentStatus: PaymentStatus) : LedgerWrite

    data class Conflict(val detail: String) : LedgerWrite

    data class RecordedOverRefund(
        val paymentStatus: PaymentStatus,
        val refundedSoFar: BigDecimal,
        val gross: BigDecimal,
    ) : LedgerWrite
}
