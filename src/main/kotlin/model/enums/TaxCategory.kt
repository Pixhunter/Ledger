package org.example.model.enums

import org.example.model.EnumId

enum class TaxCategory(override val id: Short) : EnumId {
    STANDARD(1),
    REDUCED(2),
}