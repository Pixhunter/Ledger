package org.example.model.enums

import org.example.model.EnumId

enum class EventType(override val id: Short) : EnumId {
    CAPTURE(1),
    REFUND(2),
    PAYOUT(3),
    TAX_REMITTANCE(4),
}
