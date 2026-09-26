package org.example.repository

import org.example.model.LedgerEntry
import org.example.model.PaymentEntity
import org.example.model.LedgerWrite
import org.example.model.ProcessingError

/**
 * The port PaymentService writes through.
 *
 * Deciding the split and storing it are separate concerns, and only the
 * second one needs Postgres. With the service depending on this interface,
 * every routing rule - HELD, POSTED, replay - is a unit test that runs in
 * milliseconds, and the database test is left to prove only persistence.
 */
interface PaymentStore {
    suspend fun insert(
        payment: PaymentEntity,
        entries: List<LedgerEntry>,
        rawPayload: String = "{}",
        errors: List<ProcessingError> = emptyList(),
    ): LedgerWrite
}
