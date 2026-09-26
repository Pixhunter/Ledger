package org.example.repository

import org.example.db.io
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.LEDGER_TRANSACTION
import org.example.jooq.tables.references.MERCHANT_DAILY_BALANCE
import org.example.jooq.tables.references.PAYOUT
import org.example.model.enums.Currency
import org.example.model.enums.LedgerTransactionType
import org.example.model.enums.PaymentPurpose
import org.example.model.enums.PayoutStatus
import org.example.payout.DuePayout
import org.example.payout.MerchantBalance
import org.example.payout.PayoutStore
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate
import java.util.UUID

class PayoutRepository(private val dsl: DSLContext) : PayoutStore {

    // Entries are signed: a credit to the merchant is negative, so what we owe
    // is the negated sum.
    override suspend fun balances(): List<MerchantBalance> = io {
        dsl.resultQuery(
            """
            WITH balances AS (
                SELECT purpose_key, -sum(amount) AS amount
                FROM mor.ledger_entry
                WHERE purpose = ? AND currency = ? AND purpose_key IS NOT NULL
                GROUP BY purpose_key
            ), payment_counts AS (
                SELECT merchant_id, count(*) AS payment_count
                FROM mor.payment
                WHERE merchant_id IS NOT NULL
                GROUP BY merchant_id
            )
            SELECT b.purpose_key::uuid AS merchant_id,
                   COALESCE(m.name, 'unknown merchant') AS merchant_name,
                   b.amount,
                   COALESCE(pc.payment_count, 0) AS payment_count
            FROM balances b
            LEFT JOIN mor.merchant m ON m.id = b.purpose_key::uuid
            LEFT JOIN payment_counts pc ON pc.merchant_id = b.purpose_key::uuid
            """.trimIndent(),
            PaymentPurpose.MERCHANT.id,
            Currency.EUR.name,
        ).fetch().map { row ->
            MerchantBalance(
                merchantId = row.get("merchant_id", UUID::class.java)!!,
                merchantName = row.get("merchant_name", String::class.java)!!,
                amount = row.get("amount", java.math.BigDecimal::class.java)!!,
                payments = row.get("payment_count", Long::class.java)?.toInt() ?: 0,
            )
        }
    }

    override suspend fun recordDailyBalance(balance: MerchantBalance, balanceDate: LocalDate): Unit = io {
        dsl.transaction { cfg ->
            DSL.using(cfg)
                .insertInto(MERCHANT_DAILY_BALANCE)
                .set(MERCHANT_DAILY_BALANCE.MERCHANT_ID, balance.merchantId)
                .set(MERCHANT_DAILY_BALANCE.BALANCE_DATE, balanceDate)
                .set(MERCHANT_DAILY_BALANCE.BALANCE, balance.amount)
                .set(MERCHANT_DAILY_BALANCE.CURRENCY, Currency.EUR.name)
                .onConflict(MERCHANT_DAILY_BALANCE.MERCHANT_ID, MERCHANT_DAILY_BALANCE.BALANCE_DATE)
                .doNothing()
                .execute()
        }
    }

    override suspend fun recordDailyBalances(balances: List<MerchantBalance>, balanceDate: LocalDate): Unit = io {
        if (balances.isEmpty()) return@io
        val rows = balances.map { balance ->
            DSL.row(balance.merchantId, balanceDate, balance.amount, Currency.EUR.name)
        }
        dsl.transaction { cfg ->
            DSL.using(cfg).insertInto(
                MERCHANT_DAILY_BALANCE,
                MERCHANT_DAILY_BALANCE.MERCHANT_ID,
                MERCHANT_DAILY_BALANCE.BALANCE_DATE,
                MERCHANT_DAILY_BALANCE.BALANCE,
                MERCHANT_DAILY_BALANCE.CURRENCY,
            )
                .valuesOfRows(rows)
                .onConflict(MERCHANT_DAILY_BALANCE.MERCHANT_ID, MERCHANT_DAILY_BALANCE.BALANCE_DATE)
                .doNothing()
                .execute()
        }
    }

    override suspend fun consecutiveNegativeDays(merchantId: UUID, balanceDate: LocalDate): Int = io {
        dsl.resultQuery(
            """
            SELECT count(*)
            FROM mor.merchant_daily_balance b
            WHERE b.merchant_id = ? AND b.balance_date <= ? AND b.balance < 0
              AND b.balance_date > COALESCE((
                  SELECT max(x.balance_date)
                  FROM mor.merchant_daily_balance x
                  WHERE x.merchant_id = ? AND x.balance_date <= ? AND x.balance >= 0
              ), DATE '-infinity')
            """.trimIndent(), merchantId, balanceDate, merchantId, balanceDate,
        ).fetchOne(0, Int::class.java) ?: 0
    }

    override suspend fun computePayout(balance: MerchantBalance, payoutDate: LocalDate): Boolean = io {
        runCatching {
            dsl.transactionResult { cfg ->
                val db = DSL.using(cfg)
                val transactionId = UUID.randomUUID()
                val amount = balance.amount.takeIf { it.signum() > 0 }
                    ?: return@transactionResult false

                db.insertInto(LEDGER_TRANSACTION)
                    .set(LEDGER_TRANSACTION.ID, transactionId)
                    .set(LEDGER_TRANSACTION.TYPE, LedgerTransactionType.PAYOUT.id)
                    .execute()

                val claimed = db.insertInto(PAYOUT)
                    .set(PAYOUT.MERCHANT_ID, balance.merchantId)
                    .set(PAYOUT.PAYOUT_DATE, payoutDate)
                    .set(PAYOUT.AMOUNT, amount)
                    .set(PAYOUT.CURRENCY, Currency.EUR.name)
                    .set(PAYOUT.LEDGER_TRANSACTION_ID, transactionId)
                    .set(PAYOUT.STATUS, PayoutStatus.COMPUTED.id)
                    .onConflict(PAYOUT.MERCHANT_ID, PAYOUT.PAYOUT_DATE)
                    .doNothing()
                    .execute()

                if (claimed == 0) throw AlreadyComputed()

                db.insertInto(
                    LEDGER_ENTRY,
                    LEDGER_ENTRY.TRANSACTION_ID,
                    LEDGER_ENTRY.PURPOSE,
                    LEDGER_ENTRY.PURPOSE_KEY,
                    LEDGER_ENTRY.AMOUNT,
                    LEDGER_ENTRY.CURRENCY,
                )
                    .values(
                        transactionId, PaymentPurpose.MERCHANT.id,
                        balance.merchantId.toString(), amount, Currency.EUR.name,
                    )
                    .values(
                        transactionId, PaymentPurpose.PSP.id,
                        null, amount.negate(), Currency.EUR.name,
                    )
                    .execute()

                true
            }
        }.getOrElse { e -> if (e is AlreadyComputed) false else throw e }
    }

    override suspend fun due(status: PayoutStatus, limit: Int): List<DuePayout> = io {
        require(limit in 1..1000)
        dsl.transactionResult { cfg -> DSL.using(cfg).resultQuery(
            """
            WITH candidates AS (
                SELECT merchant_id, payout_date
                FROM mor.payout
                WHERE status = ?
                   OR (status = ? AND claimed_at < now() - interval '5 minutes')
                ORDER BY payout_date, merchant_id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            ), claimed AS (
                UPDATE mor.payout p
                SET status = ?, claimed_at = now()
                FROM candidates c
                WHERE p.merchant_id = c.merchant_id AND p.payout_date = c.payout_date
                RETURNING p.merchant_id, p.payout_date, p.amount
            )
            SELECT c.merchant_id, c.payout_date, d.psp_account_id, c.amount
            FROM claimed c
            JOIN mor.merchant_payment_details d ON d.merchant_id = c.merchant_id
            ORDER BY c.payout_date, c.merchant_id
            """.trimIndent(),
            status.id, PayoutStatus.PROCESSING.id, limit, PayoutStatus.PROCESSING.id,
        ).fetch().map { row ->
            DuePayout(
                row.get(0, UUID::class.java)!!,
                row.get(1, LocalDate::class.java)!!,
                row.get(2, String::class.java)!!,
                row.get(3, java.math.BigDecimal::class.java)!!,
            )
        } }
    }

    override suspend fun markSent(
        merchantId: UUID,
        payoutDate: LocalDate,
        pspReference: String,
    ): Unit = io {
        dsl.transaction { cfg ->
            DSL.using(cfg)
                .update(PAYOUT)
                .set(PAYOUT.STATUS, PayoutStatus.SENT.id)
                .set(PAYOUT.PSP_REFERENCE, pspReference)
                .where(PAYOUT.MERCHANT_ID.eq(merchantId))
                .and(PAYOUT.PAYOUT_DATE.eq(payoutDate))
                .and(PAYOUT.STATUS.eq(PayoutStatus.PROCESSING.id))
                .execute()
        }
    }

    override suspend fun release(merchantId: UUID, payoutDate: LocalDate): Unit = io {
        dsl.transaction { cfg ->
            DSL.using(cfg).update(PAYOUT)
                .set(PAYOUT.STATUS, PayoutStatus.COMPUTED.id)
                .setNull(DSL.field(DSL.name("claimed_at"), java.time.OffsetDateTime::class.java))
                .where(PAYOUT.MERCHANT_ID.eq(merchantId))
                .and(PAYOUT.PAYOUT_DATE.eq(payoutDate))
                .and(PAYOUT.STATUS.eq(PayoutStatus.PROCESSING.id))
                .execute()
        }
    }

    private class AlreadyComputed : RuntimeException()
}
