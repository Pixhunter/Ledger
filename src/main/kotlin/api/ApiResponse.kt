package org.example.api

import io.ktor.http.HttpStatusCode
import org.example.api.generated.model.PaymentResponseDto

/** Status and body decided together, so they cannot disagree. */
data class ApiResponse(
    val status: HttpStatusCode,
    val body: PaymentResponseDto,
)
