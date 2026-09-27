package org.example.repository

import model.DueTransfer
import model.TransferKind
import model.TransferStore
import model.SentTransfer
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

    override suspend fun consecutiveNegativeDays(
        merchantIds: Collection<UUID>,
        balanceDate: LocalDate,
    ): Map<UUID, Int> = io {
        val ids = merchantIds.distinct()
        if (ids.isEmpty()) return@io emptyMap()
        val values = ids.joinToString(", ") { "(CAST(? AS uuid))" }
        val bindings = buildList<Any> {
            addAll(ids)
            add(balanceDate)
            add(balanceDate)
        }

        dsl.resultQuery(
            """
            WITH requested(merchant_id) AS (VALUES $values),
            last_non_negative AS (
                SELECT b.merchant_id, max(b.balance_date) AS balance_date
                FROM mor.merchant_daily_balance b
                JOIN requested r USING (merchant_id)
                WHERE b.balance_date <= CAST(? AS date) AND b.balance >= 0
                GROUP BY b.merchant_id
            )
            SELECT r.merchant_id, count(b.balance_date) AS negative_days
            FROM requested r
            LEFT JOIN last_non_negative n USING (merchant_id)
            LEFT JOIN mor.merchant_daily_balance b
              ON b.merchant_id = r.merchant_id
             AND b.balance_date <= CAST(? AS date)
             AND b.balance_date > COALESCE(n.balance_date, DATE '-infinity')
             AND b.balance < 0
            GROUP BY r.merchant_id
            """.trimIndent(),
            *bindings.toTypedArray(),
        ).fetch().associate { row ->
            row.get("merchant_id", UUID::class.java)!! to
                (row.get("negative_days", Long::class.java)?.toInt() ?: 0)
        }
    }

    override suspend fun computePayouts(
        balances: List<MerchantBalance>,
        payoutDate: LocalDate,
        cutoff: Instant,
    ): List<MerchantBalance> = io {
        val candidates = balances
            .filter { it.amount.signum() > 0 }
            .distinctBy { it.merchantId }
        if (candidates.isEmpty()) return@io emptyList()

        log.info("Computing payouts on payoutDate=$payoutDate")

        val result = dsl.transactionResult { cfg ->
            val db = DSL.using(cfg)
            val inputs = candidates.map { it to randomUuid() }

            db.insertInto(
                LEDGER_TRANSACTION,
                LEDGER_TRANSACTION.ID,
                LEDGER_TRANSACTION.TYPE,
            )
                .valuesOfRows(
                    inputs.map { (_, transactionId) ->
                        DSL.row(transactionId, LedgerTransactionType.PAYOUT.id)
                    }
                )
                .execute()

            val inserted = db.insertInto(
                PAYOUT,
                PAYOUT.MERCHANT_ID,
                PAYOUT.PAYOUT_DATE,
                PAYOUT.AMOUNT,
                PAYOUT.CURRENCY,
                PAYOUT.LEDGER_TRANSACTION_ID,
                PAYOUT.STATUS,
            )
                .valuesOfRows(
                    inputs.map { (candidate, transactionId) ->
                        DSL.row(
                            candidate.merchantId,
                            payoutDate,
                            candidate.amount,
                            Currency.EUR.name,
                            transactionId,
                            PayoutStatus.COMPUTED.id,
                        )
                    }
                )
                .onConflict(PAYOUT.MERCHANT_ID, PAYOUT.PAYOUT_DATE)
                .doNothing()
                .returning(PAYOUT.MERCHANT_ID, PAYOUT.LEDGER_TRANSACTION_ID)
                .fetch()
                .map { it.get(PAYOUT.MERCHANT_ID)!! to it.get(PAYOUT.LEDGER_TRANSACTION_ID)!! }

            val insertedIds = inserted.mapTo(mutableSetOf()) { it.second }
            val reserved = db.select(
                PAYOUT.MERCHANT_ID,
                PAYOUT.LEDGER_TRANSACTION_ID,
                PAYOUT.AMOUNT,
            )
                .from(PAYOUT)
                .where(PAYOUT.MERCHANT_ID.`in`(candidates.map { it.merchantId }))
                .and(PAYOUT.PAYOUT_DATE.eq(payoutDate))
                .and(PAYOUT.STATUS.eq(PayoutStatus.COMPUTED.id))
                .forUpdate()
                .fetch()
                .map {
                    ReservedPayout(
                        merchantId = it.get(PAYOUT.MERCHANT_ID)!!,
                        transactionId = it.get(PAYOUT.LEDGER_TRANSACTION_ID)!!,
                        existingAmount = if (it.get(PAYOUT.LEDGER_TRANSACTION_ID) in insertedIds) {
                            BigDecimal.ZERO
                        } else {
                            it.get(PAYOUT.AMOUNT)!!
                        },
                    )
                }

            if (reserved.isEmpty()) {
                db.deleteFrom(LEDGER_TRANSACTION)
                    .where(LEDGER_TRANSACTION.ID.`in`(inputs.map { it.second }))
                    .execute()
                return@transactionResult emptyList()
            }

            val reservedValues = reserved.joinToString(", ") {
                "(CAST(? AS uuid), CAST(? AS uuid), CAST(? AS numeric))"
            }
            val bindings = buildList<Any> {
                reserved.forEach {
                    add(it.merchantId)
                    add(it.transactionId)
                    add(it.existingAmount)
                }
                add(PaymentPurpose.MERCHANT.id)
                add(Currency.EUR.name)
                add(LedgerTransactionType.PAYOUT.id)
                repeat(3) { add(cutoff.atOffset(ZoneOffset.UTC)) }
                add(payoutDate)
                add(PaymentPurpose.MERCHANT.id)
                add(PaymentPurpose.PSP.id)
            }

            val computedRows = db.resultQuery(
                """
                WITH reserved(merchant_id, transaction_id, existing_amount) AS (
                    VALUES $reservedValues
                ), eligible AS MATERIALIZED (
                    SELECT le.id, le.amount, r.merchant_id, r.transaction_id
                    FROM mor.ledger_entry le
                    JOIN reserved r ON le.purpose_key = r.merchant_id::text
                    JOIN mor.ledger_transaction lt ON lt.id = le.transaction_id
                    LEFT JOIN mor.payment p ON p.id = lt.payment_id
                    WHERE le.purpose = ? AND le.currency = ?
                      AND le.settled_by_transaction_id IS NULL
                      AND lt.type <> ?
                      AND lt.created_at < CAST(? AS timestamptz)
                      AND le.occurred_at < CAST(? AS timestamptz)
                      AND (p.payment_time IS NULL OR p.payment_time < CAST(? AS timestamptz))
                    FOR UPDATE OF le
                ), totals AS (
                    SELECT merchant_id, transaction_id, -sum(amount) AS amount
                    FROM eligible
                    GROUP BY merchant_id, transaction_id
                ), settled AS (
                    UPDATE mor.ledger_entry le
                    SET settled_by_transaction_id = t.transaction_id
                    FROM eligible e
                    JOIN totals t USING (merchant_id, transaction_id)
                    WHERE le.id = e.id AND t.amount > 0
                    RETURNING le.purpose_key::uuid AS merchant_id,
                              le.settled_by_transaction_id AS transaction_id,
                              le.amount
                ), actual AS (
                    SELECT merchant_id, transaction_id, -sum(amount) AS amount
                    FROM settled
                    GROUP BY merchant_id, transaction_id
                ), updated_payouts AS (
                    UPDATE mor.payout p
                    SET amount = r.existing_amount + a.amount
                    FROM actual a
                    JOIN reserved r USING (merchant_id, transaction_id)
                    WHERE p.merchant_id = a.merchant_id
                      AND p.payout_date = CAST(? AS date)
                      AND p.ledger_transaction_id = a.transaction_id
                    RETURNING p.merchant_id, p.ledger_transaction_id,
                              a.amount AS computed_amount
                ), inserted_entries AS (
                    INSERT INTO mor.ledger_entry (
                        transaction_id, purpose, purpose_key, amount, currency, occurred_at
                    )
                    SELECT p.ledger_transaction_id, entry.purpose, entry.purpose_key,
                           entry.amount, '${Currency.EUR.name}', clock_timestamp()
                    FROM updated_payouts p
                    CROSS JOIN LATERAL (
                        VALUES
                            (CAST(? AS smallint), p.merchant_id::text, p.computed_amount),
                            (CAST(? AS smallint), NULL::text, -p.computed_amount)
                    ) AS entry(purpose, purpose_key, amount)
                    RETURNING transaction_id
                )
                SELECT merchant_id, ledger_transaction_id, computed_amount
                FROM updated_payouts
                ORDER BY merchant_id
                """.trimIndent(),
                *bindings.toTypedArray(),
            ).fetch()

            val computedTransactionIds = computedRows
                .mapNotNull { it.get("ledger_transaction_id", UUID::class.java) }
                .toSet()
            val unusedReservedIds = inserted.map { it.second }.filterNot(computedTransactionIds::contains)
            if (unusedReservedIds.isNotEmpty()) {
                db.deleteFrom(PAYOUT)
                    .where(PAYOUT.LEDGER_TRANSACTION_ID.`in`(unusedReservedIds))
                    .execute()
            }

            val unusedTransactionIds = inputs.map { it.second }.filterNot(computedTransactionIds::contains)
            if (unusedTransactionIds.isNotEmpty()) {
                db.deleteFrom(LEDGER_TRANSACTION)
                    .where(LEDGER_TRANSACTION.ID.`in`(unusedTransactionIds))
                    .execute()
            }

            val computedAmounts = computedRows.associate {
                it.get("merchant_id", UUID::class.java)!! to
                    it.get("computed_amount", BigDecimal::class.java)!!
            }
            candidates.mapNotNull { candidate ->
                computedAmounts[candidate.merchantId]?.let { candidate.copy(amount = it) }
            }
        }

        log.info("Got ${result.size} results")
        result
    }

    private data class ReservedPayout(
        val merchantId: UUID,
        val transactionId: UUID,
        val existingAmount: BigDecimal,
    )

    override suspend fun due(limit: Int): List<DueTransfer> = io {
        if (limit !in 1..Constants.Jobs.MAX_BATCH_SIZE) return@io emptyList()

        val result = dsl.transactionResult { cfg ->
            DSL.using(cfg).resultQuery(
                """
            WITH candidates AS (
                SELECT p.merchant_id, p.payout_date
                FROM mor.payout p
                JOIN mor.merchant_payment_details d ON d.merchant_id = p.merchant_id
                WHERE p.status = ${PayoutStatus.COMPUTED.id}
                   OR (p.status = ${PayoutStatus.PROCESSING.id} AND p.claimed_at < now() - make_interval(mins => ${Constants.Jobs.CLAIM_TIMEOUT_MINUTES}))
                ORDER BY p.payout_date, p.merchant_id
                FOR UPDATE OF p SKIP LOCKED
                LIMIT ?
            ), claimed AS (
                UPDATE mor.payout p
                SET status = ${PayoutStatus.PROCESSING.id}, claimed_at = clock_timestamp()
                FROM candidates c
                WHERE p.merchant_id = c.merchant_id AND p.payout_date = c.payout_date
                RETURNING p.merchant_id, p.payout_date, p.amount, p.claimed_at
            )
            SELECT c.merchant_id, c.payout_date, d.psp_account_id, c.amount, c.claimed_at
            FROM claimed c
            JOIN mor.merchant_payment_details d ON d.merchant_id = c.merchant_id
            ORDER BY c.payout_date, c.merchant_id
            """.trimIndent(),
                limit,
            ).fetch().map { row ->
                DueTransfer(
                    kind = TransferKind.PAYOUT,
                    key = row.get(0, UUID::class.java)!!.toString(),
                    period = row.get(1, LocalDate::class.java)!!,
                    destination = row.get(2, String::class.java)!!.sensitive(),
                    amount = row.get(3, BigDecimal::class.java)!!,
                    claimedAt = row.get(4, OffsetDateTime::class.java)!!,
                )
            }
        }

        log.info("Got ${result.size} results")
        result
    }

    override suspend fun markSent(transfers: List<SentTransfer>): Int = io {
        if (transfers.isEmpty()) return@io 0
        val values = transfers.joinToString(", ") {
            "(CAST(? AS uuid), CAST(? AS date), CAST(? AS timestamptz), CAST(? AS text))"
        }
        val bindings = buildList<Any> {
            transfers.forEach {
                addAll(listOf(
                    it.transfer.key,
                    it.transfer.period,
                    it.transfer.claimedAt,
                    it.externalReference,
                ))
            }
        }
        dsl.transactionResult { cfg ->
            DSL.using(cfg).execute(
                """
                WITH sent(merchant_id, payout_date, claimed_at, external_reference) AS (VALUES $values)
                UPDATE mor.payout p
                SET status = ${PayoutStatus.SENT.id}, psp_reference = s.external_reference
                FROM sent s
                WHERE p.merchant_id = s.merchant_id
                  AND p.payout_date = s.payout_date
                  AND p.status = ${PayoutStatus.PROCESSING.id}
                  AND p.claimed_at = s.claimed_at
                """.trimIndent(),
                *bindings.toTypedArray(),
            )
        }
    }

    override suspend fun release(transfers: List<DueTransfer>): Int = io {
        if (transfers.isEmpty()) return@io 0
        val values = transfers.joinToString(", ") {
            "(CAST(? AS uuid), CAST(? AS date), CAST(? AS timestamptz))"
        }
        val bindings = buildList<Any> {
            transfers.forEach { addAll(listOf(it.key, it.period, it.claimedAt)) }
        }
        dsl.transactionResult { cfg ->
            DSL.using(cfg).execute(
                """
                WITH released(merchant_id, payout_date, claimed_at) AS (VALUES $values)
                UPDATE mor.payout p
                SET status = ${PayoutStatus.COMPUTED.id}, claimed_at = NULL
                FROM released r
                WHERE p.merchant_id = r.merchant_id
                  AND p.payout_date = r.payout_date
                  AND p.status = ${PayoutStatus.PROCESSING.id}
                  AND p.claimed_at = r.claimed_at
                """.trimIndent(),
                *bindings.toTypedArray(),
            )
        }
    }
}
