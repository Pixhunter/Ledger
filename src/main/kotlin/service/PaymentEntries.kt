package org.example.service

import org.example.ledger.AccountType
import org.example.ledger.LedgerEntry
import org.example.repository.model.PaymentEntity
import org.example.model.enums.PaymentStatus

/**
 * Turns a decided payment into its ledger lines.
 *
 * Kept pure and separate so the zero-sum property is unit-testable without a
 * database. Debits positive, credits negative: cash comes in, the other legs
 * are what the MoR now owes or has earned.
 */
object PaymentEntries {

    fun of(payment: PaymentEntity): List<LedgerEntry> = buildList {
        val currency = payment.currency

        // What actually arrived.
        add(LedgerEntry(AccountType.PSP, null, payment.gross, currency))

        if (payment.status == PaymentStatus.HELD && payment.taxCountry == null) {
            // Tax unresolved: nothing can be split, so the whole amount is
            // frozen against the merchant until a human decides.
            add(LedgerEntry(AccountType.HELD, payment.merchantId?.toString(), -payment.gross, currency))
            return@buildList
        }

        if (payment.tax != 0L) {
            add(LedgerEntry(AccountType.TAX, payment.taxCountry, -payment.tax, currency))
        }

        if (payment.fee != 0L) {
            add(LedgerEntry(AccountType.REVENUE, null, -payment.fee, currency))
        }

        if (payment.merchantNet != 0L) {
            // Unknown merchant: the split is correct, but the merchant's share
            // has nobody to belong to yet. account_key stays null.
            val heldForUnknownMerchant =
                payment.status == PaymentStatus.HELD && payment.merchantId == null

            add(
                LedgerEntry(
                    accountType = if (payment.status == PaymentStatus.HELD) AccountType.HELD else AccountType.MERCHANT,
                    accountKey = if (heldForUnknownMerchant) null else payment.merchantId?.toString(),
                    amount = -payment.merchantNet,
                    currency = currency,
                )
            )
        }
    }
}
