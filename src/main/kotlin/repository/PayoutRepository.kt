package org.example.repository

import model.DueTransfer
import model.TransferKind
import model.TransferStore
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
import org.example.utils.logger
import java.math.BigDecimal
import org.example.utils.sensitive
import repository.store.PayoutStore

class PayoutRepository(private val dsl: DSLContext) : PayoutStore, TransferStore {
    private val log = logger<PayoutRepository>()

    override suspend fun processingCutoff(endOfDay: Instant): Instant = io {
        log.info("Make the time to be cut for payout calculation")

        dsl.resultQuery(
            "SELECT least(CAST(? AS timestamptz), clock_timestamp())",
            endOfDay.atOffset(ZoneOffset.UTC),
        ).fetchSingle(0, OffsetDateTime::class.java)!!.toInstant()
    }

    override suspend fun balancePage(afterMerchantId: UUID?, limit: Int, cutoff: Instant): List<MerchantBalance> = io {
        log.info("Start balance for $afterMerchantId")
        if (limit <= 0) {
            log.info("Limit $limit <= 0 - skipping payout")
            return@io emptyList()
        }

        val cursorCondition = if (afterMerchantId == null) "" else "AND purpose_key > ?"

        val bindings = buildList<Any> {
            add(PaymentPurpose.MERCHANT.id)
            add(Currency.EUR.name)
            add(cutoff.atOffset(ZoneOffset.UTC))
            add(cutoff.atOffset(ZoneOffset.UTC))
            add(cutoff.atOffset(ZoneOffset.UTC))
            afterMerchantId?.let { add(it.toString()) }
            add(limit)
        }

        val result = dsl.resultQuery(
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
                  AND le.occurred_at < CAST(? AS timestamptz)
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
        )
            .fetch()
            .map { row ->
                MerchantBalance(
                    merchantId = row.get("merchant_id", UUID::class.java)!!,
                    merchantName = row.get("merchant_name", String::class.java)!!,
                    amount = row.get("amount", BigDecimal::class.java)!!,
                    payments = row.get("payment_count", Long::class.java)?.toInt() ?: 0,
                    suspended = row.get("suspended", Boolean::class.java) ?: true,
                )
            }

        log.info("Got ${result.size} results")
        result
    }

    override suspend fun recordDailyBalances(balances: List<MerchantBalance>, balanceDate: LocalDate): Unit = io {
        if (balances.isEmpty()) return@io

        log.info("Calculating daily balances for balanceDate=$balanceDate")
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
        log.info("Count negative balance days for merchantId=$merchantId and on balanceDate=$balanceDate")

        val result = dsl.resultQuery(
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

        log.info("Got result $result")
        result
    }

    override suspend fun computePayouts(
        balances: List<MerchantBalance>,
        payoutDate: LocalDate,
        cutoff: Instant,
    ): List<MerchantBalance> = io {
        val candidates = balances.filter { it.amount.signum() > 0 }
        if (candidates.isEmpty()) return@io emptyList()

        log.info("Computing payouts on payoutDate=$payoutDate")

        val result = dsl.transactionResult { cfg ->
            val db = DSL.using(cfg)
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val computed = mutableListOf<MerchantBalance>()

            candidates.forEach { candidate ->
                val transactionId = randomUuid()
                db.insertInto(LEDGER_TRANSACTION)
                    .set(LEDGER_TRANSACTION.ID, transactionId)
                    .set(LEDGER_TRANSACTION.TYPE, LedgerTransactionType.PAYOUT.id)
                    .execute()

                val reserved = db.insertInto(PAYOUT)
                    .set(PAYOUT.MERCHANT_ID, candidate.merchantId)
                    .set(PAYOUT.PAYOUT_DATE, payoutDate)
                    .set(PAYOUT.AMOUNT, candidate.amount)
                    .set(PAYOUT.CURRENCY, Currency.EUR.name)
                    .set(PAYOUT.LEDGER_TRANSACTION_ID, transactionId)
                    .set(PAYOUT.STATUS, PayoutStatus.COMPUTED.id)
                    .onConflict(PAYOUT.MERCHANT_ID, PAYOUT.PAYOUT_DATE)
                    .doNothing()
                    .returning(PAYOUT.MERCHANT_ID)
                    .fetchOne() != null

                if (!reserved) {
                    db.deleteFrom(LEDGER_TRANSACTION)
                        .where(LEDGER_TRANSACTION.ID.eq(transactionId))
                        .execute()
                    return@forEach
                }

                val settledAmounts = db.resultQuery(
                    """
                    WITH eligible AS MATERIALIZED (
                        SELECT le.id, le.amount
                        FROM mor.ledger_entry le
                        JOIN mor.ledger_transaction lt ON lt.id = le.transaction_id
                        LEFT JOIN mor.payment p ON p.id = lt.payment_id
                        WHERE le.purpose = ? AND le.purpose_key = ? AND le.currency = ?
                          AND le.settled_by_transaction_id IS NULL
                          AND lt.type <> ?
                          AND lt.created_at < CAST(? AS timestamptz)
                          AND le.occurred_at < CAST(? AS timestamptz)
                          AND (p.payment_time IS NULL OR p.payment_time < CAST(? AS timestamptz))
                        FOR UPDATE OF le
                    ), total AS (
                        SELECT -COALESCE(sum(amount), 0) AS amount
                        FROM eligible
                    ), settled AS (
                        UPDATE mor.ledger_entry le
                        SET settled_by_transaction_id = CAST(? AS uuid)
                        FROM eligible e, total t
                        WHERE le.id = e.id AND t.amount > 0
                        RETURNING le.amount
                    )
                    SELECT amount FROM settled
                    """.trimIndent(),
                    PaymentPurpose.MERCHANT.id,
                    candidate.merchantId.toString(),
                    Currency.EUR.name,
                    LedgerTransactionType.PAYOUT.id,
                    cutoff.atOffset(ZoneOffset.UTC),
                    cutoff.atOffset(ZoneOffset.UTC),
                    cutoff.atOffset(ZoneOffset.UTC),
                    transactionId,
                ).fetch(0, BigDecimal::class.java)

                val amount = settledAmounts.fold(BigDecimal.ZERO, BigDecimal::add).negate()
                if (amount.signum() <= 0) {
                    db.deleteFrom(PAYOUT)
                        .where(PAYOUT.MERCHANT_ID.eq(candidate.merchantId))
                        .and(PAYOUT.PAYOUT_DATE.eq(payoutDate))
                        .and(PAYOUT.LEDGER_TRANSACTION_ID.eq(transactionId))
                        .execute()
                    db.deleteFrom(LEDGER_TRANSACTION)
                        .where(LEDGER_TRANSACTION.ID.eq(transactionId))
                        .execute()
                    return@forEach
                }

                db.update(PAYOUT)
                    .set(PAYOUT.AMOUNT, amount)
                    .where(PAYOUT.MERCHANT_ID.eq(candidate.merchantId))
                    .and(PAYOUT.PAYOUT_DATE.eq(payoutDate))
                    .and(PAYOUT.LEDGER_TRANSACTION_ID.eq(transactionId))
                    .execute()

                db.insertInto(
                    LEDGER_ENTRY,
                    LEDGER_ENTRY.TRANSACTION_ID,
                    LEDGER_ENTRY.PURPOSE,
                    LEDGER_ENTRY.PURPOSE_KEY,
                    LEDGER_ENTRY.AMOUNT,
                    LEDGER_ENTRY.CURRENCY,
                    LEDGER_ENTRY.OCCURRED_AT,
                )
                    .values(
                        transactionId, PaymentPurpose.MERCHANT.id,
                        candidate.merchantId.toString(), amount, Currency.EUR.name, now,
                    )
                    .values(
                        transactionId, PaymentPurpose.PSP.id,
                        null, amount.negate(), Currency.EUR.name, now,
                    )
                    .execute()

                computed += candidate.copy(amount = amount)
            }

            computed
        }

        log.info("Got ${result.size} results")
        result
    }

    override suspend fun due(limit: Int): List<DueTransfer> = io {
        if (limit !in 1..Constants.Jobs.MAX_BATCH_SIZE) return@io emptyList()

        val result = dsl.transactionResult { cfg ->
            DSL.using(cfg).resultQuery(
                """
            WITH candidates AS (
                SELECT p.merchant_id, p.payout_date
                FROM mor.payout p
                JOIN mor.merchant_payment_details d ON d.merchant_id = p.merchant_id
                WHERE p.status = ?
                   OR (p.status = ? AND p.claimed_at < now() - make_interval(mins => ${Constants.Jobs.CLAIM_TIMEOUT_MINUTES}))
                ORDER BY p.payout_date, p.merchant_id
                FOR UPDATE OF p SKIP LOCKED
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

        log.info("Got ${result.size} results")
        result
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
