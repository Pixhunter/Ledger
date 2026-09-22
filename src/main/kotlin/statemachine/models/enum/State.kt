package org.example.statemachine.models.enum

import org.example.model.EnumId

enum class State(override val id: Short) : EnumId {
    CREATED(0),
    DONE(20),
    FAILED(30)
    ;

    fun isTerminal(): Boolean {
        return this == FAILED || this == DONE
    }
}