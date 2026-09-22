package org.example.statemachine.models.enum

import org.example.model.EnumId

enum class MachineFlow(override val id: Short) : EnumId {
    PAYMENT(1),
    REFUND(2),
}