package org.example.ledger

import org.example.model.EnumId

enum class LedgerTransactionType(override val id: Short) : EnumId {
    CAPTURE(1),
    REFUND(2),
    RELEASE(3),
    PAYOUT(4),
}
