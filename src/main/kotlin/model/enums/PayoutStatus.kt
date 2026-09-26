package org.example.model.enums

import org.example.model.EnumId

/** Only COMPUTED is implemented; sending money to a bank is a separate integration. */
enum class PayoutStatus(override val id: Short) : EnumId {
    COMPUTED(1),
    SENT(2),
    CONFIRMED(3),
    PROCESSING(4),
}
