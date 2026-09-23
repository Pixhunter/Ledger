package org.example.ledger

import org.example.repository.model.PaymentEntity

interface PaymentRepository {

    /**
     * Writes the payment, its ledger transaction and its entries in ONE
     * database transaction. Append-only: nothing is ever updated here.
     *
     * Idempotent on psp_reference. A PSP retry does not insert a second row
     * and does not touch the first one - it comes back as [PaymentWrite.Duplicate]
     * carrying the stored status, so the retry gets the same answer as the
     * original call.
     */
    suspend fun insert(
        payment: PaymentEntity,
        entries: List<LedgerEntry>,
    ): PaymentWrite
}
