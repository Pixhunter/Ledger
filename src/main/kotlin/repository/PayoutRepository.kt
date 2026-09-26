package org.example.repository

import org.example.db.io
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.LEDGER_TRANSACTION
import org.example.jooq.tables.references.MERCHANT
import org.example.jooq.tables.references.MERCHANT_DAILY_BALANCE
import org.example.jooq.tables.references.MERCHANT_PAYMENT_DETAILS
import org.example.jooq.tables.references.PAYMENT
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
        val owed = DSL.sum(LEDGER_ENTRY.AMOUNT).neg()

        val owedByMerchant = dsl.select(LEDGER_ENTRY.PURPOSE_KEY, owed)
            .from(LEDGER_ENTRY)
            .where(LEDGER_ENTRY.PURPOSE.eq(PaymentPurpose.MERCHANT.id))
            .and(LEDGER_ENTRY.PURPOSE_KEY.isNotNull)
            .groupBy(LEDGER_ENTRY.PURPOSE_KEY)
            .fetch()
            .associate { UUID.fromString(it.value1()!!) to it.value2()!! }

        val payments = dsl.select(PAYMENT.MERCHANT_ID, DSL.count())
            .from(PAYMENT)
            .where(PAYMENT.MERCHANT_ID.isNotNull)
            .groupBy(PAYMENT.MERCHANT_ID)
            .fetch()
            .associate { it.value1()!! to it.value2() }

        val names = dsl.select(MERCHANT.ID, MERCHANT.NAME)
            .from(MERCHANT)
            .fetch()
            .associate { it.value1()!! to it.value2()!! }

        owedByMerchant.map { (merchantId, amount) ->
            MerchantBalance(
                merchantId = merchantId,
                merchantName = names[merchantId] ?: "unknown merchant",
                amount = amount,
                payments = payments[merchantId] ?: 0,
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

    override suspend fun consecutiveNegativeDays(merchantId: UUID, balanceDate: LocalDate): Int = io {
        dsl.select(MERCHANT_DAILY_BALANCE.BALANCE)
            .from(MERCHANT_DAILY_BALANCE)
            .where(MERCHANT_DAILY_BALANCE.MERCHANT_ID.eq(merchantId))
            .and(MERCHANT_DAILY_BALANCE.BALANCE_DATE.le(balanceDate))
            .orderBy(MERCHANT_DAILY_BALANCE.BALANCE_DATE.desc())
            .fetch(MERCHANT_DAILY_BALANCE.BALANCE)
            .takeWhile { it!!.signum() < 0 }
            .size
    }

    override suspend fun computePayout(balance: MerchantBalance, payoutDate: LocalDate): Boolean = io {
        runCatching {
            dsl.transactionResult { cfg ->
                val db = DSL.using(cfg)
                val transactionId = UUID.randomUUID()

                db.insertInto(LEDGER_TRANSACTION)
                    .set(LEDGER_TRANSACTION.ID, transactionId)
                    .set(LEDGER_TRANSACTION.TYPE, LedgerTransactionType.PAYOUT.id)
                    .execute()

                val claimed = db.insertInto(PAYOUT)
                    .set(PAYOUT.MERCHANT_ID, balance.merchantId)
                    .set(PAYOUT.PAYOUT_DATE, payoutDate)
                    .set(PAYOUT.AMOUNT, balance.amount)
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
                        balance.merchantId.toString(), balance.amount, Currency.EUR.name,
                    )
                    .values(
                        transactionId, PaymentPurpose.PSP.id,
                        null, balance.amount.negate(), Currency.EUR.name,
                    )
                    .execute()

                true
            }
        }.getOrElse { e -> if (e is AlreadyComputed) false else throw e }
    }

    override suspend fun due(status: PayoutStatus): List<DuePayout> = io {
        dsl.select(
            PAYOUT.MERCHANT_ID,
            PAYOUT.PAYOUT_DATE,
            MERCHANT_PAYMENT_DETAILS.PSP_ACCOUNT_ID,
            PAYOUT.AMOUNT,
        )
            .from(PAYOUT)
            .join(MERCHANT_PAYMENT_DETAILS)
            .on(MERCHANT_PAYMENT_DETAILS.MERCHANT_ID.eq(PAYOUT.MERCHANT_ID))
            .where(PAYOUT.STATUS.eq(status.id))
            .orderBy(PAYOUT.PAYOUT_DATE)
            .fetch()
            .map { DuePayout(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!) }
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
                .execute()
        }
    }

    private class AlreadyComputed : RuntimeException()
}
