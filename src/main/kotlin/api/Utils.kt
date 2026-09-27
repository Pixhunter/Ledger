package org.example.api

import kotlinx.coroutines.withContext
import org.example.utils.Constants
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.slf4j.MDCContext
import org.example.database.Database

fun String.toUuid(field: String): UUID =
    runCatching { UUID.fromString(this) }
        .getOrElse { throw IllegalArgumentException("$field is not a valid uuid: '$this'") }

fun String.toCountry(field: String): String {
    val c = trim().uppercase()
    require(Constants.Api.COUNTRY_CODE.matches(c)) { "$field must be ISO 3166-1 alpha-2, was '$this'" }
    return c
}

fun String.toInstant(field: String): Instant =
    runCatching { Instant.parse(this).truncatedTo(Constants.Dates.STORED_PRECISION) }
        .getOrElse { throw IllegalArgumentException("$field is not an ISO-8601 instant: '$this'") }

fun String.toLocalDate(field: String): LocalDate =
    runCatching { LocalDate.parse(this) }
        .getOrElse { throw IllegalArgumentException("$field is not an ISO-8601 date: '$this'") }

fun String.toIntIn(field: String, range: IntRange): Int {
    val value = toIntOrNull()
        ?: throw IllegalArgumentException("$field is not a number: '$this'")
    require(value in range) { "$field must be ${range.first}..${range.last}, was $value" }
    return value
}

fun randomUuid(): UUID = UUID.randomUUID()

/**
 * Runs blocking JDBC work off Ktor's request threads, on a dispatcher capped at
 * the connection-pool size, so waiting for a connection queues in the scheduler
 * instead of blocking a thread. See [Database.jdbc].
 */
internal suspend inline fun <R> io(crossinline block: () -> R): R =
    withContext(Database.jdbc + MDCContext()) { block() }