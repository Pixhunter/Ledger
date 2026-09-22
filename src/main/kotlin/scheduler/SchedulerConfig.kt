package org.example.scheduler

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Hardcoded on purpose: small values so the behaviour is visible while
 * testing. Externalise to config later.
 */
object SchedulerConfig {

    /** How long to sleep when nothing was claimed. */
    val POLL_INTERVAL: Duration = 1.seconds

    /** Random extra sleep, so parallel workers do not wake in lockstep. */
    val POLL_JITTER: Duration = 500.milliseconds

    /** Tasks claimed per poll. */
    const val BATCH_SIZE = 10

    /**
     * Lease length. Must comfortably exceed the slowest step: if it expires
     * while a healthy worker is still running, another worker claims the same
     * task and it gets processed twice.
     */
    val LEASE: Duration = 30.seconds

    /** Attempts for one step before the whole task is failed. */
    const val MAX_ATTEMPTS = 5

    /** Linear backoff, deliberately short for testing. */
    fun backoff(attempt: Int): Duration = attempt.coerceAtLeast(1).seconds
}
