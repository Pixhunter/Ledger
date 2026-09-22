package org.example.statemachine

import org.example.api.model.PaymentResponseDto
import org.example.model.PaymentModel
import org.example.statemachine.models.enum.MachineFlow
import org.example.statemachine.payment.PaymentStates
import org.slf4j.LoggerFactory

class StateMachineService(
    private val tasks: TaskRepository
) {
    private val log = LoggerFactory.getLogger(StateMachineService::class.java)

    suspend fun createPaymentStateMachine(payment: PaymentModel): PaymentResponseDto {
        val requestId = payment.requestId.toString()
        log.info("creating payment state machine for requestId=$requestId")

        val created = tasks.insert(MachineFlow.PAYMENT, requestId, payment, PaymentStates.CREATE)

        if (!created) {
            val existing = tasks.findState(requestId) ?: PaymentStates.CREATE
            log.info("duplicate request $requestId, already at ${existing.name}")
            return PaymentResponseDto(state = existing.id)
        }

        log.info("task created $requestId")
        return PaymentResponseDto(state = PaymentStates.CREATE.id)
    }
}
