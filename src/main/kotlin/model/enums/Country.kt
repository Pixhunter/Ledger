package org.example.model.enums

import org.example.model.EnumId

enum class Country(override val id: Short) : EnumId {
    FRANCE(1),
    GERMANY(2),
    AUSTRALIA(3),
    USA(4),
    UK(5),
    GREECE(6),
    SPAIN(7),
    BRAZIL(8)
    ;
}