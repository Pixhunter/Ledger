package org.example.ledger

interface LedgerRepository {

    /**
     * Appends all entries of one transaction atomically.
     *
     * Returns false when this requestId was already posted - the unique index
     * on (request_id, account) makes a double post impossible at the database
     * level, not merely unlikely.
     *
     * @throws IllegalArgumentException if the entries do not sum to zero.
     */
    suspend fun append(entries: List<LedgerEntry>): Boolean

    /** What the MoR owes each tax authority, per currency. */
    suspend fun taxLiabilities(): List<TaxLiability>

    /** What the MoR owes each merchant, per currency. */
    suspend fun merchantBalances(): List<MerchantBalance>

    /** Balance of a single account, per currency. */
    suspend fun balance(account: String): List<AccountBalance>
}
