package org.example.statemachine.models.enum

import org.example.model.EnumId

enum class Status(override val id: Short) : EnumId {
    OPEN(1),
    CLOSE(2),
}