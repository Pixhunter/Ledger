package org.example.service

import org.example.model.RejectReason

sealed interface PaymentResult {

    data object Posted : PaymentResult

    data object Held : PaymentResult

    data object Duplicate : PaymentResult

    data class Rejected(val reason: RejectReason) : PaymentResult
}
