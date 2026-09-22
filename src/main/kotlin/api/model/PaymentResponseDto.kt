package org.example.api.model

import kotlinx.serialization.Serializable

@Serializable
data class PaymentResponseDto(
    val error: String? = null,
    val state: Short,
)
