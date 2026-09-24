package org.example.repository

import org.example.db.Mapper
import org.example.db.io
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.LEDGER_TRANSACTION
import org.example.jooq.tables.references.PAYMENT
import org.example.jooq.tables.references.REFUND
import org.example.model.LedgerEntry
import org.example.model.PaymentEntity
import org.example.model.RefundEntity
import org.example.model.LedgerWrite
import org.example.model.enumById
import org.example.model.enums.Currency
import org.example.model.enums.HoldReason
import org.example.model.enums.LedgerTransactionType
import org.example.model.enums.PaymentStatus
import org.example.model.enums.RefundReason
import org.example.model.enums.TaxCategory
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.util.UUID

class RefundRepository(private val dsl: DSLContext) : RefundStore {

    private val log = LoggerFactory.getLogger(RefundRepository::class.java)

    override suspend fun findPayment(pspReference: String): PaymentEntity? = io {
        dsl.selectFrom(PAYMENT)
            .where(PAYMENT.PSP_REFERENCE.eq(pspReference))
            .fetchOne()
            ?.let { row ->
                PaymentEntity(
                    id = row.id,
                    pspReference = row.pspReference,
                    merchantId = row.merchantId,
                    gross = row.gross,
                    tax = row.tax,
                    fee = row.fee,
                    merchantNet = row.merchantNet,
                    currency = currency(row.currency, row.pspReference),
                    taxCountry = row.taxCountry,
                    taxCategory = row.taxCategory?.let { enumById<TaxCategory>(it) },
                    taxRateBps = row.taxRateBps,
                    reverseCharge = row.reverseCharge ?: false,
                    evidence = Mapper.fromJsonb<Map<String, String?>>(row.evidence) ?: emptyMap(),
                    status = enumById<PaymentStatus>(row.status),
                    holdReason = row.holdReason?.let { enumById<HoldReason>(it) },
                    paymentTime = row.paymentTime.toInstant(),
                )
            }
    }

    override suspend fun insert(
        refund: RefundEntity,
        entries: List<LedgerEntry>,
    ): LedgerWrite = io {
        entries.groupBy { it.currency }.forEach { (currency, group) ->
            val sum = group.sumOf { it.amount }
            require(sum == 0L) { "entries for $currency do not sum to zero: $sum" }
        }

        dsl.transactionResult { cfg ->
            val db = DSL.using(cfg)

            val inserted: UUID? = db
                .insertInto(REFUND)
                .set(REFUND.ID, refund.id)
                .set(REFUND.REFUND_REFERENCE, refund.refundReference)
                .set(REFUND.PAYMENT_ID, refund.paymentId)
                .set(REFUND.AMOUNT, refund.amount)
                .set(REFUND.CURRENCY, refund.currency.name)
                .set(REFUND.REASON, refund.reason.id)
                .set(REFUND.FEE_RETURNED, refund.feeReturned)
                .set(REFUND.REFUNDED_AT, refund.refundedAt.atOffset(java.time.ZoneOffset.UTC))
                .onConflict(REFUND.REFUND_REFERENCE)
                .doNothing()
                .returningResult(REFUND.ID)
                .fetchOne()
                ?.value1()

            if (inserted == null) {
                val stored = storedRefund(db, refund.refundReference)

                if (stored.paymentId != refund.paymentId ||
                    stored.amount != refund.amount ||
                    stored.currency != refund.currency
                ) {
                    return@transactionResult LedgerWrite.Conflict(
                        "stored ${stored.amount} ${stored.currency} on payment ${stored.paymentId}"
                    )
                }

                log.info("replay of refund {}, nothing written", refund.refundReference)
                return@transactionResult LedgerWrite.Duplicate(paymentStatus(db, refund.paymentId))
            }

            val gross = paymentGross(db, refund.paymentId)
            val refundedSoFar = refundedSoFar(db, refund.paymentId)

            val transactionId = UUID.randomUUID()

            db.insertInto(LEDGER_TRANSACTION)
                .set(LEDGER_TRANSACTION.ID, transactionId)
                .set(LEDGER_TRANSACTION.TYPE, LedgerTransactionType.REFUND.id)
                .set(LEDGER_TRANSACTION.PAYMENT_ID, refund.paymentId)
                .execute()

            val lines = db.insertInto(
                LEDGER_ENTRY,
                LEDGER_ENTRY.TRANSACTION_ID,
                LEDGER_ENTRY.PURPOSE,
                LEDGER_ENTRY.PURPOSE_KEY,
                LEDGER_ENTRY.AMOUNT,
                LEDGER_ENTRY.CURRENCY,
            )

            entries.forEach { e ->
                lines.values(transactionId, e.purpose.id, e.purposeKey, e.amount, e.currency.name)
            }

            lines.execute()

            val status =
                if (refundedSoFar >= gross) PaymentStatus.REFUNDED
                else PaymentStatus.PARTIALLY_REFUNDED

            db.update(PAYMENT)
                .set(PAYMENT.STATUS, status.id)
                .where(PAYMENT.ID.eq(refund.paymentId))
                .execute()

            log.info("stored refund {} as {}", refund.refundReference, status)

            if (refundedSoFar > gross) {
                LedgerWrite.RecordedOverRefund(status, refundedSoFar, gross)
            } else {
                LedgerWrite.Inserted(status)
            }
        }
    }

    private fun paymentGross(db: DSLContext, paymentId: UUID): Long =
        db.select(PAYMENT.GROSS)
            .from(PAYMENT)
            .where(PAYMENT.ID.eq(paymentId))
            .fetchOne(PAYMENT.GROSS)
            ?: error("payment $paymentId disappeared inside its own transaction")

    private fun refundedSoFar(db: DSLContext, paymentId: UUID): Long =
        db.select(DSL.sum(REFUND.AMOUNT))
            .from(REFUND)
            .where(REFUND.PAYMENT_ID.eq(paymentId))
            .fetchOne(0, Long::class.java)
            ?: 0L

    private fun storedRefund(db: DSLContext, reference: String): RefundEntity =
        db.selectFrom(REFUND)
            .where(REFUND.REFUND_REFERENCE.eq(reference))
            .fetchOne()
            ?.let { row ->
                RefundEntity(
                    id = row.id,
                    refundReference = row.refundReference,
                    paymentId = row.paymentId,
                    amount = row.amount,
                    currency = currency(row.currency, row.refundReference),
                    reason = enumById<RefundReason>(row.reason),
                    feeReturned = row.feeReturned,
                    refundedAt = row.refundedAt.toInstant(),
                )
            }
            ?: error("refund $reference conflicted but cannot be read back")

    private fun currency(code: String, reference: String): Currency =
        Currency.byCode(code) ?: error("$reference stored in unsupported currency '$code'")

    private fun paymentStatus(db: DSLContext, paymentId: UUID): PaymentStatus =
        db.select(PAYMENT.STATUS)
            .from(PAYMENT)
            .where(PAYMENT.ID.eq(paymentId))
            .fetchOne(PAYMENT.STATUS)
            ?.let { enumById<PaymentStatus>(it) }
            ?: error("payment $paymentId not found for a stored refund")
}
