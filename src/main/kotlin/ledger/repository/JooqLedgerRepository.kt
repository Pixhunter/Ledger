package org.example.ledger.repository

import org.example.db.io
import org.example.ledger.AccountBalance
import org.example.ledger.LedgerEntry
import org.example.ledger.LedgerRepository
import org.example.ledger.MerchantBalance
import org.example.ledger.TaxLiability
import org.example.model.enums.Currency
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.slf4j.LoggerFactory
import java.time.ZoneOffset

/**
 * Plain-SQL against ledger.entry rather than generated classes: the ledger
 * schema arrives in V3, and regenerating jOOQ requires a live database with
 * V3 already applied. Run ./scripts/jooq-generate.sh afterwards and these can
 * become typed like the task repository.
 */
class JooqLedgerRepository(private val dsl: DSLContext) : LedgerRepository {

    private val log = LoggerFactory.getLogger(JooqLedgerRepository::class.java)

    override suspend fun append(entries: List<LedgerEntry>): Boolean = io {
        require(entries.isNotEmpty()) { "no entries to append" }

        // The invariant, asserted before it reaches the database. Checked per
        // currency, because summing across currencies is meaningless.
        entries.groupBy { it.currency }.forEach { (currency, group) ->
            val sum = group.sumOf { it.amount }
            require(sum == 0L) { "entries for $currency do not sum to zero: $sum" }
        }

        dsl.transactionResult { cfg ->
            val db = DSL.using(cfg)

            var inserted = 0
            entries.forEach { e ->
                inserted += db.query(
                    """
                    INSERT INTO ledger.entry
                        (transaction_id, request_id, kind, account,
                         amount, currency, jurisdiction, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (request_id, account) DO NOTHING
                    """.trimIndent(),
                    e.transactionId,
                    e.requestId,
                    e.kind.name,
                    e.account,
                    e.amount,
                    e.currency.name,
                    e.jurisdiction,
                    e.occurredAt.atOffset(ZoneOffset.UTC),
                ).execute()
            }

            if (inserted == 0) {
                log.info("replay of ${entries.first().requestId}, nothing posted")
                false
            } else {
                // A partial insert would mean the transaction no longer
                // balances - refuse it rather than commit a broken ledger.
                check(inserted == entries.size) {
                    "partial post for ${entries.first().requestId}: $inserted of ${entries.size}"
                }
                true
            }
        }
    }

    override suspend fun taxLiabilities(): List<TaxLiability> = io {
        dsl.fetch(
            """
            SELECT jurisdiction, currency, -SUM(amount) AS owed
            FROM ledger.entry
            WHERE account LIKE 'tax:%'
            GROUP BY jurisdiction, currency
            ORDER BY jurisdiction, currency
            """.trimIndent()
        ).map { r ->
            TaxLiability(
                jurisdiction = r.get("jurisdiction", String::class.java),
                currency = Currency.valueOf(r.get("currency", String::class.java)),
                owed = r.get("owed", Long::class.java),
            )
        }
    }

    override suspend fun merchantBalances(): List<MerchantBalance> = io {
        dsl.fetch(
            """
            SELECT split_part(account, ':', 2) AS merchant_id,
                   currency,
                   -SUM(amount) AS owed
            FROM ledger.entry
            WHERE account LIKE 'merchant:%'
            GROUP BY split_part(account, ':', 2), currency
            ORDER BY 1, 2
            """.trimIndent()
        ).map { r ->
            MerchantBalance(
                merchantId = r.get("merchant_id", String::class.java),
                currency = Currency.valueOf(r.get("currency", String::class.java)),
                owed = r.get("owed", Long::class.java),
            )
        }
    }

    override suspend fun balance(account: String): List<AccountBalance> = io {
        dsl.select(
            DSL.field("currency", SQLDataType.VARCHAR),
            DSL.sum(DSL.field("amount", SQLDataType.BIGINT)).`as`("balance"),
        )
            .from(DSL.table("ledger.entry"))
            .where(DSL.field("account", SQLDataType.VARCHAR).eq(account))
            .groupBy(DSL.field("currency", SQLDataType.VARCHAR))
            .fetch()
            .map { r ->
                AccountBalance(
                    account = account,
                    currency = Currency.valueOf(r.get("currency", String::class.java)),
                    balance = r.get("balance", Long::class.java),
                )
            }
    }
}
