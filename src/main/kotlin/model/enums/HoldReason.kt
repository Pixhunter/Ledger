package org.example.model.enums

import model.enums.EnumId

enum class HoldReason(override val id: Short) : EnumId {
    /** Merchant is not in our system. We keep the money, a human resolves it. */
    UNKNOWN_MERCHANT(1),

    /** Country vote failed, or the country has no rate in the table. */
    TAX_UNRESOLVED(2),
}