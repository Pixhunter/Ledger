package org.example.api

import io.ktor.http.HttpStatusCode

/** Status and body decided together, so they cannot disagree. */
data class ApiResponse<T : Any>(
    val status: HttpStatusCode,
    val body: T,
)
