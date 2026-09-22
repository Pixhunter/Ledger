package org.example.statemachine

import org.example.statemachine.general.models.enum.MachineFlow
import org.example.statemachine.payment.PaymentStates

/**
 * Creating and reading tasks - what the API side needs.
 *
 * Deliberately has no claim/advance/fail: moving a task through its states is
 * the worker's concern and lives in JobRepository. Splitting them keeps the
 * request path unable to mutate a task the worker is holding a lease on, and
 * makes each interface small enough to fake in a test.
 */
interface TaskRepository {

    /**
     * Inserts the task and its first state row.
     * Returns false when requestId already exists - the caller replayed the
     * same event, and the existing row must not be touched.
     */
    suspend fun <T> insert(
        machineId: MachineFlow,
        requestId: String,
        context: T,
        state: PaymentStates,
    ): Boolean

    /** Current step of a task, or null if unknown. */
    suspend fun findState(requestId: String): PaymentStates?

    /** Stored context, deserialised back into T. */
    suspend fun <T> findContext(requestId: String, type: Class<T>): T?
}
