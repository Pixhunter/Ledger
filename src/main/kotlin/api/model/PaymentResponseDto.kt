package org.example.api.model

import kotlinx.serialization.Serializable

@Serializable
data class PaymentResponseDto(
    val status: String,
    val reason: String? = null,
) {
    companion object {
        fun success() = PaymentResponseDto(ResponseStatus.SUCCESS.name)

        fun failed(reason: FailureReason) = PaymentResponseDto(ResponseStatus.FAILED.name, reason.name)
    }
}
