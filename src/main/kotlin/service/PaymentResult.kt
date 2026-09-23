package org.example.service

import org.example.api.model.FailureReason
import org.example.api.model.PaymentResponseDto
import org.example.tax.Split

/**
 * What happened to a capture. The HTTP layer maps this to a status code; the
 * domain knows nothing about HTTP.
 */
sealed interface PaymentResult {

    /** Recorded and attributed. */
    data class Posted(val split: Split) : PaymentResult

    /** Recorded but frozen. Split is null when tax could not be resolved. */
    data class Held(val split: Split?) : PaymentResult

    /** Already stored under this pspReference. Nothing was written. */
    data object Duplicate : PaymentResult

    /** Nothing written - the request itself was broken. */
    data class Rejected(val reason: FailureReason) : PaymentResult

    fun toResponse(): PaymentResponseDto = when (this) {
        is Rejected -> PaymentResponseDto.failed(reason)
        else -> PaymentResponseDto.success()
    }
}
