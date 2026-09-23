package org.example.api.model

import kotlinx.serialization.Serializable

@Serializable
data class PaymentResponseDto(
    val status: String,
    val reason: String? = null,
) {
    companion object {
        fun success() = PaymentResponseDto(ResponseStatusDto.SUCCESS.name)

        fun failed(reason: FailureReasonDto) = PaymentResponseDto(ResponseStatusDto.FAILED.name, reason.name)
    }
}
