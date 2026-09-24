package org.example

import java.time.Instant
import java.util.UUID

fun String.toUuid(field: String): UUID =
    runCatching { UUID.fromString(this) }
        .getOrElse { throw IllegalArgumentException("$field is not a valid uuid: '$this'") }

private val COUNTRY = Regex("^[A-Z]{2}$")

fun String.toCountry(field: String): String {
    val c = trim().uppercase()
    require(COUNTRY.matches(c)) { "$field must be ISO 3166-1 alpha-2, was '$this'" }
    return c
}

fun String.toInstant(field: String): Instant =
    runCatching { Instant.parse(this) }
        .getOrElse { throw IllegalArgumentException("$field is not an ISO-8601 instant: '$this'") }
