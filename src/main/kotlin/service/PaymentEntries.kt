package org.example.service

import org.example.model.LedgerEntry
import org.example.model.enums.PaymentPurpose
import org.example.model.enums.PaymentStatus
import org.example.model.PaymentEntity

object PaymentEntries {

    fun of(payment: PaymentEntity): List<LedgerEntry> = buildList {
        val currency = payment.currency
        val held = payment.status == PaymentStatus.HELD
        val merchantKey = payment.merchantId?.toString()

        add(LedgerEntry(PaymentPurpose.PSP, null, payment.gross, currency))

        if (payment.tax != 0L) {
            add(LedgerEntry(PaymentPurpose.TAX, payment.taxCountry, -payment.tax, currency))
        }

        if (payment.fee != 0L) {
            add(LedgerEntry(PaymentPurpose.REVENUE, null, -payment.fee, currency))
        }

        if (payment.merchantNet != 0L) {
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
