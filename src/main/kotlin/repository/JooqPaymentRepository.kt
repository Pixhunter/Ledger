package org.example.repository

import org.example.db.Mapper
import org.example.db.io
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.LEDGER_TRANSACTION
import org.example.jooq.tables.references.PAYMENT
import org.example.ledger.PaymentWrite
import org.example.ledger.LedgerEntry
import org.example.ledger.LedgerTransactionType
import org.example.ledger.PaymentRepository
import org.example.repository.model.PaymentEntity
import org.example.model.enums.HoldReason
import org.example.model.enumById
import org.example.model.enums.PaymentStatus
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.time.ZoneOffset
import java.util.UUID

class JooqPaymentRepository(private val dsl: DSLContext) : PaymentRepository {

    private val log = LoggerFactory.getLogger(JooqPaymentRepository::class.java)

    override suspend fun insert(
        payment: PaymentEntity,
        entries: List<LedgerEntry>,
    ): PaymentWrite = io {
        // The invariant, checked before it reaches the database. Per currency,
        // because summing across currencies is meaningless. A table CHECK
        // cannot see sibling rows, so this is where it lives.
        entries.groupBy { it.currency }.forEach { (currency, group) ->
            val sum = group.sumOf { it.amount }
            require(sum == 0L) { "entries for $currency do not sum to zero: $sum" }
        }

        dsl.transactionResult { cfg ->
            val db = DSL.using(cfg)

            // ON CONFLICT DO NOTHING rather than SELECT-then-INSERT: under
            // concurrent PSP retries the read-then-write version lets two
            // callers both see "not there" and both insert. The unique index
            // cannot be raced.
            val inserted: UUID? = db
                .insertInto(PAYMENT)
                .set(PAYMENT.ID, payment.id)
                .set(PAYMENT.PSP_REFERENCE, payment.pspReference)
                .set(PAYMENT.MERCHANT_ID, payment.merchantId)
                .set(PAYMENT.GROSS, payment.gross)
                .set(PAYMENT.TAX, payment.tax)
                .set(PAYMENT.FEE, payment.fee)
                .set(PAYMENT.MERCHANT_NET, payment.merchantNet)
                .set(PAYMENT.CURRENCY, payment.currency.name)
                .set(PAYMENT.TAX_COUNTRY, payment.taxCountry)
                .set(PAYMENT.TAX_CATEGORY, payment.taxCategory?.id)
                .set(PAYMENT.TAX_RATE_BPS, payment.taxRateBps)
                .set(PAYMENT.REVERSE_CHARGE, payment.reverseCharge)
                .set(PAYMENT.EVIDENCE, Mapper.toJsonb(payment.evidence))
                .set(PAYMENT.STATUS, payment.status.id)
                .set(PAYMENT.HOLD_REASON, payment.holdReason?.id)
                .set(PAYMENT.CAPTURED_AT, payment.capturedAt.atOffset(ZoneOffset.UTC))
                .onConflict(PAYMENT.PSP_REFERENCE)
                .doNothing()
                .returningResult(PAYMENT.ID)
                .fetchOne()
                ?.value1()

            if (inserted == null) {
                // Replay. Read back what we decided the first time and answer
                // identically - the PSP must never get two different answers
                // for one payment.
                val existing = db
                    .select(PAYMENT.STATUS, PAYMENT.HOLD_REASON)
                    .from(PAYMENT)
                    .where(PAYMENT.PSP_REFERENCE.eq(payment.pspReference))
                    .fetchOne()
                    ?: error("psp_reference ${payment.pspReference} conflicted but cannot be read back")

                log.info("replay of {}, nothing written", payment.pspReference)

                return@transactionResult PaymentWrite.Duplicate(
                    status = enumById<PaymentStatus>(existing[PAYMENT.STATUS]!!),
                    holdReason = existing[PAYMENT.HOLD_REASON]?.let { enumById<HoldReason>(it) },
                )
            }

            // One money event, then its lines. Type and payment id live on the
            // transaction so they are not repeated on every entry.
            val transactionId = UUID.randomUUID()

            db.insertInto(LEDGER_TRANSACTION)
                .set(LEDGER_TRANSACTION.ID, transactionId)
                .set(LEDGER_TRANSACTION.TYPE, LedgerTransactionType.CAPTURE.id)
                .set(LEDGER_TRANSACTION.PAYMENT_ID, payment.id)
                .execute()

            // One statement for all lines. Built without reassigning the
            // step: jOOQ's values() mutates and returns the same builder, and
            // reassigning a var here makes Kotlin fall back to the
            // values(Field...) overload with a baffling error.
            val insert = db.insertInto(
                LEDGER_ENTRY,
                LEDGER_ENTRY.TRANSACTION_ID,
                LEDGER_ENTRY.ACCOUNT_TYPE,
                LEDGER_ENTRY.ACCOUNT_KEY,
                LEDGER_ENTRY.AMOUNT,
                LEDGER_ENTRY.CURRENCY,
            )

            entries.forEach { e ->
                insert.values(
                    transactionId,
                    e.accountType.id,
                    e.accountKey,
                    e.amount,
                    e.currency.name,
                )
            }

            insert.execute()

            log.info(
                "stored {} as {}{}",
                payment.pspReference,
                payment.status,
                payment.holdReason?.let { " ($it)" } ?: "",
            )

            PaymentWrite.Inserted
        }
    }
}
