package org.example.statemachine.payment

import org.slf4j.LoggerFactory
import org.example.db.Mapper
import org.example.model.PaymentModel
import org.example.scheduler.Permanent
import org.example.statemachine.general.models.ClaimedTask

/**
 * One transition per call. No loops, no retry logic, no sleeping: the runner
 * owns all of that. Keeping this function a pure "given this step, do one
 * thing, return the next step" makes every transition unit-testable without a
 * worker, a thread or a clock.
 */
class PaymentMachine {
    private val log = LoggerFactory.getLogger(PaymentMachine::class.java)

    suspend fun step(task: ClaimedTask): PaymentStates {
        val payment = Mapper.mapper.readValue(task.context, PaymentModel::class.java)

        return when (task.step) {
            PaymentStates.CREATE -> {
                log.info("[${task.requestId}] CREATE -> GET_MERCHANT")
                PaymentStates.GET_MERCHANT
            }

            PaymentStates.GET_MERCHANT -> {
                // TODO resolve merchant + fee terms from the merchant service
                log.info("[${task.requestId}] merchant=${payment.merchantId}")
                PaymentStates.PAY_TAXES
            }

            PaymentStates.PAY_TAXES -> {
                // TODO split gross into tax / fee / merchant net, record tax liability
                log.info("[${task.requestId}] amount=${payment.amount} ${payment.currency}")
                PaymentStates.REVENUE
            }

            PaymentStates.REVENUE -> {
                // TODO book the MoR fee and the merchant payable
                log.info("[${task.requestId}] REVENUE -> DONE")
                PaymentStates.DONE
            }

            // Terminal states are never claimed, so reaching here is a bug.
            PaymentStates.DONE,
            PaymentStates.FAILURE ->
                throw Permanent("step() called on terminal state ${task.step}")
        }
    }
}
