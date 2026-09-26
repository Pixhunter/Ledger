package org.example.service

import org.example.model.LedgerWrite
import org.example.model.RefundEntity
import org.example.model.RefundModel
import org.example.model.enums.ProcessingErrorCode
import org.example.repository.RefundStore
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID

class RefundService(
    private val refunds: RefundStore,
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
            return LedgerResult.NotBookable(
                LedgerError(
                    ProcessingErrorCode.INVALID_DATE,
                    request.refundReference,
                    "refundedAt ${request.refundedAt}, payment at ${payment.paymentTime}",
                )
            )
        }

        val refund = RefundEntity(
            id = UUID.randomUUID(),
            refundReference = request.refundReference,
            paymentId = payment.id,
            amount = request.amount,
            currency = request.currency,
            reason = request.reason,
            feeReturned = FEE_RETURNED,
            refundedAt = request.refundedAt,
        )

        return when (
            val write = refunds.insert(refund) { previousRefundAmounts ->
                RefundEntries.of(refund, payment, previousRefundAmounts)
            }
        ) {
            is LedgerWrite.Inserted -> LedgerResult.Recorded(write.paymentStatus)
            is LedgerWrite.Duplicate -> LedgerResult.Duplicate(write.paymentStatus)

            is LedgerWrite.Conflict -> LedgerResult.NotBookable(
                LedgerError(
                    ProcessingErrorCode.IDEMPOTENCY_CONFLICT,
                    request.refundReference,
                    write.detail,
                )
            )

            is LedgerWrite.RecordedOverRefund -> LedgerResult.Recorded(
                write.paymentStatus,
                LedgerError(
                    ProcessingErrorCode.OVER_REFUND,
                    request.refundReference,
                    "refunds total ${write.refundedSoFar} against gross ${write.gross}, " +
                        "excess booked to SUSPENSE",
                ),
            )
        }
    }

    private companion object {
        // The MoR keeps its fee on every refund; the merchant covers it. Frozen on the
        // row, so varying it by reason later never rewrites history.
        const val FEE_RETURNED = false

        val CLOCK_SKEW: Duration = Duration.ofDays(60)
    }
}
