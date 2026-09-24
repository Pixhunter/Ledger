package org.example.service

import org.example.model.LedgerWrite
import org.example.model.RefundEntity
import org.example.model.RefundModel
import org.example.model.RejectReason
import org.example.repository.RefundStore
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID

class RefundService(
    private val refunds: RefundStore,
    private val feePolicy: RefundFeePolicy,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(RefundService::class.java)

    suspend fun createRefund(request: RefundModel): LedgerResult {
        if (!request.success) {
            log.info("refund {} failed at the PSP, nothing stored", request.refundReference)
            return LedgerResult.NothingToRecord
        }

        val payment = refunds.findPayment(request.pspReference)
            ?: return LedgerResult.PaymentNotFound

        if (request.refundedAt.isBefore(payment.paymentTime) ||
            request.refundedAt.isAfter(clock.instant().plus(CLOCK_SKEW))
        ) {
            log.warn(
                "refund {} dated {}, payment at {}",
                request.refundReference, request.refundedAt, payment.paymentTime,
            )
            return LedgerResult.Rejected(RejectReason.INVALID_REQUEST)
        }

        val refund = RefundEntity(
            id = UUID.randomUUID(),
            refundReference = request.refundReference,
            paymentId = payment.id,
            amount = request.amount,
            currency = request.currency,
            reason = request.reason,
            feeReturned = feePolicy.feeReturned(request.reason),
            refundedAt = request.refundedAt,
        )

        return when (
            val write = refunds.insert(refund) { previousRefundAmounts ->
                RefundEntries.of(refund, payment, previousRefundAmounts)
            }
        ) {
            is LedgerWrite.Inserted -> LedgerResult.Recorded(write.paymentStatus)
            is LedgerWrite.Duplicate -> LedgerResult.Duplicate(write.paymentStatus)

            // TODO quarantine: write the raw event to rejected_event (README, "Rejected events").
            is LedgerWrite.Conflict -> {
                log.error(
                    "REFUND CONFLICT refund={} payment={} amount={}: {}",
                    request.refundReference, request.pspReference, request.amount, write.detail,
                )
                LedgerResult.Duplicate(payment.status)
            }

            // TODO quarantine: write the raw event to rejected_event (README, "Rejected events").
            is LedgerWrite.RecordedOverRefund -> {
                log.error(
                    "OVER-REFUND refund={} payment={}: refunds total {} against gross {}",
                    request.refundReference, request.pspReference, write.refundedSoFar, write.gross,
                )
                LedgerResult.Recorded(write.paymentStatus)
            }
        }
    }

    private companion object {
        val CLOCK_SKEW: Duration = Duration.ofMinutes(5)
    }
}
