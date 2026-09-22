package org.example.statemachine.payment

import org.example.model.EnumId

enum class PaymentStates(override val id: Short) : EnumId {
    CREATE(1),
    GET_MERCHANT(2),
    PAY_TAXES(3),
    REVENUE(4),
    DONE(5),
    FAILURE(6),
    ;
}