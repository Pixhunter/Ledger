package org.example.service

import org.example.model.LedgerEntry
import org.example.model.PaymentEntity
import org.example.model.RefundEntity
import org.example.model.enums.PaymentPurpose
import java.math.BigDecimal
import java.math.RoundingMode
import org.example.model.Money
import org.example.Constants

// TODO release flow must clear hold_reason, or a refund of a released payment credits HELD.
object RefundEntries {

    fun of(
        refund: RefundEntity,
        payment: PaymentEntity,
        previousTotal: BigDecimal = BigDecimal.ZERO,
    ): List<LedgerEntry> = buildList {
        val currency = payment.currency
        val refundedTotal = previousTotal + refund.amount

        // Allocate from cumulative totals rather than rounding every refund in
        // isolation. Tiny refunds may receive no tax at first, but later ones
        // catch up and a full refund always reverses the complete original tax.
        val taxPart = cumulativeShare(refundedTotal, payment.tax, payment.gross, currency) -
            cumulativeShare(previousTotal, payment.tax, payment.gross, currency)
        val feePart = cumulativeShare(refundedTotal, payment.fee, payment.gross, currency) -
            cumulativeShare(previousTotal, payment.fee, payment.gross, currency)

        // Anything beyond what is left of the payment is not a sale being undone:
        // it reverses no tax and no fee, and waits in SUSPENSE for a human.
        val remaining = (payment.gross - previousTotal).max(Money.ZERO)
        val withinSale = refund.amount.min(remaining)
        val excess = refund.amount - withinSale

        val revenuePart = if (refund.feeReturned) feePart else Money.ZERO
        val merchantPart = withinSale - taxPart - revenuePart

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
                    purpose = if (payment.holdReasons.isNotEmpty()) PaymentPurpose.HELD else PaymentPurpose.MERCHANT,
                    purposeKey = payment.merchantId?.toString(),
                    amount = merchantPart,
                    currency = currency,
                )
            )
        }

        if (!Money.isZero(excess)) {
            add(
                LedgerEntry(
                    purpose = PaymentPurpose.SUSPENSE,
                    purposeKey = payment.merchantId?.toString(),
                    amount = excess,
                    currency = currency,
                )
            )
        }
    }

    private fun cumulativeShare(
        refundedTotal: BigDecimal,
        part: BigDecimal,
        gross: BigDecimal,
        currency: org.example.model.enums.Currency,
    ): BigDecimal {
        val cappedTotal = if (refundedTotal.compareTo(gross) > 0) gross else refundedTotal
        return Money.calculated(
            cappedTotal.multiply(part)
                .divide(gross, Constants.Amounts.DIVISION_SCALE, RoundingMode.HALF_EVEN),
            currency,
        )
    }
}
