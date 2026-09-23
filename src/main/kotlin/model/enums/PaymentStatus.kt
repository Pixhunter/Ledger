package org.example.model.enums

import org.example.model.EnumId

enum class PaymentStatus(override val id: Short) : EnumId {
    POSTED(1),
    HELD(2),
    FAILED(3),
}