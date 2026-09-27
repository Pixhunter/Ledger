package org.example.model.enums

import model.enums.EnumId

enum class PayoutStatus(override val id: Short) : EnumId {
    COMPUTED(1),
    SENT(2),
    PROCESSING(3),
}
