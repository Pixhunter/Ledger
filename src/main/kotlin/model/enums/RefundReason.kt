package org.example.model.enums

import org.example.model.EnumId

enum class RefundReason(override val id: Short) : EnumId {
    DUPLICATE(1),
    FRAUD(2),
    CUSTOMER_REQUEST(3),
    PRODUCT_ISSUE(4),
    OTHER(5),
}
