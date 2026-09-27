package org.example.repository

import model.DueTransfer
import model.TransferKind
import model.TransferStore
import model.SentTransfer
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.LEDGER_TRANSACTION
import org.example.jooq.tables.references.TAX_DAILY_BALANCE
import org.example.jooq.tables.references.TAX_REMITTANCE
import org.example.model.enums.Currency
import org.example.model.enums.LedgerTransactionType
import org.example.model.enums.PaymentPurpose
import org.example.model.enums.PayoutStatus
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.example.utils.Constants
import org.example.api.randomUuid
import org.example.api.io
import org.example.model.TaxLiability
import org.example.utils.logger
import org.example.utils.sensitive
import repository.store.TaxRemittanceStore

class TaxRemittanceRepository(private val dsl: DSLContext) : TaxRemittanceStore, TransferStore {
    private val log = logger<TaxRemittanceRepository>()

    override suspend fun liabilities(cutoff: Instant): List<TaxLiability> = io {
        log.info("Select tax liability on cutoff=${cutoff}")

        val at = cutoff.atOffset(ZoneOffset.UTC)
        val result = dsl.resultQuery(
            """
            WITH balances AS (
                SELECT le.purpose_key, -sum(le.amount) AS amount
                FROM mor.ledger_entry le
                JOIN mor.ledger_transaction lt ON lt.id = le.transaction_id
                WHERE le.purpose = ? AND le.currency = ? AND le.purpose_key IS NOT NULL
                  AND le.settled_by_transaction_id IS NULL
                  AND lt.type <> ${LedgerTransactionType.PAYOUT.id}
                  AND le.occurred_at < CAST(? AS timestamptz)
                GROUP BY le.purpose_key
            ), payment_stats AS (
                SELECT tax_country, count(*) AS payment_count, max(tax_rate_bps) AS max_rate_bps
                FROM mor.payment
                WHERE tax_country IS NOT NULL AND payment_time < CAST(? AS timestamptz)
                GROUP BY tax_country
            )
            SELECT b.purpose_key AS country,
                   b.amount,
                   COALESCE(s.payment_count, 0) AS payment_count,
                   COALESCE(s.max_rate_bps, 0) AS max_rate_bps
            FROM balances b
            LEFT JOIN payment_stats s ON s.tax_country = b.purpose_key
            """.trimIndent(),
            PaymentPurpose.TAX.id, Currency.EUR.name, at, at,
        )
            .fetch()
            .map { row ->
                val rateBps = row.get("max_rate_bps", Int::class.java) ?: 0
                TaxLiability(
                    country = row.get("country", String::class.java)!!,
                    amount = row.get("amount", BigDecimal::class.java)!!,
                    payments = row.get("payment_count", Long::class.java)?.toInt() ?: 0,
                    ratePercent = BigDecimal(rateBps).divide(BigDecimal(100)),
                )
            }
        log.info("Got ${result.size} results")
        result
    }

    override suspend fun recordDailyBalances(liabilities: List<TaxLiability>, balanceDate: LocalDate): Unit = io {
        if (liabilities.isEmpty()) return@io
        log.info("Record daily balances for date=$balanceDate")

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

    override suspend fun consecutiveNegativeDays(
        countries: Collection<String>,
        balanceDate: LocalDate,
    ): Map<String, Int> = io {
        val keys = countries.distinct()
        if (keys.isEmpty()) return@io emptyMap()
        val values = keys.joinToString(", ") { "(CAST(? AS text))" }
        val bindings = buildList<Any> {
            addAll(keys)
            add(balanceDate)
            add(balanceDate)
        }

        dsl.resultQuery(
            """
            WITH requested(country) AS (VALUES $values),
            last_non_negative AS (
                SELECT b.country, max(b.balance_date) AS balance_date
                FROM mor.tax_daily_balance b
                JOIN requested r USING (country)
                WHERE b.balance_date <= CAST(? AS date) AND b.balance >= 0
                GROUP BY b.country
            )
            SELECT r.country, count(b.balance_date) AS negative_days
            FROM requested r
            LEFT JOIN last_non_negative n USING (country)
            LEFT JOIN mor.tax_daily_balance b
              ON b.country = r.country
             AND b.balance_date <= CAST(? AS date)
             AND b.balance_date > COALESCE(n.balance_date, DATE '-infinity')
             AND b.balance < 0
            GROUP BY r.country
            """.trimIndent(),
            *bindings.toTypedArray(),
        ).fetch().associate { row ->
            row.get("country", String::class.java)!! to
                (row.get("negative_days", Long::class.java)?.toInt() ?: 0)
        }
    }

    override suspend fun computeRemittances(
        liabilities: Collection<TaxLiability>,
        periodStart: LocalDate,
        cutoff: Instant,
    ): Map<String, BigDecimal> = io {
        val candidates = liabilities.filter { it.amount.signum() > 0 }.distinctBy { it.country }
        if (candidates.isEmpty()) return@io emptyMap()

        dsl.transactionResult { cfg ->
            val db = DSL.using(cfg)
            val inputs = candidates.map { it to randomUuid() }

            db.insertInto(
                LEDGER_TRANSACTION,
                LEDGER_TRANSACTION.ID,
                LEDGER_TRANSACTION.TYPE,
            )
                .valuesOfRows(inputs.map { (_, transactionId) ->
                    DSL.row(transactionId, LedgerTransactionType.PAYOUT.id)
                })
                .execute()

            val inserted = db.insertInto(
                TAX_REMITTANCE,
                TAX_REMITTANCE.COUNTRY,
                TAX_REMITTANCE.PERIOD_START,
                TAX_REMITTANCE.AMOUNT,
                TAX_REMITTANCE.CURRENCY,
                TAX_REMITTANCE.LEDGER_TRANSACTION_ID,
                TAX_REMITTANCE.STATUS,
            )
                .valuesOfRows(inputs.map { (liability, transactionId) ->
                    DSL.row(
                        liability.country,
                        periodStart,
                        liability.amount,
                        Currency.EUR.name,
                        transactionId,
                        PayoutStatus.COMPUTED.id,
                    )
                })
                .onConflict(TAX_REMITTANCE.COUNTRY, TAX_REMITTANCE.PERIOD_START)
                .doNothing()
                .returning(TAX_REMITTANCE.COUNTRY, TAX_REMITTANCE.LEDGER_TRANSACTION_ID)
                .fetch()
                .map { it.get(TAX_REMITTANCE.COUNTRY)!! to it.get(TAX_REMITTANCE.LEDGER_TRANSACTION_ID)!! }

            val insertedIds = inserted.mapTo(mutableSetOf()) { it.second }
            val reserved = db.select(
                TAX_REMITTANCE.COUNTRY,
                TAX_REMITTANCE.LEDGER_TRANSACTION_ID,
                TAX_REMITTANCE.AMOUNT,
            )
                .from(TAX_REMITTANCE)
                .where(TAX_REMITTANCE.COUNTRY.`in`(candidates.map { it.country }))
                .and(TAX_REMITTANCE.PERIOD_START.eq(periodStart))
                .and(TAX_REMITTANCE.STATUS.eq(PayoutStatus.COMPUTED.id))
                .forUpdate()
                .fetch()
                .map {
                    ReservedRemittance(
                        country = it.get(TAX_REMITTANCE.COUNTRY)!!,
                        transactionId = it.get(TAX_REMITTANCE.LEDGER_TRANSACTION_ID)!!,
                        existingAmount = if (it.get(TAX_REMITTANCE.LEDGER_TRANSACTION_ID) in insertedIds) {
                            BigDecimal.ZERO
                        } else {
                            it.get(TAX_REMITTANCE.AMOUNT)!!
                        },
                    )
                }

            if (reserved.isEmpty()) {
                db.deleteFrom(LEDGER_TRANSACTION)
                    .where(LEDGER_TRANSACTION.ID.`in`(inputs.map { it.second }))
                    .execute()
                return@transactionResult emptyMap()
            }

            val reservedValues = reserved.joinToString(", ") {
                "(CAST(? AS text), CAST(? AS uuid), CAST(? AS numeric))"
            }
            val bindings = buildList<Any> {
                reserved.forEach {
                    add(it.country)
                    add(it.transactionId)
                    add(it.existingAmount)
                }
                add(PaymentPurpose.TAX.id)
                add(Currency.EUR.name)
                add(LedgerTransactionType.PAYOUT.id)
                add(cutoff.atOffset(ZoneOffset.UTC))
                add(periodStart)
                add(PaymentPurpose.TAX.id)
                add(PaymentPurpose.PSP.id)
            }

            val computedRows = db.resultQuery(
                """
                WITH reserved(country, transaction_id, existing_amount) AS (
                    VALUES $reservedValues
                ), eligible AS MATERIALIZED (
                    SELECT le.id, le.amount, r.country, r.transaction_id
                    FROM mor.ledger_entry le
                    JOIN reserved r ON le.purpose_key = r.country
                    JOIN mor.ledger_transaction lt ON lt.id = le.transaction_id
                    WHERE le.purpose = ? AND le.currency = ?
                      AND le.settled_by_transaction_id IS NULL
                      AND lt.type <> ?
                      AND le.occurred_at < CAST(? AS timestamptz)
                    FOR UPDATE OF le
                ), totals AS (
                    SELECT country, transaction_id, -sum(amount) AS amount
                    FROM eligible
                    GROUP BY country, transaction_id
                ), settled AS (
                    UPDATE mor.ledger_entry le
                    SET settled_by_transaction_id = t.transaction_id
                    FROM eligible e
                    JOIN totals t USING (country, transaction_id)
                    WHERE le.id = e.id AND t.amount > 0
                    RETURNING le.purpose_key AS country,
                              le.settled_by_transaction_id AS transaction_id,
                              le.amount
                ), actual AS (
                    SELECT country, transaction_id, -sum(amount) AS amount
                    FROM settled
                    GROUP BY country, transaction_id
                ), updated_remittances AS (
                    UPDATE mor.tax_remittance r
                    SET amount = x.existing_amount + a.amount
                    FROM actual a
                    JOIN reserved x USING (country, transaction_id)
                    WHERE r.country = a.country
                      AND r.period_start = CAST(? AS date)
                      AND r.ledger_transaction_id = a.transaction_id
                    RETURNING r.country, r.ledger_transaction_id,
                              a.amount AS computed_amount
                ), inserted_entries AS (
                    INSERT INTO mor.ledger_entry (
                        transaction_id, purpose, purpose_key, amount, currency, occurred_at
                    )
                    SELECT r.ledger_transaction_id, entry.purpose, entry.purpose_key,
                           entry.amount, '${Currency.EUR.name}', clock_timestamp()
                    FROM updated_remittances r
                    CROSS JOIN LATERAL (
                        VALUES
                            (CAST(? AS smallint), r.country, r.computed_amount),
                            (CAST(? AS smallint), NULL::text, -r.computed_amount)
                    ) AS entry(purpose, purpose_key, amount)
                    RETURNING transaction_id
                )
                SELECT country, ledger_transaction_id, computed_amount
                FROM updated_remittances
                ORDER BY country
                """.trimIndent(),
                *bindings.toTypedArray(),
            ).fetch()

            val completedIds = computedRows
                .mapNotNull { it.get("ledger_transaction_id", java.util.UUID::class.java) }
                .toSet()
            val unusedReservedIds = inserted.map { it.second }.filterNot(completedIds::contains)
            if (unusedReservedIds.isNotEmpty()) {
                db.deleteFrom(TAX_REMITTANCE)
                    .where(TAX_REMITTANCE.LEDGER_TRANSACTION_ID.`in`(unusedReservedIds))
                    .execute()
            }
            val unusedTransactionIds = inputs.map { it.second }.filterNot(completedIds::contains)
            if (unusedTransactionIds.isNotEmpty()) {
                db.deleteFrom(LEDGER_TRANSACTION)
                    .where(LEDGER_TRANSACTION.ID.`in`(unusedTransactionIds))
                    .execute()
            }

            computedRows.associate { row ->
                row.get("country", String::class.java)!! to
                    row.get("computed_amount", BigDecimal::class.java)!!
            }
        }
    }

    private data class ReservedRemittance(
        val country: String,
        val transactionId: java.util.UUID,
        val existingAmount: BigDecimal,
    )

    override suspend fun due(limit: Int): List<DueTransfer> = io {
        if (limit !in 1..Constants.Jobs.MAX_BATCH_SIZE) return@io emptyList()
        log.info("Update taxes on limit=$limit")

        val result = dsl.transactionResult { cfg ->
            DSL.using(cfg).resultQuery(
                """
            WITH candidates AS (
                SELECT country, period_start
                FROM mor.tax_remittance
                WHERE status = ${PayoutStatus.COMPUTED.id}
                   OR (status = ${PayoutStatus.PROCESSING.id} AND claimed_at < now() - make_interval(mins => ${Constants.Jobs.CLAIM_TIMEOUT_MINUTES}))
                ORDER BY period_start, country
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE mor.tax_remittance r
            SET status = ${PayoutStatus.PROCESSING.id}, claimed_at = clock_timestamp()
            FROM candidates c
            WHERE r.country = c.country AND r.period_start = c.period_start
            RETURNING r.country, r.period_start, r.amount, r.claimed_at
            """.trimIndent(),
                limit,
            ).fetch().map { row ->
                val country = row.get(0, String::class.java)!!
                DueTransfer(
                    kind = TransferKind.TAX,
                    key = country,
                    period = row.get(1, LocalDate::class.java)!!,
                    destination = country.sensitive(),
                    amount = row.get(2, BigDecimal::class.java)!!,
                    claimedAt = row.get(3, OffsetDateTime::class.java)!!,
                )
            }
        }

        log.info("Got ${result.size} results")
        result
    }

    override suspend fun markSent(transfers: List<SentTransfer>): Int = io {
        if (transfers.isEmpty()) return@io 0
        val values = transfers.joinToString(", ") {
            "(CAST(? AS text), CAST(? AS date), CAST(? AS timestamptz), CAST(? AS text))"
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
                WITH sent(country, period_start, claimed_at, external_reference) AS (VALUES $values)
                UPDATE mor.tax_remittance r
                SET status = ${PayoutStatus.SENT.id}, reference = s.external_reference
                FROM sent s
                WHERE r.country = s.country
                  AND r.period_start = s.period_start
                  AND r.status = ${PayoutStatus.PROCESSING.id}
                  AND r.claimed_at = s.claimed_at
                """.trimIndent(),
                *bindings.toTypedArray(),
            )
        }
    }

    override suspend fun release(transfers: List<DueTransfer>): Int = io {
        if (transfers.isEmpty()) return@io 0
        val values = transfers.joinToString(", ") {
            "(CAST(? AS text), CAST(? AS date), CAST(? AS timestamptz))"
        }
        val bindings = buildList<Any> {
            transfers.forEach { addAll(listOf(it.key, it.period, it.claimedAt)) }
        }
        dsl.transactionResult { cfg ->
            DSL.using(cfg).execute(
                """
                WITH released(country, period_start, claimed_at) AS (VALUES $values)
                UPDATE mor.tax_remittance r
                SET status = ${PayoutStatus.COMPUTED.id}, claimed_at = NULL
                FROM released x
                WHERE r.country = x.country
                  AND r.period_start = x.period_start
                  AND r.status = ${PayoutStatus.PROCESSING.id}
                  AND r.claimed_at = x.claimed_at
                """.trimIndent(),
                *bindings.toTypedArray(),
            )
        }
    }

}
