package org.example.ledger.repository

import org.example.db.io
import org.example.jooq.tables.references.ENTRY
import org.example.jooq.tables.references.MERCHANT_BALANCE
import org.example.jooq.tables.references.TAX_LIABILITY
import org.example.ledger.AccountBalance
import org.example.ledger.LedgerEntry
import org.example.ledger.LedgerRepository
import org.example.ledger.MerchantBalance
import org.example.ledger.TaxLiability
import org.example.model.enums.Currency
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.ZoneOffset

/**
 * Typed against the generated ENTRY table and the two report views.
 *
 * The plain-SQL version needed an explicit ?::timestamptz cast, because a
 * string-bound parameter carries no type and Postgres would not coerce it.
 * With generated fields the type travels with the column, so that whole class
 * of bug cannot occur - and renaming a column breaks the build instead of a
 * request.
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

            // One multi-row insert rather than a statement per entry: fewer
            // round trips, and the whole transaction lands or none of it does.
            var insert = db.insertInto(
                ENTRY,
                ENTRY.TRANSACTION_ID,
                ENTRY.REQUEST_ID,
                ENTRY.KIND,
                ENTRY.ACCOUNT,
                ENTRY.AMOUNT,
                ENTRY.CURRENCY,
                ENTRY.JURISDICTION,
                ENTRY.OCCURRED_AT,
            )

            entries.forEach { e ->
                insert = insert.values(
                    e.transactionId,
                    e.requestId,
                    e.kind.name,
                    e.account,
                    e.amount,
                    e.currency.name,
                    e.jurisdiction,
                    e.occurredAt.atOffset(ZoneOffset.UTC),
                )
            }

            val inserted = insert
                .onConflict(ENTRY.REQUEST_ID, ENTRY.ACCOUNT)
                .doNothing()
                .execute()

            when (inserted) {
                0 -> {
                    log.info("replay of {}, nothing posted", entries.first().requestId)
                    false
                }

                entries.size -> true

                // A partial insert would leave the transaction unbalanced.
                // Refuse rather than commit a ledger that does not add up.
                else -> error(
                    "partial post for ${entries.first().requestId}: $inserted of ${entries.size}"
                )
            }
        }
    }

    override suspend fun taxLiabilities(): List<TaxLiability> = io {
        dsl.select(TAX_LIABILITY.JURISDICTION, TAX_LIABILITY.CURRENCY, TAX_LIABILITY.OWED)
            .from(TAX_LIABILITY)
            .orderBy(TAX_LIABILITY.JURISDICTION, TAX_LIABILITY.CURRENCY)
            .fetch()
            .map { r ->
                TaxLiability(
                    jurisdiction = r[TAX_LIABILITY.JURISDICTION].orEmpty(),
                    currency = Currency.valueOf(r[TAX_LIABILITY.CURRENCY]!!),
                    owed = r[TAX_LIABILITY.OWED].toMinorUnits(),
                )
            }
    }

    override suspend fun merchantBalances(): List<MerchantBalance> = io {
        dsl.select(MERCHANT_BALANCE.MERCHANT_ID, MERCHANT_BALANCE.CURRENCY, MERCHANT_BALANCE.OWED)
            .from(MERCHANT_BALANCE)
            .orderBy(MERCHANT_BALANCE.MERCHANT_ID, MERCHANT_BALANCE.CURRENCY)
            .fetch()
            .map { r ->
                MerchantBalance(
                    merchantId = r[MERCHANT_BALANCE.MERCHANT_ID].orEmpty(),
                    currency = Currency.valueOf(r[MERCHANT_BALANCE.CURRENCY]!!),
                    owed = r[MERCHANT_BALANCE.OWED].toMinorUnits(),
                )
            }
    }

    override suspend fun balance(account: String): List<AccountBalance> = io {
        val total = DSL.sum(ENTRY.AMOUNT)

        dsl.select(ENTRY.CURRENCY, total)
            .from(ENTRY)
            .where(ENTRY.ACCOUNT.eq(account))
            .groupBy(ENTRY.CURRENCY)
            .fetch()
            .map { r ->
                AccountBalance(
                    account = account,
                    currency = Currency.valueOf(r[ENTRY.CURRENCY]!!),
                    balance = r[total].toMinorUnits(),
                )
            }
    }

    /**
     * SUM() over a bigint comes back as numeric in Postgres, so jOOQ hands us
     * a BigDecimal. Amounts are minor units and always whole, so this is exact
     * rather than a rounding decision.
     */
    private fun BigDecimal?.toMinorUnits(): Long = this?.toLong() ?: 0L
}
