package org.example.ledger

import org.example.model.EnumId

/**
 * A ledger account is NOT a bank account. All money physically sits in the
 * PSP balance; the account is a label saying whose money it is.
 */
enum class AccountType(override val id: Short) : EnumId {
    /** Money that came in or went out via the PSP. */
    PSP(1),

    /** Owed to a country's tax authority. account_key = country. */
    TAX(2),

    /** The MoR's fee. */
    REVENUE(3),

    /** Owed to a merchant, paid out nightly. account_key = merchant id. */
    MERCHANT(4),

    /** Frozen, never paid out. account_key = merchant id, or null if unknown. */
    HELD(5),
}
