package org.example.service

import org.example.api.generated.model.ErrorReasonDto
import org.example.api.generated.model.PaymentResponseDto
import org.example.api.paymentRecorded
import org.example.api.paymentRejected
import org.example.tax.Split

sealed interface PaymentResult {

    data class Posted(val split: Split) : PaymentResult

    data class Held(val split: Split?) : PaymentResult

    data object Duplicate : PaymentResult

    data class Rejected(val reason: ErrorReasonDto) : PaymentResult

    fun toResponse(): PaymentResponseDto = when (this) {
        is Rejected -> paymentRejected(reason)
        else -> paymentRecorded()
    }
}
