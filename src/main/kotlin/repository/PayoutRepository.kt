package org.example.repository

import org.example.api.io
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.LEDGER_TRANSACTION
import org.example.jooq.tables.references.MERCHANT_DAILY_BALANCE
import org.example.jooq.tables.references.PAYOUT
import org.example.model.enums.Currency
import org.example.model.enums.LedgerTransactionType
import org.example.model.enums.PaymentPurpose
import org.example.model.enums.PayoutStatus
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.Instant
import java.util.UUID
import org.example.utils.Constants
import org.example.api.randomUuid
import org.example.model.MerchantBalance
import org.example.model.enums.MerchantStatus
import java.math.BigDecimal
import org.example.utils.sensitive

class PayoutRepository(private val dsl: DSLContext) : PayoutStore, TransferStore {

    override suspend fun processingCutoff(endOfDay: Instant): Instant = io {
        dsl.resultQuery(
            "SELECT least(CAST(? AS timestamptz), clock_timestamp())",
            endOfDay.atOffset(ZoneOffset.UTC),
        ).fetchSingle(0, java.time.OffsetDateTime::class.java)!!.toInstant()
    }

    override suspend fun balancePage(afterMerchantId: UUID?, limit: Int, cutoff: Instant): List<MerchantBalance> = io {
        if (limit <= 0) return@io emptyList()

        val cursorCondition = if (afterMerchantId == null) "" else "AND purpose_key > ?"
        val bindings = buildList<Any> {
            add(PaymentPurpose.MERCHANT.id)
            add(Currency.EUR.name)
            add(cutoff.atOffset(ZoneOffset.UTC))
            add(cutoff.atOffset(ZoneOffset.UTC))
            afterMerchantId?.let { add(it.toString()) }
            add(limit)
        }
        dsl.resultQuery(
            """
            WITH balances AS (
                SELECT le.purpose_key, -sum(le.amount) AS amount,
                       count(*) FILTER (WHERE lt.type = ${LedgerTransactionType.CAPTURE.id}) AS payment_count
                FROM mor.ledger_entry le
                JOIN mor.ledger_transaction lt ON lt.id = le.transaction_id
                LEFT JOIN mor.payment p ON p.id = lt.payment_id
                WHERE le.purpose = ? AND le.currency = ? AND le.purpose_key IS NOT NULL
                  AND le.settled_by_transaction_id IS NULL
                  AND lt.type <> ${LedgerTransactionType.PAYOUT.id}
                  AND lt.created_at < CAST(? AS timestamptz)
                  AND (p.payment_time IS NULL OR p.payment_time < CAST(? AS timestamptz))
                  $cursorCondition
                GROUP BY le.purpose_key
            )
            SELECT b.purpose_key::uuid AS merchant_id,
                   COALESCE(m.name, 'unknown merchant') AS merchant_name,
                   b.amount, b.payment_count,
                   COALESCE(m.status, 0) <> ${MerchantStatus.ACTIVE.id} AS suspended
            FROM balances b
            LEFT JOIN mor.merchant m ON m.id = b.purpose_key::uuid
            ORDER BY b.purpose_key
            LIMIT ?
            """.trimIndent(),
            *bindings.toTypedArray(),
        ).fetch().map { row ->
            MerchantBalance(
                merchantId = row.get("merchant_id", UUID::class.java)!!,
                merchantName = row.get("merchant_name", String::class.java)!!,
                amount = row.get("amount", BigDecimal::class.java)!!,
                payments = row.get("payment_count", Long::class.java)?.toInt() ?: 0,
                suspended = row.get("suspended", Boolean::class.java) ?: true,
            )
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
            """.trimIndent(),
            merchantId, balanceDate, merchantId, balanceDate,
        ).fetchOne(0, Int::class.java) ?: 0
    }

    override suspend fun computePayouts(
        balances: List<MerchantBalance>,
        payoutDate: LocalDate,
        cutoff: Instant,
    ): List<MerchantBalance> = io {
        val candidates = balances.filter { it.amount.signum() > 0 }
        if (candidates.isEmpty()) return@io emptyList()

        dsl.transactionResult { cfg ->
            val db = DSL.using(cfg)
            val transactionByMerchant = candidates.associate { it.merchantId to randomUuid() }

            val transactions = db.insertInto(
                LEDGER_TRANSACTION,
                LEDGER_TRANSACTION.ID,
                LEDGER_TRANSACTION.TYPE,
            )
            transactionByMerchant.values.forEach { transactionId ->
                transactions.values(transactionId, LedgerTransactionType.PAYOUT.id)
            }
            transactions.execute()

            val payouts = db.insertInto(
                PAYOUT,
                PAYOUT.MERCHANT_ID,
                PAYOUT.PAYOUT_DATE,
                PAYOUT.AMOUNT,
                PAYOUT.CURRENCY,
                PAYOUT.LEDGER_TRANSACTION_ID,
                PAYOUT.STATUS,
            )
            candidates.forEach { balance ->
                payouts.values(
                    balance.merchantId,
                    payoutDate,
                    balance.amount,
                    Currency.EUR.name,
                    transactionByMerchant.getValue(balance.merchantId),
                    PayoutStatus.COMPUTED.id,
                )
            }
            val inserted = payouts
                .onConflict(PAYOUT.MERCHANT_ID, PAYOUT.PAYOUT_DATE)
                .doNothing()
                .returning(PAYOUT.MERCHANT_ID, PAYOUT.LEDGER_TRANSACTION_ID)
                .fetch()

            val insertedIds = inserted.map { it.merchantId }.toSet()
            val insertedTransactions = inserted.map { it.ledgerTransactionId }.toSet()
            val unusedTransactions = transactionByMerchant.values - insertedTransactions
            if (unusedTransactions.isNotEmpty()) {
                db.deleteFrom(LEDGER_TRANSACTION)
                    .where(LEDGER_TRANSACTION.ID.`in`(unusedTransactions))
                    .execute()
            }

            val now = OffsetDateTime.now(ZoneOffset.UTC)

            if (insertedIds.isNotEmpty()) {
                val lines = db.insertInto(
                    LEDGER_ENTRY,
                    LEDGER_ENTRY.TRANSACTION_ID,
                    LEDGER_ENTRY.PURPOSE,
                    LEDGER_ENTRY.PURPOSE_KEY,
                    LEDGER_ENTRY.AMOUNT,
                    LEDGER_ENTRY.CURRENCY,
                    LEDGER_ENTRY.OCCURRED_AT,
                )
                candidates.filter { it.merchantId in insertedIds }.forEach { balance ->
                    val transactionId = transactionByMerchant.getValue(balance.merchantId)
                    lines.values(
                        transactionId, PaymentPurpose.MERCHANT.id,
                        balance.merchantId.toString(), balance.amount, Currency.EUR.name, now,
                    )
                    lines.values(
                        transactionId, PaymentPurpose.PSP.id,
                        null, balance.amount.negate(), Currency.EUR.name, now,
                    )
                }
                lines.execute()

                val settlementRows = inserted.associate { it.merchantId.toString() to it.ledgerTransactionId }
                val valuesSql = settlementRows.keys.joinToString(",") { "(?, CAST(? AS uuid))" }
                val settlementBindings = buildList<Any> {
                    settlementRows.forEach { (merchantId, transactionId) ->
                        add(merchantId)
                        add(transactionId)
                    }
                    add(PaymentPurpose.MERCHANT.id)
                    add(Currency.EUR.name)
                    add(LedgerTransactionType.PAYOUT.id)
                    add(cutoff.atOffset(ZoneOffset.UTC))
                    add(cutoff.atOffset(ZoneOffset.UTC))
                }
                db.query(
                    """
                    UPDATE mor.ledger_entry le
                    SET settled_by_transaction_id = s.transaction_id
                    FROM (VALUES $valuesSql) AS s(purpose_key, transaction_id), mor.ledger_transaction lt
                    LEFT JOIN mor.payment p ON p.id = lt.payment_id
                    WHERE le.purpose_key = s.purpose_key
                      AND lt.id = le.transaction_id
                      AND le.purpose = ? AND le.currency = ?
                      AND le.settled_by_transaction_id IS NULL
                      AND lt.type <> ?
                      AND lt.created_at < CAST(? AS timestamptz)
                      AND (p.payment_time IS NULL OR p.payment_time < CAST(? AS timestamptz))
                    """.trimIndent(),
                    *settlementBindings.toTypedArray(),
                ).execute()
            }

            candidates.filter { it.merchantId in insertedIds }
        }
    }

    override suspend fun due(limit: Int): List<DueTransfer> = io {
        if (limit !in 1..Constants.Jobs.MAX_BATCH_SIZE) return@io emptyList()

        dsl.transactionResult { cfg ->
            DSL.using(cfg).resultQuery(
                """
            WITH candidates AS (
                SELECT merchant_id, payout_date
                FROM mor.payout
                WHERE status = ?
                   OR (status = ? AND claimed_at < now() - make_interval(mins => ${Constants.Jobs.CLAIM_TIMEOUT_MINUTES}))
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
                PayoutStatus.COMPUTED.id, PayoutStatus.PROCESSING.id, limit, PayoutStatus.PROCESSING.id,
            ).fetch().map { row ->
                DueTransfer(
                    kind = TransferKind.PAYOUT,
                    key = row.get(0, UUID::class.java)!!.toString(),
                    period = row.get(1, LocalDate::class.java)!!,
                    destination = row.get(2, String::class.java)!!.sensitive(),
                    amount = row.get(3, BigDecimal::class.java)!!,
                )
            }
        }
    }

    override suspend fun markSent(transfer: DueTransfer, externalReference: String): Unit = io {
        dsl.transaction { cfg ->
            DSL.using(cfg)
                .update(PAYOUT)
                .set(PAYOUT.STATUS, PayoutStatus.SENT.id)
                .set(PAYOUT.PSP_REFERENCE, externalReference)
                .where(claim(transfer))
                .execute()
        }
    }

    override suspend fun release(transfer: DueTransfer): Unit = io {
        dsl.transaction { cfg ->
            DSL.using(cfg).update(PAYOUT)
                .set(PAYOUT.STATUS, PayoutStatus.COMPUTED.id)
                .setNull(PAYOUT.CLAIMED_AT)
                .where(claim(transfer))
                .execute()
        }
    }

    private fun claim(transfer: DueTransfer) =
        PAYOUT.MERCHANT_ID.eq(UUID.fromString(transfer.key))
            .and(PAYOUT.PAYOUT_DATE.eq(transfer.period))
            .and(PAYOUT.STATUS.eq(PayoutStatus.PROCESSING.id))
}
