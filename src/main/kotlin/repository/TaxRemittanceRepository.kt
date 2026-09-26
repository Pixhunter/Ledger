package org.example.repository

import org.example.db.io
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.LEDGER_TRANSACTION
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
import java.time.LocalDate
import java.util.UUID

class TaxRemittanceRepository(private val dsl: DSLContext) : TaxRemittanceStore {

    // Entries are signed: tax owed is a credit, so the liability is the negated sum.
    override suspend fun liabilities(): List<TaxLiability> = io {
        val owed = DSL.sum(LEDGER_ENTRY.AMOUNT).neg()

        dsl.select(LEDGER_ENTRY.PURPOSE_KEY, owed)
            .from(LEDGER_ENTRY)
            .where(LEDGER_ENTRY.PURPOSE.eq(PaymentPurpose.TAX.id))
            .and(LEDGER_ENTRY.PURPOSE_KEY.isNotNull)
            .groupBy(LEDGER_ENTRY.PURPOSE_KEY)
            .fetch()
            .map { TaxLiability(it.value1()!!, it.value2()!!) }
    }

    override suspend fun recordDailyBalance(liability: TaxLiability, balanceDate: LocalDate): Unit = io {
        dsl.transaction { cfg ->
            DSL.using(cfg)
                .insertInto(TAX_DAILY_BALANCE)
                .set(TAX_DAILY_BALANCE.COUNTRY, liability.country)
                .set(TAX_DAILY_BALANCE.BALANCE_DATE, balanceDate)
                .set(TAX_DAILY_BALANCE.BALANCE, liability.amount)
                .set(TAX_DAILY_BALANCE.CURRENCY, Currency.EUR.name)
                .onConflict(TAX_DAILY_BALANCE.COUNTRY, TAX_DAILY_BALANCE.BALANCE_DATE)
                .doNothing()
                .execute()
        }
    }

    override suspend fun consecutiveNegativeDays(country: String, balanceDate: LocalDate): Int = io {
        dsl.select(TAX_DAILY_BALANCE.BALANCE)
            .from(TAX_DAILY_BALANCE)
            .where(TAX_DAILY_BALANCE.COUNTRY.eq(country))
            .and(TAX_DAILY_BALANCE.BALANCE_DATE.le(balanceDate))
            .orderBy(TAX_DAILY_BALANCE.BALANCE_DATE.desc())
            .fetch(TAX_DAILY_BALANCE.BALANCE)
            .takeWhile { it!!.signum() < 0 }
            .size
    }

    override suspend fun computeRemittance(liability: TaxLiability, periodStart: LocalDate): Boolean = io {
        runCatching {
            dsl.transactionResult { cfg ->
                val db = DSL.using(cfg)
                val transactionId = UUID.randomUUID()

                db.insertInto(LEDGER_TRANSACTION)
                    .set(LEDGER_TRANSACTION.ID, transactionId)
                    .set(LEDGER_TRANSACTION.TYPE, LedgerTransactionType.PAYOUT.id)
                    .execute()

                val claimed = db.insertInto(TAX_REMITTANCE)
                    .set(TAX_REMITTANCE.COUNTRY, liability.country)
                    .set(TAX_REMITTANCE.PERIOD_START, periodStart)
                    .set(TAX_REMITTANCE.AMOUNT, liability.amount)
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
                        liability.country, liability.amount, Currency.EUR.name,
                    )
                    .values(
                        transactionId, PaymentPurpose.PSP.id,
                        null, liability.amount.negate(), Currency.EUR.name,
                    )
                    .execute()

                true
            }
        }.getOrElse { e -> if (e is AlreadyFiled) false else throw e }
    }

    override suspend fun due(status: PayoutStatus): List<DueRemittance> = io {
        dsl.select(TAX_REMITTANCE.COUNTRY, TAX_REMITTANCE.PERIOD_START, TAX_REMITTANCE.AMOUNT)
            .from(TAX_REMITTANCE)
            .where(TAX_REMITTANCE.STATUS.eq(status.id))
            .orderBy(TAX_REMITTANCE.PERIOD_START)
            .fetch()
            .map { DueRemittance(it.value1()!!, it.value2()!!, it.value3()!!) }
    }

    override suspend fun markSent(country: String, periodStart: LocalDate, reference: String): Unit = io {
        dsl.transaction { cfg ->
            DSL.using(cfg)
                .update(TAX_REMITTANCE)
                .set(TAX_REMITTANCE.STATUS, PayoutStatus.SENT.id)
                .set(TAX_REMITTANCE.REFERENCE, reference)
                .where(TAX_REMITTANCE.COUNTRY.eq(country))
                .and(TAX_REMITTANCE.PERIOD_START.eq(periodStart))
                .execute()
        }
    }

    private class AlreadyFiled : RuntimeException()
}
