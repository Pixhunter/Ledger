package org.example.service

import org.example.model.RejectReason
import org.example.model.enums.PaymentStatus
import org.example.model.enums.ProcessingErrorCode

sealed interface LedgerResult {
    data class Recorded(
        val paymentStatus: PaymentStatus,
        val error: LedgerError? = null,
    ) : LedgerResult
    data class Duplicate(val paymentStatus: PaymentStatus) : LedgerResult
    data object NothingToRecord : LedgerResult
    data object PaymentNotFound : LedgerResult
    data class Rejected(val reason: RejectReason) : LedgerResult

    data class NotBookable(val error: LedgerError) : LedgerResult
}
