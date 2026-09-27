package org.example.model.enums

import model.enums.EnumId

enum class TaxCategory(override val id: Short) : EnumId {
    STANDARD(1),
    REDUCED(2),
}