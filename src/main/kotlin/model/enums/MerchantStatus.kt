package org.example.model.enums

import org.example.model.EnumId

/** SUSPENDED stops payouts; captures are still recorded. */
enum class MerchantStatus(override val id: Short) : EnumId {
    ACTIVE(1),
    SUSPENDED(2),
}