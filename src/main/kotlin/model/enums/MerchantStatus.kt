package org.example.model.enums

import model.enums.EnumId

/** SUSPENDED stops payouts; captures are still recorded. */
enum class MerchantStatus(override val id: Short) : EnumId {
    ACTIVE(1),
    SUSPENDED(2),
}