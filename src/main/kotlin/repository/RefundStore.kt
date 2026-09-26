package org.example.repository

import org.example.model.LedgerEntry
import org.example.model.PaymentEntity
import org.example.model.RefundEntity
import org.example.model.LedgerWrite
import java.math.BigDecimal

interface RefundStore {

    suspend fun findPayment(pspReference: String): PaymentEntity?
    suspend fun insert(
        refund: RefundEntity,
        rawPayload: String,
        entries: (previousRefundTotal: BigDecimal) -> List<LedgerEntry>,
    ): LedgerWrite
}
