package model

import org.example.model.enums.PaymentStatus

sealed interface LedgerResult {
    data class Recorded(
        val paymentStatus: PaymentStatus,
        val error: LedgerError? = null,
    ) : LedgerResult
    data class Duplicate(val paymentStatus: PaymentStatus) : LedgerResult
    data object NothingToRecord : LedgerResult
    data object PaymentNotFound : LedgerResult

    data class NotBookable(val error: LedgerError) : LedgerResult
}