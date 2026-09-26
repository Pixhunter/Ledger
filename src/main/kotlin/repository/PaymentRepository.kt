package org.example.repository

import org.example.db.Mapper
import org.example.db.io
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.LEDGER_TRANSACTION
import org.example.jooq.tables.references.PAYMENT
import org.example.jooq.tables.references.PAYMENT_HOLD
import org.example.model.LedgerWrite
import org.example.model.LedgerEntry
import org.example.model.enums.LedgerTransactionType
import org.example.model.PaymentEntity
import org.example.model.ProcessingError
import org.example.model.enums.EventType
import org.example.model.enums.ProcessingErrorCode
import org.example.model.enumById
import org.example.model.enums.PaymentStatus
import org.example.model.enums.TaxCategory
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.time.ZoneOffset
import java.util.UUID
import java.math.BigDecimal

class PaymentRepository(private val dsl: DSLContext) : PaymentStore {

    private val log = LoggerFactory.getLogger(PaymentRepository::class.java)

    override suspend fun insert(
        payment: PaymentEntity,
        entries: List<LedgerEntry>,
        rawPayload: String,
        errors: List<ProcessingError>,
    ): LedgerWrite = io {
        // The invariant, checked before it reaches the database. Per currency,
        // because summing across currencies is meaningless. A table CHECK
        // cannot see sibling rows, so this is where it lives.
        entries.groupBy { it.currency }.forEach { (currency, group) ->
            val sum = group.fold(BigDecimal.ZERO) { total, entry -> total + entry.amount }
            require(sum.signum() == 0) { "entries for $currency do not sum to zero: $sum" }
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
                .set(PAYMENT.PAYMENT_TIME, payment.paymentTime.atOffset(ZoneOffset.UTC))
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
                    .selectFrom(PAYMENT)
                    .where(PAYMENT.PSP_REFERENCE.eq(payment.pspReference))
                    .fetchOne()
                    ?: error("psp_reference ${payment.pspReference} conflicted but cannot be read back")

                // A replay is only a replay if it says the same thing. The unique
                // index proves the reference was seen, not that the money matches.
                val sameEvent = existing.merchantId == payment.merchantId &&
                        existing.gross.compareTo(payment.gross) == 0 &&
                        existing.tax.compareTo(payment.tax) == 0 &&
                        existing.fee.compareTo(payment.fee) == 0 &&
                        existing.merchantNet.compareTo(payment.merchantNet) == 0 &&
                        existing.currency == payment.currency.name &&
                        existing.taxCountry == payment.taxCountry &&
                        existing.taxCategory?.let { enumById<TaxCategory>(it) } == payment.taxCategory &&
                        existing.taxRateBps == payment.taxRateBps &&
                        (existing.reverseCharge ?: false) == payment.reverseCharge &&
                        Mapper.fromJsonb<Map<String, String?>>(existing.evidence) == payment.evidence &&
                        existing.paymentTime.toInstant() == payment.paymentTime

                if (!sameEvent) {
                    insertProcessingError(
                        db,
                        ProcessingError(
                            id = UUID.randomUUID(),
                            eventType = EventType.CAPTURE,
                            externalReference = payment.pspReference,
                            payload = rawPayload,
                            code = ProcessingErrorCode.IDEMPOTENCY_CONFLICT,
                            detail = "same pspReference received with different immutable payment data",
                        ),
                    )
                    return@transactionResult LedgerWrite.Conflict(
                        "same pspReference received with different immutable payment data"
                    )
                }

                log.info("replay of {}, nothing written", payment.pspReference)

                return@transactionResult LedgerWrite.Duplicate(
                    enumById<PaymentStatus>(existing.status)
                )
            }

            if (payment.holdReasons.isNotEmpty()) {
                val holds = db.insertInto(
                    PAYMENT_HOLD,
                    PAYMENT_HOLD.PAYMENT_ID,
                    PAYMENT_HOLD.REASON,
                )
                payment.holdReasons.forEach { reason ->
                    holds.values(payment.id, reason.id)
                }
                holds.execute()
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
                LEDGER_ENTRY.PURPOSE,
                LEDGER_ENTRY.PURPOSE_KEY,
                LEDGER_ENTRY.AMOUNT,
                LEDGER_ENTRY.CURRENCY,
            )

            entries.forEach { e ->
                insert.values(
                    transactionId,
                    e.purpose.id,
                    e.purposeKey,
                    e.amount,
                    e.currency.name,
                )
            }

            insert.execute()

            insertProcessingErrors(db, errors)

            log.info(
                "stored {} as {}{}",
                payment.pspReference,
                payment.status,
                payment.holdReasons.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "",
            )

            LedgerWrite.Inserted(payment.status)
        }
    }
}
