package org.example.model.enums

import org.example.model.EnumId

enum class ProcessingErrorCode(override val id: Short) : EnumId {
    MALFORMED(1),
    IDEMPOTENCY_CONFLICT(2),
    OVER_REFUND(3),
    CURRENCY_MISMATCH(5),
    INVALID_DATE(6),
    UNKNOWN_MERCHANT(7),
    TAX_UNRESOLVED(8),
    NEGATIVE_BALANCE(9),
    NEGATIVE_TAX_BALANCE(10),
}
