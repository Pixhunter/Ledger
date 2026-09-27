package repository.store

import org.example.model.LedgerEntry
import org.example.model.LedgerWrite
import org.example.model.PaymentEntity
import org.example.model.ProcessingError

interface PaymentStore {
    suspend fun insert(
        payment: PaymentEntity,
        entries: List<LedgerEntry>,
        rawPayload: String = "{}",
        errors: List<ProcessingError> = emptyList(),
    ): LedgerWrite
}