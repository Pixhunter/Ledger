package org.example.scheduler

import org.example.statemachine.models.ClaimedTask
import org.example.statemachine.payment.PaymentStates
import java.time.Instant

/**
 * Moving tasks through their lifecycle - what the worker needs.
 *
 * Every method here assumes the caller holds a lease on the task, which only
 * claim() hands out. Nothing on the request path can reach these.
 */
interface JobRepository {

    /**
     * Takes a lease on up to [limit] tasks that are open, due, and not already
     * leased by someone else. Uses FOR UPDATE SKIP LOCKED, so any number of
     * workers can poll the same table without ever being handed the same row.
     */
    suspend fun claim(workerId: String, limit: Int, leaseUntil: Instant): List<ClaimedTask>

    /** Step succeeded: move to [to], reset attempts, make it due immediately. */
    suspend fun advance(requestId: String, from: PaymentStates, to: PaymentStates)

    /** Step failed but is worth retrying: count the attempt, schedule it later. */
    suspend fun retry(requestId: String, step: PaymentStates, attempts: Int, error: String, nextRunAt: Instant)

    /** Give up: close the task in FAILURE. */
    suspend fun fail(requestId: String, step: PaymentStates, error: String)

    /** Reached a terminal success state: close the task. */
    suspend fun complete(requestId: String, step: PaymentStates)
}
