package org.example.service

import org.example.model.LedgerEntry
import org.example.model.PaymentEntity
import org.example.model.RefundEntity
import org.example.model.enums.PaymentPurpose
import java.math.BigDecimal
import java.math.RoundingMode
import org.example.model.Money

// TODO release flow must clear hold_reason, or a refund of a released payment credits HELD.
object RefundEntries {

    fun of(
        refund: RefundEntity,
        payment: PaymentEntity,
        previousRefundAmounts: List<BigDecimal> = emptyList(),
    ): List<LedgerEntry> = buildList {
        val currency = payment.currency
        val previousTotal = previousRefundAmounts.fold(BigDecimal.ZERO, BigDecimal::add)
        val isFinalRefund = (previousTotal + refund.amount).compareTo(payment.gross) == 0
        val taxPart = if (isFinalRefund) {
            payment.tax - previousRefundAmounts.sumOf { share(it, payment.tax, payment.gross, currency) }
        } else {
            share(refund.amount, payment.tax, payment.gross, currency)
        }
        val feePart = if (isFinalRefund) {
            payment.fee - previousRefundAmounts.sumOf { share(it, payment.fee, payment.gross, currency) }
        } else {
            share(refund.amount, payment.fee, payment.gross, currency)
        }

        val revenuePart = if (refund.feeReturned) feePart else Money.ZERO
        val merchantPart = refund.amount - taxPart - revenuePart

        add(LedgerEntry(PaymentPurpose.PSP, null, -refund.amount, currency))

        if (!Money.isZero(taxPart)) {
            add(LedgerEntry(PaymentPurpose.TAX, payment.taxCountry, taxPart, currency))
        }

        if (!Money.isZero(revenuePart)) {
            add(LedgerEntry(PaymentPurpose.REVENUE, null, revenuePart, currency))
        }

        if (!Money.isZero(merchantPart)) {
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

    private fun share(
        amount: BigDecimal,
        part: BigDecimal,
        gross: BigDecimal,
        currency: org.example.model.enums.Currency,
    ): BigDecimal =
        Money.calculated(
            amount.multiply(part)
                .divide(gross, Money.STORAGE_SCALE + 8, RoundingMode.HALF_EVEN),
            currency,
        )
}
