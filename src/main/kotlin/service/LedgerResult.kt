package org.example.service

import org.example.model.RejectReason
import org.example.model.enums.PaymentStatus

sealed interface LedgerResult {
    data class Recorded(val paymentStatus: PaymentStatus) : LedgerResult
    data class Duplicate(val paymentStatus: PaymentStatus) : LedgerResult
    data object NothingToRecord : LedgerResult
    data object PaymentNotFound : LedgerResult
    data class Rejected(val reason: RejectReason) : LedgerResult
}
