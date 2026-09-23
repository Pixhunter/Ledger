package org.example.ledger

import org.example.model.enums.HoldReason
import org.example.model.enums.PaymentStatus

/** Outcome of trying to store a payment. */
sealed interface PaymentWrite {

    /** New payment, ledger transaction and entries were written. */
    data object Inserted : PaymentWrite

    /**
     * This psp_reference was already stored. Nothing was written; the answer
     * is whatever we decided the first time.
     */
    data class Duplicate(
        val status: PaymentStatus,
        val holdReason: HoldReason?,
    ) : PaymentWrite
}
