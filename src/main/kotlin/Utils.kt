package org.example

import org.example.model.enums.Currency
import java.time.Instant
import java.util.UUID

/**
 * Wire-value parsing shared by the mappers. Each throws
 * IllegalArgumentException, which StatusPages turns into 400 INVALID_REQUEST.
 */

fun String.toUuid(field: String): UUID =
    runCatching { UUID.fromString(this) }
        .getOrElse { throw IllegalArgumentException("$field is not a valid uuid: '$this'") }

fun String.toCurrency(): Currency =
    runCatching { Currency.valueOf(uppercase()) }
        .getOrElse {
            throw IllegalArgumentException(
                "unsupported currency '$this', expected one of ${Currency.entries.joinToString()}"
            )
        }

private val COUNTRY = Regex("^[A-Z]{2}$")

fun String.toCountry(field: String): String {
    val c = trim().uppercase()
    require(COUNTRY.matches(c)) { "$field must be ISO 3166-1 alpha-2, was '$this'" }
    return c
}

fun String.toInstant(field: String): Instant =
    runCatching { Instant.parse(this) }
        .getOrElse { throw IllegalArgumentException("$field is not an ISO-8601 instant: '$this'") }
