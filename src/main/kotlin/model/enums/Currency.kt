package org.example.model.enums

import org.example.model.EnumId

enum class Currency(override val id: Short) : EnumId {
    EUR(1),
    USD(2),
    GBP(3),
    AUD(4)
    ;
}