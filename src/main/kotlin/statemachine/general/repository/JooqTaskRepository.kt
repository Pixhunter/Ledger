package org.example.statemachine.general.repository

import org.example.db.Mapper
import org.example.db.insertTaskState
import org.example.db.io
import org.example.db.toPaymentState
import org.example.jooq.tables.references.TASK
import org.example.statemachine.TaskRepository
import org.example.statemachine.general.models.enum.MachineFlow
import org.example.statemachine.general.models.enum.Status
import org.example.statemachine.payment.PaymentStates
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory

class JooqTaskRepository(private val dsl: DSLContext) : TaskRepository {

    private val log = LoggerFactory.getLogger(JooqTaskRepository::class.java)

    /**
     * Writes the task and its first state row in ONE transaction: a task
     * without a state row would be invisible to the worker, and a state row
     * without a task violates the foreign key. Either both land or neither.
     */
    override suspend fun <T> insert(
        machineId: MachineFlow,
        requestId: String,
        context: T,
        state: PaymentStates,
    ): Boolean = io {
        log.info("creating task requestId=$requestId flow=${machineId.name}")
        val payload = Mapper.toJsonb(context)

        dsl.transactionResult { cfg ->
            val db = DSL.using(cfg)

            val taskInserted = db
                .insertInto(TASK)
                .set(TASK.REQUEST_ID, requestId)
                .set(TASK.MACHINE_ID, machineId.id)
                .set(TASK.STATE, Status.OPEN.id)
                .set(TASK.STEP, state.id)
                .set(TASK.ATTEMPTS, 0)
                .set(TASK.CONTEXT, payload)
                .onConflict(TASK.REQUEST_ID)
                .doNothing()
                .execute()

            // Replay of an existing request: leave its history untouched.
            if (taskInserted == 0) {
                return@transactionResult false
            }

            db.insertTaskState(requestId, state, 0, null)
            true
        }
    }

    override suspend fun findState(requestId: String): PaymentStates? = io {
        dsl.transactionResult { cfg ->
            DSL.using(cfg)
                .select(TASK.STEP)
                .from(TASK)
                .where(TASK.REQUEST_ID.eq(requestId))
                .fetchOne(TASK.STEP)
        }?.toPaymentState()
    }

    /**
     * Takes Class<T> rather than being reified: an interface method cannot be
     * inline, and the concrete type has to survive to the mapper somehow.
     */
    override suspend fun <T> findContext(requestId: String, type: Class<T>): T? = io {
        val json = dsl.transactionResult { cfg ->
            DSL.using(cfg)
                .select(TASK.CONTEXT)
                .from(TASK)
                .where(TASK.REQUEST_ID.eq(requestId))
                .fetchOne(TASK.CONTEXT)
        }
        json?.data()?.let { Mapper.mapper.readValue(it, type) }
    }
}
