package org.example.service

import org.example.model.LedgerEntry
import org.example.model.PaymentEntity
import org.example.model.RefundEntity
import org.example.model.enums.PaymentPurpose
import java.math.BigDecimal
import java.math.RoundingMode

// TODO release flow must clear hold_reason, or a refund of a released payment credits HELD.
object RefundEntries {

    fun of(
        refund: RefundEntity,
        payment: PaymentEntity,
        previousRefundAmounts: List<Long> = emptyList(),
    ): List<LedgerEntry> = buildList {
        val currency = payment.currency
        val isFinalRefund = previousRefundAmounts.sum() + refund.amount == payment.gross
        val taxPart = if (isFinalRefund) {
            payment.tax - previousRefundAmounts.sumOf { share(it, payment.tax, payment.gross) }
        } else {
            share(refund.amount, payment.tax, payment.gross)
        }
        val feePart = if (isFinalRefund) {
            payment.fee - previousRefundAmounts.sumOf { share(it, payment.fee, payment.gross) }
        } else {
            share(refund.amount, payment.fee, payment.gross)
        }

        val revenuePart = if (refund.feeReturned) feePart else 0
        val merchantPart = refund.amount - taxPart - revenuePart

        add(LedgerEntry(PaymentPurpose.PSP, null, -refund.amount, currency))

        if (taxPart != 0L) {
            add(LedgerEntry(PaymentPurpose.TAX, payment.taxCountry, taxPart, currency))
        }

        if (revenuePart != 0L) {
            add(LedgerEntry(PaymentPurpose.REVENUE, null, revenuePart, currency))
        }

        if (merchantPart != 0L) {
            add(
                LedgerEntry(
                    purpose = if (payment.holdReason != null) PaymentPurpose.HELD else PaymentPurpose.MERCHANT,
                    purposeKey = payment.merchantId?.toString(),
                    amount = merchantPart,
                    currency = currency,
                )
            )
        }
    }

    private fun share(amount: Long, part: Long, gross: Long): Long =
        BigDecimal(amount).multiply(BigDecimal(part))
            .divide(BigDecimal(gross), 0, RoundingMode.HALF_EVEN)
            .toLong()
}
