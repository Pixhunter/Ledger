package org.example.service

import org.example.model.LedgerWrite
import org.example.model.RefundEntity
import org.example.model.RefundModel
import org.example.model.enums.ProcessingErrorCode
import java.time.Clock
import org.example.utils.Constants
import org.example.api.randomUuid
import org.example.repository.RefundRepository
import org.example.utils.logger

class RefundService(
    private val refunds: RefundRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = logger<RefundService>()

    suspend fun createRefund(request: RefundModel, rawPayload: String = "{}"): LedgerResult {
        if (!request.success) {
            log.info("outcome=psp_failed nothing stored")
            return LedgerResult.NothingToRecord
        }

        val payment = refunds.findPayment(request.pspReference)
            ?: return LedgerResult.PaymentNotFound


        if (request.refundedAt.isBefore(payment.paymentTime) ||
            request.refundedAt.isAfter(clock.instant().plus(Constants.Dates.REFUND_MAX_FUTURE_DRIFT))
        ) {
            return LedgerResult.NotBookable(
                LedgerError(
                    ProcessingErrorCode.INVALID_DATE,
                    request.refundReference,
                    "refundedAt ${request.refundedAt}, payment at ${payment.paymentTime}, " +
                        "now ${clock.instant()}, allowedFutureDrift=${Constants.Dates.REFUND_MAX_FUTURE_DRIFT}",
                )
            )
        }

        val refund = RefundEntity(
            id = randomUuid(),
            refundReference = request.refundReference,
            paymentId = payment.id,
            amount = request.amount,
            currency = request.currency,
            reason = request.reason,
            feeReturned = Constants.Fees.FEE_RETURNED_ON_REFUND,
            refundedAt = request.refundedAt,
        )

        return when (
            val write = refunds.insert(refund, rawPayload) { previousRefundAmounts ->
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

}
