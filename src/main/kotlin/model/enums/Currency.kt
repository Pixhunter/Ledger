package org.example.model.enums

import org.example.model.IdKey

enum class Currency(id: Int): IdKey {
    EUR(1),
    USD(2),
    GBP(3),
    AUD(4)
    ;
}