package org.example.model.enums

import model.enums.EnumId

enum class PaymentPurpose(override val id: Short) : EnumId {
    PSP(1),
    TAX(2),
    REVENUE(3),
    MERCHANT(4),
    HELD(5),
    SUSPENSE(6),
}