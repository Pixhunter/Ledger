package org.example.scheduler.repository

import org.example.db.insertTaskState
import org.example.db.io
import org.example.db.now
import org.example.db.toPaymentState
import org.example.jooq.tables.references.TASK
import org.example.scheduler.JobRepository
import org.example.statemachine.models.ClaimedTask
import org.example.statemachine.models.enum.MachineFlow
import org.example.statemachine.models.enum.Status
import org.example.statemachine.payment.PaymentStates
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset

class JooqJobRepository(private val dsl: DSLContext) : JobRepository {

    private val log = LoggerFactory.getLogger(JooqJobRepository::class.java)

    /**
     * Claim = mark the lease and return the rows, in one statement.
     *
     * The inner SELECT ... FOR UPDATE SKIP LOCKED is what makes multiple
     * workers safe: rows another transaction already holds are skipped rather
     * than waited on, so N workers polling the same table never collide and
     * never block each other. Doing it as select-then-update would open a
     * window where two workers both see the same free row.
     */
    override suspend fun claim(workerId: String, limit: Int, leaseUntil: Instant): List<ClaimedTask> = io {
        val now = now()

        dsl.transactionResult { cfg ->
            val db = DSL.using(cfg)

            val due = DSL.select(TASK.REQUEST_ID)
                .from(TASK)
                .where(TASK.STATE.eq(Status.OPEN.id))
                .and(TASK.NEXT_RUN_AT.le(now))
                .and(TASK.LOCKED_UNTIL.isNull.or(TASK.LOCKED_UNTIL.lt(now)))
                .orderBy(TASK.NEXT_RUN_AT.asc())
                .limit(limit)
                .forUpdate()
                .skipLocked()

            db.update(TASK)
                .set(TASK.LOCKED_BY, workerId)
                .set(TASK.LOCKED_UNTIL, leaseUntil.atOffset(ZoneOffset.UTC))
                .set(TASK.UPDATED_AT, now)
                .where(TASK.REQUEST_ID.`in`(due))
                .returningResult(TASK.REQUEST_ID, TASK.MACHINE_ID, TASK.STEP, TASK.ATTEMPTS, TASK.CONTEXT)
                .fetch()
                .map { r ->
                    ClaimedTask(
                        requestId = r.value1()!!,
                        machine = MachineFlow.entries.first { it.id == r.value2() },
                        step = r.value3()!!.toPaymentState()!!,
                        attempts = r.value4() ?: 0,
                        context = r.value5()!!.data(),
                    )
                }
        }
    }

    /**
     * Success: move to the next step, clear the lease, reset attempts (they
     * count per step, not per task) and make the row due immediately so the
     * next poll picks it straight up.
     */
    override suspend fun advance(requestId: String, from: PaymentStates, to: PaymentStates) = io {
        log.info("advance $requestId ${from.name} -> ${to.name}")
        val now = now()

        dsl.transaction { cfg ->
            val db = DSL.using(cfg)

            db.update(TASK)
                .set(TASK.STEP, to.id)
                .set(TASK.ATTEMPTS, 0)
                .set(TASK.NEXT_RUN_AT, now)
                .setNull(TASK.LOCKED_UNTIL)
                .setNull(TASK.LOCKED_BY)
                .set(TASK.UPDATED_AT, now)
                .where(TASK.REQUEST_ID.eq(requestId))
                .and(TASK.STEP.eq(from.id))   // guard: someone else may have moved it
                .execute()

            db.insertTaskState(requestId, to, 0, null)
        }
    }

    /** Retryable failure: record it, count the attempt, come back later. */
    override suspend fun retry(
        requestId: String,
        step: PaymentStates,
        attempts: Int,
        error: String,
        nextRunAt: Instant,
    ) = io {
        log.info("retry $requestId at ${step.name}, attempt $attempts")
        val now = now()

        dsl.transaction { cfg ->
            val db = DSL.using(cfg)

            db.update(TASK)
                .set(TASK.ATTEMPTS, attempts)
                .set(TASK.NEXT_RUN_AT, nextRunAt.atOffset(ZoneOffset.UTC))
                .setNull(TASK.LOCKED_UNTIL)   // release early; no point holding it while waiting
                .setNull(TASK.LOCKED_BY)
                .set(TASK.UPDATED_AT, now)
                .where(TASK.REQUEST_ID.eq(requestId))
                .execute()

            db.insertTaskState(requestId, step, attempts, error)
        }
    }

    /** Terminal failure: close the task, keep the error for inspection. */
    override suspend fun fail(requestId: String, step: PaymentStates, error: String) = io {
        log.warn("fail $requestId at ${step.name}: $error")
        val now = now()

        dsl.transaction { cfg ->
            val db = DSL.using(cfg)

            db.update(TASK)
                .set(TASK.STATE, Status.CLOSE.id)
                .set(TASK.STEP, PaymentStates.FAILURE.id)
                .set(TASK.NEXT_RUN_AT, now)
                .setNull(TASK.LOCKED_UNTIL)
                .setNull(TASK.LOCKED_BY)
                .set(TASK.UPDATED_AT, now)
                .where(TASK.REQUEST_ID.eq(requestId))
                .execute()

            db.insertTaskState(requestId, PaymentStates.FAILURE, 0, error)
        }
    }

    /** Terminal success: close the task so it is never claimed again. */
    override suspend fun complete(requestId: String, step: PaymentStates) = io {
        log.info("complete $requestId at ${step.name}")
        val now = now()

        dsl.transaction { cfg ->
            DSL.using(cfg)
                .update(TASK)
                .set(TASK.STATE, Status.CLOSE.id)
                .set(TASK.STEP, step.id)
                .setNull(TASK.LOCKED_UNTIL)
                .setNull(TASK.LOCKED_BY)
                .set(TASK.UPDATED_AT, now)
                .where(TASK.REQUEST_ID.eq(requestId))
                .execute()
        }
    }
}
