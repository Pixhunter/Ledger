package org.example.statemachine.general.models

import org.example.statemachine.general.models.enum.MachineFlow
import org.example.statemachine.payment.PaymentStates

/**
 * A task this worker holds a lease on.
 */
data class ClaimedTask(
    val requestId: String,
    val machine: MachineFlow,
    val step: PaymentStates,
    val attempts: Int,
    val context: String,
)
