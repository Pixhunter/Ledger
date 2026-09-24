package org.example.api

import io.ktor.http.HttpStatusCode
import org.example.api.generated.model.ErrorReasonDto
import org.example.api.generated.model.LedgerResponseDto
import org.example.api.generated.model.ResponseStatusDto

data class ApiResponse<T : Any>(
    val status: HttpStatusCode,
    val body: T,
)

fun recorded() = LedgerResponseDto(ResponseStatusDto.SUCCESS)
fun rejected(reason: ErrorReasonDto) = LedgerResponseDto(ResponseStatusDto.FAILED, reason)
