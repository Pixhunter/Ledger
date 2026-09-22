package org.example.scheduler

import org.slf4j.LoggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.example.statemachine.Permanent
import org.example.statemachine.Retryable
import org.example.statemachine.models.ClaimedTask
import org.example.statemachine.payment.PaymentMachine
import org.example.statemachine.payment.PaymentStates
import org.example.uuid
import java.time.Instant
import kotlin.random.Random
import kotlin.time.toJavaDuration

/**
 * Polls the task table and drives each claimed task one step at a time until
 * it reaches DONE or FAILURE.
 *
 * Polling rather than an in-memory queue: a task queued in memory dies with
 * the process. The database is the queue, so a restart loses nothing and a
 * second instance picks up work without any coordination between them.
 */
class JobRunner(
    private val jobs: JobRepository,
    private val payments: PaymentMachine,
    private val workerId: String = "worker-${uuid().take(8)}",
) {
    private val log = LoggerFactory.getLogger(JobRunner::class.java)

    fun start(scope: CoroutineScope): Job = scope.launch {
        log.info("job runner $workerId started")
        while (isActive) {
            val worked = runCatching { pollOnce() }
                .onFailure { log.error("poll failed", it) }
                .getOrDefault(false)

            // Only sleep when there was nothing to do: a full batch means more
            // is probably waiting, so go straight round again.
            if (!worked) {
                delay(SchedulerConfig.POLL_INTERVAL + SchedulerConfig.POLL_JITTER * Random.nextDouble())
            }
        }
        log.info("job runner $workerId stopped")
    }

    /** @return true if anything was claimed. */
    private suspend fun pollOnce(): Boolean {
        val leaseUntil = Instant.now().plus(SchedulerConfig.LEASE.toJavaDuration())
        val claimed = jobs.claim(workerId, SchedulerConfig.BATCH_SIZE, leaseUntil)

        // Sequential on purpose: correctness first. Parallelising within a
        // batch is a later change and needs no extra locking, since each task
        // is already leased to this worker.
        claimed.forEach { execute(it) }
        return claimed.isNotEmpty()
    }

    private suspend fun execute(task: ClaimedTask) {
        try {
            val next = payments.step(task)

            when (next) {
                PaymentStates.DONE -> {
                    jobs.advance(task.requestId, task.step, next)
                    jobs.complete(task.requestId, next)
                    log.info("[${task.requestId}] done")
                }
                PaymentStates.FAILURE -> {
                    jobs.fail(task.requestId, task.step, "machine returned FAILURE")
                }
                else -> {
                    // Committed one step. The task is now due again immediately
                    // and will be picked up on the next poll - possibly by a
                    // different worker, which is fine: state lives in the row,
                    // not in this process.
                    jobs.advance(task.requestId, task.step, next)
                }
            }
        } catch (e: Permanent) {
            log.warn("[${task.requestId}] permanent failure at ${task.step}", e)
            jobs.fail(task.requestId, task.step, e.message ?: "permanent")
        } catch (e: Retryable) {
            retryOrFail(task, e)
        } catch (e: Exception) {
            // Unknown failures are treated as retryable: a transient bug or a
            // blip should not kill a payment permanently. MAX_ATTEMPTS still
            // bounds it.
            retryOrFail(task, e)
        }
    }

    private suspend fun retryOrFail(task: ClaimedTask, e: Exception) {
        val attempts = task.attempts + 1
        val message = e.message ?: e::class.simpleName.orEmpty()

        if (attempts >= SchedulerConfig.MAX_ATTEMPTS) {
            log.warn("[${task.requestId}] giving up at ${task.step} after $attempts attempts", e)
            jobs.fail(task.requestId, task.step, "after $attempts attempts: $message")
        } else {
            val nextRunAt = Instant.now().plus(SchedulerConfig.backoff(attempts).toJavaDuration())
            log.info("[${task.requestId}] retry ${task.step} attempt $attempts at $nextRunAt")
            jobs.retry(task.requestId, task.step, attempts, message, nextRunAt)
        }
    }
}
