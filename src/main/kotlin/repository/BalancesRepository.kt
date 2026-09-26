package org.example.repository

import org.example.balances.BalancesStore
import org.example.balances.MerchantBalanceView
import org.example.balances.TaxBalance
import org.example.db.io
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.MERCHANT
import org.example.jooq.tables.references.MERCHANT_DAILY_BALANCE
import org.example.model.enums.PaymentPurpose
import org.jooq.Condition
import org.jooq.DSLContext
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
 */
class BalancesRepository(private val dsl: DSLContext) : BalancesStore {

    override suspend fun taxBalance(country: String, from: Instant?, to: Instant): TaxBalance = io {
        val owedAtEnd = owed(PaymentPurpose.TAX, country, before(to))
        val owedAtStart = from?.let { owed(PaymentPurpose.TAX, country, before(it)) } ?: BigDecimal.ZERO

        TaxBalance(
            country = country,
            from = from,
            to = to,
            owedAtStart = owedAtStart,
            movement = owedAtEnd - owedAtStart,
            owedAtEnd = owedAtEnd,
        )
    }

    override suspend fun merchantBalances(merchantIds: List<UUID>): List<MerchantBalanceView> = io {
        val keys = merchantIds.map { it.toString() }

        val available = owedByKey(PaymentPurpose.MERCHANT, keys)
        val held = owedByKey(PaymentPurpose.HELD, keys)

        val names = dsl.select(MERCHANT.ID, MERCHANT.NAME)
            .from(MERCHANT)
            .where(if (keys.isEmpty()) DSL.noCondition() else MERCHANT.ID.`in`(merchantIds))
            .fetch()
            .associate { it.value1()!! to it.value2()!! }

        (available.keys + held.keys + names.keys.map { it.toString() })
            .distinct()
            .map { key ->
                val id = UUID.fromString(key)
                MerchantBalanceView(
                    merchantId = id,
                    merchantName = names[id] ?: "unknown merchant",
                    available = available[key] ?: BigDecimal.ZERO,
                    held = held[key] ?: BigDecimal.ZERO,
                )
            }
            .sortedBy { it.merchantName }
    }

    /**
     * Read straight from the nightly snapshot: for a day the close job ran,
     * this is the number it acted on, which is what a dispute is about.
     * Held is not snapshotted, so it is absent here.
     */
    override suspend fun merchantBalancesOn(
        merchantIds: List<UUID>,
        date: LocalDate,
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
            .fetch()
            .map {
                MerchantBalanceView(
                    merchantId = it.value1()!!,
                    merchantName = it.value2() ?: "unknown merchant",
                    available = it.value3()!!,
                    held = null,
                )
            }
            .sortedBy { it.merchantName }
    }

    private fun owed(purpose: PaymentPurpose, key: String, upTo: Condition): BigDecimal =
        dsl.select(DSL.sum(LEDGER_ENTRY.AMOUNT).neg())
            .from(LEDGER_ENTRY)
            .where(LEDGER_ENTRY.PURPOSE.eq(purpose.id))
            .and(LEDGER_ENTRY.PURPOSE_KEY.eq(key))
            .and(upTo)
            .fetchOne(0, BigDecimal::class.java)
            ?: BigDecimal.ZERO

    private fun owedByKey(purpose: PaymentPurpose, keys: List<String>): Map<String, BigDecimal> =
        dsl.select(LEDGER_ENTRY.PURPOSE_KEY, DSL.sum(LEDGER_ENTRY.AMOUNT).neg())
            .from(LEDGER_ENTRY)
            .where(LEDGER_ENTRY.PURPOSE.eq(purpose.id))
            .and(LEDGER_ENTRY.PURPOSE_KEY.isNotNull)
            .and(if (keys.isEmpty()) DSL.noCondition() else LEDGER_ENTRY.PURPOSE_KEY.`in`(keys))
            .groupBy(LEDGER_ENTRY.PURPOSE_KEY)
            .fetch()
            .associate { it.value1()!! to it.value2()!! }

    private fun before(instant: Instant): Condition =
        LEDGER_ENTRY.OCCURRED_AT.lt(instant.atOffset(ZoneOffset.UTC))
}
