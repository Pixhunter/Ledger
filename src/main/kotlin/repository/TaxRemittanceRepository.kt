package org.example.repository

import org.example.db.io
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.LEDGER_TRANSACTION
import org.example.jooq.tables.references.PAYMENT
import org.example.jooq.tables.references.TAX_DAILY_BALANCE
import org.example.jooq.tables.references.TAX_REMITTANCE
import org.example.model.enums.Currency
import org.example.model.enums.LedgerTransactionType
import org.example.model.enums.PaymentPurpose
import org.example.model.enums.PayoutStatus
import org.example.remittance.DueRemittance
import org.example.remittance.TaxLiability
import org.example.remittance.TaxRemittanceStore
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class TaxRemittanceRepository(private val dsl: DSLContext) : TaxRemittanceStore {

    // Entries are signed: tax owed is a credit, so the liability is the negated sum.
    override suspend fun liabilities(): List<TaxLiability> = io {
        dsl.resultQuery(
            """
            WITH balances AS (
                SELECT purpose_key, -sum(amount) AS amount
                FROM mor.ledger_entry
                WHERE purpose = ? AND currency = ? AND purpose_key IS NOT NULL
                GROUP BY purpose_key
            ), payment_stats AS (
                SELECT tax_country, count(*) AS payment_count, max(tax_rate_bps) AS max_rate_bps
                FROM mor.payment
                WHERE tax_country IS NOT NULL
                GROUP BY tax_country
            )
            SELECT b.purpose_key AS country,
                   b.amount,
                   COALESCE(s.payment_count, 0) AS payment_count,
                   COALESCE(s.max_rate_bps, 0) AS max_rate_bps
            FROM balances b
            LEFT JOIN payment_stats s ON s.tax_country = b.purpose_key
            """.trimIndent(), PaymentPurpose.TAX.id, Currency.EUR.name,
        ).fetch().map { row ->
            val rateBps = row.get("max_rate_bps", Int::class.java) ?: 0
            TaxLiability(
                country = row.get("country", String::class.java)!!,
                amount = row.get("amount", BigDecimal::class.java)!!,
                payments = row.get("payment_count", Long::class.java)?.toInt() ?: 0,
                ratePercent = BigDecimal(rateBps).divide(BigDecimal(100)),
            )
        }
    }

    override suspend fun recordDailyBalances(liabilities: List<TaxLiability>, balanceDate: LocalDate): Unit = io {
        if (liabilities.isEmpty()) return@io
        val rows = liabilities.map { liability ->
            DSL.row(liability.country, balanceDate, liability.amount, Currency.EUR.name)
        }
        dsl.transaction { cfg ->
            DSL.using(cfg).insertInto(
                TAX_DAILY_BALANCE,
                TAX_DAILY_BALANCE.COUNTRY,
                TAX_DAILY_BALANCE.BALANCE_DATE,
                TAX_DAILY_BALANCE.BALANCE,
                TAX_DAILY_BALANCE.CURRENCY,
            )
                .valuesOfRows(rows)
                .onConflict(TAX_DAILY_BALANCE.COUNTRY, TAX_DAILY_BALANCE.BALANCE_DATE)
                .doNothing()
                .execute()
        }
    }

    override suspend fun consecutiveNegativeDays(country: String, balanceDate: LocalDate): Int = io {
        dsl.resultQuery(
            """
            SELECT count(*)
            FROM mor.tax_daily_balance b
            WHERE b.country = ? AND b.balance_date <= ? AND b.balance < 0
              AND b.balance_date > COALESCE((
                  SELECT max(x.balance_date)
                  FROM mor.tax_daily_balance x
                  WHERE x.country = ? AND x.balance_date <= ? AND x.balance >= 0
              ), DATE '-infinity')
            """.trimIndent(), country, balanceDate, country, balanceDate,
        ).fetchOne(0, Int::class.java) ?: 0
    }

    override suspend fun computeRemittance(liability: TaxLiability, periodStart: LocalDate): Boolean = io {
        runCatching {
            dsl.transactionResult { cfg ->
                val db = DSL.using(cfg)
                val transactionId = UUID.randomUUID()
                val amount = liability.amount.takeIf { it.signum() > 0 }
                    ?: return@transactionResult false

                db.insertInto(LEDGER_TRANSACTION)
                    .set(LEDGER_TRANSACTION.ID, transactionId)
                    .set(LEDGER_TRANSACTION.TYPE, LedgerTransactionType.PAYOUT.id)
                    .execute()

                val claimed = db.insertInto(TAX_REMITTANCE)
                    .set(TAX_REMITTANCE.COUNTRY, liability.country)
                    .set(TAX_REMITTANCE.PERIOD_START, periodStart)
                    .set(TAX_REMITTANCE.AMOUNT, amount)
                    .set(TAX_REMITTANCE.CURRENCY, Currency.EUR.name)
                    .set(TAX_REMITTANCE.LEDGER_TRANSACTION_ID, transactionId)
                    .set(TAX_REMITTANCE.STATUS, PayoutStatus.COMPUTED.id)
                    .onConflict(TAX_REMITTANCE.COUNTRY, TAX_REMITTANCE.PERIOD_START)
                    .doNothing()
                    .execute()

                if (claimed == 0) throw AlreadyFiled()

                db.insertInto(
                    LEDGER_ENTRY,
                    LEDGER_ENTRY.TRANSACTION_ID,
                    LEDGER_ENTRY.PURPOSE,
                    LEDGER_ENTRY.PURPOSE_KEY,
                    LEDGER_ENTRY.AMOUNT,
                    LEDGER_ENTRY.CURRENCY,
                )
                    .values(
                        transactionId, PaymentPurpose.TAX.id,
                        liability.country, amount, Currency.EUR.name,
                    )
                    .values(
                        transactionId, PaymentPurpose.PSP.id,
                        null, amount.negate(), Currency.EUR.name,
                    )
                    .execute()

                true
            }
        }.getOrElse { e -> if (e is AlreadyFiled) false else throw e }
    }

    override suspend fun due(status: PayoutStatus, limit: Int): List<DueRemittance> = io {
        require(limit in 1..1000)
        dsl.transactionResult { cfg -> DSL.using(cfg).resultQuery(
            """
            WITH candidates AS (
                SELECT country, period_start
                FROM mor.tax_remittance
                WHERE status = ?
                   OR (status = ? AND claimed_at < now() - interval '5 minutes')
                ORDER BY period_start, country
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE mor.tax_remittance r
            SET status = ?, claimed_at = now()
            FROM candidates c
            WHERE r.country = c.country AND r.period_start = c.period_start
            RETURNING r.country, r.period_start, r.amount
            """.trimIndent(),
            status.id, PayoutStatus.PROCESSING.id, limit, PayoutStatus.PROCESSING.id,
        ).fetch().map { row ->
            DueRemittance(
                row.get(0, String::class.java)!!,
                row.get(1, LocalDate::class.java)!!,
                row.get(2, BigDecimal::class.java)!!,
            )
        } }
    }

    override suspend fun markSent(country: String, periodStart: LocalDate, reference: String): Unit = io {
        dsl.transaction { cfg ->
            DSL.using(cfg)
                .update(TAX_REMITTANCE)
                .set(TAX_REMITTANCE.STATUS, PayoutStatus.SENT.id)
                .set(TAX_REMITTANCE.REFERENCE, reference)
                .where(TAX_REMITTANCE.COUNTRY.eq(country))
                .and(TAX_REMITTANCE.PERIOD_START.eq(periodStart))
                .and(TAX_REMITTANCE.STATUS.eq(PayoutStatus.PROCESSING.id))
                .execute()
        }
    }

    override suspend fun release(country: String, periodStart: LocalDate): Unit = io {
        dsl.transaction { cfg ->
            DSL.using(cfg).update(TAX_REMITTANCE)
                .set(TAX_REMITTANCE.STATUS, PayoutStatus.COMPUTED.id)
                .setNull(DSL.field(DSL.name("claimed_at"), java.time.OffsetDateTime::class.java))
                .where(TAX_REMITTANCE.COUNTRY.eq(country))
                .and(TAX_REMITTANCE.PERIOD_START.eq(periodStart))
                .and(TAX_REMITTANCE.STATUS.eq(PayoutStatus.PROCESSING.id))
                .execute()
        }
    }

    private class AlreadyFiled : RuntimeException()
}
