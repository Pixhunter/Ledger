package org.example.model.enums

import model.enums.EnumId

enum class ProcessingErrorCode(override val id: Short) : EnumId {
    IDEMPOTENCY_CONFLICT(1),
    OVER_REFUND(2),
    INVALID_DATE(3),
    UNKNOWN_MERCHANT(4),
    TAX_UNRESOLVED(5),
    NEGATIVE_BALANCE(6),
    NEGATIVE_TAX_BALANCE(7),
    INVALID_VAT_ID(8),
    WEAK_TAX_COUNTRY_EVIDENCE(9),
}
