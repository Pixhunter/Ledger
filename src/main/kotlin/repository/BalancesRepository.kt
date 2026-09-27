package org.example.repository

import org.example.model.MerchantBalanceView
import org.example.model.TaxBalance
import org.example.api.io
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.MERCHANT
import org.example.jooq.tables.references.MERCHANT_DAILY_BALANCE
import org.example.model.Money
import org.example.model.enums.PaymentPurpose
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Balances are SUMs over ledger_entry, which carries its own occurred_at, so
 * every query below is one index scan on
 * (purpose, purpose_key, currency, occurred_at) with no join.
 *
 * Entries are signed - a credit is negative - so what we owe is the negated sum.
 *
 * Every report answers from one database snapshot. A report built from several
 * statements at READ COMMITTED can straddle a concurrent capture and report a
 * movement that matches neither endpoint, so a report is either a single
 * statement (aggregate FILTERs instead of repeated scans) or a REPEATABLE READ
 * READ ONLY transaction.
 *
 * Merchant reads are keyset pages ordered by merchant id - never the whole
 * ledger - so response size and memory stay bounded by the page limit.
 *
 * Both reports cut off at an instant. occurred_at is the tax point, which the
 * PSP may date up to the accepted drift into the future, so without a cutoff a
 * capture would read as available while the payout job correctly withholds it.
 */
class BalancesRepository(private val dsl: DSLContext) {

    suspend fun taxBalance(country: String, from: Instant?, to: Instant): TaxBalance = io {
        val row = dsl.select(owedAt(to), from?.let { owedAt(it) } ?: DSL.inline(Money.ZERO))
            .from(LEDGER_ENTRY)
            .where(LEDGER_ENTRY.PURPOSE.eq(PaymentPurpose.TAX.id))
            .and(LEDGER_ENTRY.PURPOSE_KEY.eq(country))
            .fetchOne()

        val owedAtEnd = row?.value1() ?: Money.ZERO
        val owedAtStart = row?.value2() ?: Money.ZERO

        TaxBalance(
            country = country,
            from = from,
            to = to,
            owedAtStart = owedAtStart,
            movement = owedAtEnd - owedAtStart,
            owedAtEnd = owedAtEnd,
        )
    }

    /**
     * Live balance from the ledger, one keyset page ordered by merchant id.
     * Entries dated after [asOf] are excluded: a capture booked with a future
     * tax point is not payable yet, and the payout job already waits for it.
     */
    suspend fun merchantBalances(
        merchantIds: List<UUID>,
        asOf: Instant,
        after: UUID?,
        limit: Int,
    ): List<MerchantBalanceView> = io {
        snapshot { db ->
            val keys = merchantIds.map { it.toString() }

            val page = db.select(
                LEDGER_ENTRY.PURPOSE_KEY,
                owedFor(PaymentPurpose.MERCHANT),
                owedFor(PaymentPurpose.HELD),
            )
                .from(LEDGER_ENTRY)
                .where(LEDGER_ENTRY.PURPOSE.`in`(PaymentPurpose.MERCHANT.id, PaymentPurpose.HELD.id))
                .and(LEDGER_ENTRY.PURPOSE_KEY.isNotNull)
                .and(before(asOf))
                .and(if (keys.isEmpty()) DSL.noCondition() else LEDGER_ENTRY.PURPOSE_KEY.`in`(keys))
                .and(after?.let { LEDGER_ENTRY.PURPOSE_KEY.gt(it.toString()) } ?: DSL.noCondition())
                .groupBy(LEDGER_ENTRY.PURPOSE_KEY)
                .orderBy(LEDGER_ENTRY.PURPOSE_KEY)
                .limit(limit)
                .fetch()

            val ids = page.map { UUID.fromString(it.value1()!!) }
            val names = if (ids.isEmpty()) emptyMap() else db.select(MERCHANT.ID, MERCHANT.NAME)
                .from(MERCHANT)
                .where(MERCHANT.ID.`in`(ids))
                .fetch()
                .associate { it.value1()!! to it.value2()!! }

            page.map {
                val id = UUID.fromString(it.value1()!!)
                MerchantBalanceView(
                    merchantId = id,
                    merchantName = names[id] ?: "unknown merchant",
                    available = it.value2() ?: Money.ZERO,
                    held = it.value3() ?: Money.ZERO,
                )
            }
        }
    }

    /**
     * Read straight from the nightly snapshot: for a day the close job ran,
     * this is the number it acted on, which is what a dispute is about.
     * Held is not snapshotted, so it is absent here. Empty when the job did
     * not run that day.
     */
    suspend fun merchantBalancesOn(
        merchantIds: List<UUID>,
        date: LocalDate,
        after: UUID?,
        limit: Int,
    ): List<MerchantBalanceView> = io {
        dsl.select(
            MERCHANT_DAILY_BALANCE.MERCHANT_ID,
            MERCHANT.NAME,
            MERCHANT_DAILY_BALANCE.BALANCE,
        )
            .from(MERCHANT_DAILY_BALANCE)
            .leftJoin(MERCHANT).on(MERCHANT.ID.eq(MERCHANT_DAILY_BALANCE.MERCHANT_ID))
            .where(MERCHANT_DAILY_BALANCE.BALANCE_DATE.eq(date))
            .and(if (merchantIds.isEmpty()) DSL.noCondition() else MERCHANT_DAILY_BALANCE.MERCHANT_ID.`in`(merchantIds))
            .and(after?.let { MERCHANT_DAILY_BALANCE.MERCHANT_ID.gt(it) } ?: DSL.noCondition())
            .orderBy(MERCHANT_DAILY_BALANCE.MERCHANT_ID)
            .limit(limit)
            .fetch()
            .map {
                MerchantBalanceView(
                    merchantId = it.value1()!!,
                    merchantName = it.value2() ?: "unknown merchant",
                    available = it.value3()!!,
                    held = null,
                )
            }
    }

    private fun <T> snapshot(block: (DSLContext) -> T): T =
        dsl.transactionResult { cfg ->
            val db = DSL.using(cfg)
            db.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY")
            block(db)
        }

    private fun owedAt(instant: Instant): Field<BigDecimal> =
        DSL.sum(LEDGER_ENTRY.AMOUNT.neg()).filterWhere(before(instant))

    private fun owedFor(purpose: PaymentPurpose): Field<BigDecimal> =
        DSL.sum(LEDGER_ENTRY.AMOUNT.neg()).filterWhere(LEDGER_ENTRY.PURPOSE.eq(purpose.id))

    private fun before(instant: Instant): Condition =
        LEDGER_ENTRY.OCCURRED_AT.lt(instant.atOffset(ZoneOffset.UTC))
}
