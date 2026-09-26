package org.example.service

import org.example.model.LedgerEntry
import org.example.model.enums.PaymentPurpose
import org.example.model.PaymentEntity
import org.example.model.Money

object PaymentEntries {

    fun of(payment: PaymentEntity): List<LedgerEntry> = buildList {
        val currency = payment.currency
        val held = payment.holdReasons.isNotEmpty()
        val merchantKey = payment.merchantId?.toString()

        add(LedgerEntry(PaymentPurpose.PSP, null, payment.gross, currency))

        if (!Money.isZero(payment.tax)) {
            add(LedgerEntry(PaymentPurpose.TAX, payment.taxCountry, -payment.tax, currency))
        }

        if (!Money.isZero(payment.fee)) {
            add(LedgerEntry(PaymentPurpose.REVENUE, null, -payment.fee, currency))
        }

        if (!Money.isZero(payment.merchantNet)) {
            add(
                LedgerEntry(
                    purpose = if (held) PaymentPurpose.HELD else PaymentPurpose.MERCHANT,
                    purposeKey = merchantKey,
                    amount = -payment.merchantNet,
                    currency = currency,
                )
            )
        }
    }
}
