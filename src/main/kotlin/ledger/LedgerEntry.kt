package org.example.ledger

import org.example.model.enums.Currency
import java.time.Instant

enum class EntryKind { CAPTURE, REFUND, REMITTANCE, PAYOUT }

/**
 * One posting. Never updated, never deleted - a correction is another entry,
 * so the history always explains the balance.
 */
data class LedgerEntry(
    val transactionId: String,
    val requestId: String,
    val kind: EntryKind,
    val account: String,
    val amount: Long,            // signed: debit positive, credit negative
    val currency: Currency,
    val jurisdiction: String? = null,
    val occurredAt: Instant,
)

data class AccountBalance(
    val account: String,
    val currency: Currency,
    val balance: Long,
)

data class TaxLiability(
    val jurisdiction: String,
    val currency: Currency,
    val owed: Long,
)

data class MerchantBalance(
    val merchantId: String,
    val currency: Currency,
    val owed: Long,
)
