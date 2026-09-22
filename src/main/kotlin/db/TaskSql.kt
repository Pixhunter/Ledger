package org.example.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.jooq.tables.references.TASK_STATE
import org.example.statemachine.payment.PaymentStates
import org.jooq.DSLContext
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Shared between the task and job repositories. Both write task_state rows and
 * both map the smallint step back to an enum; duplicating either would be two
 * places to get wrong.
 */

/**
 * One row per (task, step). A retry of the same step updates that row's error
 * and attempt count rather than appending - the unique index on
 * (request_id, state) would reject a second row anyway.
 */
internal fun DSLContext.insertTaskState(
    requestId: String,
    step: PaymentStates,
    attempts: Int,
    error: String?,
) {
    insertInto(TASK_STATE)
        .set(TASK_STATE.REQUEST_ID, requestId)
        .set(TASK_STATE.STATE, step.id)
        .set(TASK_STATE.ATTEMPTS, attempts)
        .set(TASK_STATE.ERROR_MESSAGE, error)
        .onConflict(TASK_STATE.REQUEST_ID, TASK_STATE.STATE)
        .doUpdate()
        .set(TASK_STATE.ATTEMPTS, attempts)
        .set(TASK_STATE.ERROR_MESSAGE, error)
        .set(TASK_STATE.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
        .execute()
}

internal fun Short.toPaymentState(): PaymentStates? =
    PaymentStates.entries.firstOrNull { it.id == this }

internal fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

/**
 * jOOQ is blocking JDBC, so every repository call hops to Dispatchers.IO.
 * inline + crossinline so the IDE's "blocking call in non-blocking context"
 * inspection can still see which dispatcher the block runs on.
 */
internal suspend inline fun <R> io(crossinline block: () -> R): R =
    withContext(Dispatchers.IO) { block() }
