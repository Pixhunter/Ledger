package org.example.repository

import org.example.database.JsonbMapper
import org.example.jooq.tables.references.LEDGER_ENTRY
import org.example.jooq.tables.references.LEDGER_TRANSACTION
import org.example.jooq.tables.references.PAYMENT
import org.example.jooq.tables.references.PAYMENT_HOLD
import org.example.jooq.tables.references.REFUND
import org.example.model.LedgerEntry
import org.example.model.PaymentEntity
import org.example.model.RefundEntity
import org.example.model.LedgerWrite
import org.example.model.ProcessingError
import org.example.model.enums.EventType
import org.example.model.enums.ProcessingErrorCode
import model.enums.enumById
import org.example.model.enums.Currency
import org.example.model.enums.HoldReason
import org.example.model.enums.LedgerTransactionType
import org.example.model.enums.PaymentStatus
import org.example.model.enums.RefundReason
import org.example.model.enums.TaxCategory
import org.example.api.io
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID
import java.math.BigDecimal
import java.time.ZoneOffset
import org.example.api.randomUuid
import org.example.utils.logger

class RefundRepository(private val dsl: DSLContext) {

    private val log = logger<RefundRepository>()

    suspend fun findPayment(pspReference: String): PaymentEntity? = io {
        log.info("Find payments for pspReference")

        val rows = dsl.select()
            .from(PAYMENT)
            .leftJoin(PAYMENT_HOLD)
            .on(PAYMENT_HOLD.PAYMENT_ID.eq(PAYMENT.ID))
            .and(PAYMENT_HOLD.RESOLVED_AT.isNull)
            .where(PAYMENT.PSP_REFERENCE.eq(pspReference))
            .fetch()

        val row = rows.firstOrNull() ?: let {
            log.info("Nothing found for pspReference")
            return@io null
        }
        val reference = row[PAYMENT.PSP_REFERENCE]!!

        val result = PaymentEntity(
            id = row[PAYMENT.ID]!!,
            pspReference = reference,
            merchantId = row[PAYMENT.MERCHANT_ID],
            gross = row[PAYMENT.GROSS]!!,
            tax = row[PAYMENT.TAX]!!,
            fee = row[PAYMENT.FEE]!!,
            merchantNet = row[PAYMENT.MERCHANT_NET]!!,
            currency = currency(row[PAYMENT.CURRENCY]!!, reference),
            taxCountry = row[PAYMENT.TAX_COUNTRY],
            taxCategory = row[PAYMENT.TAX_CATEGORY]?.let { enumById<TaxCategory>(it) },
            taxRateBps = row[PAYMENT.TAX_RATE_BPS],
            reverseCharge = row[PAYMENT.REVERSE_CHARGE] ?: false,
            evidence = JsonbMapper.fromJsonb<Map<String, String?>>(row[PAYMENT.EVIDENCE]) ?: emptyMap(),
            status = enumById<PaymentStatus>(row[PAYMENT.STATUS]!!),
            holdReasons = rows.mapNotNull { it[PAYMENT_HOLD.REASON] }
                .map { enumById<HoldReason>(it) }
                .toSet(),
            paymentTime = row[PAYMENT.PAYMENT_TIME]!!.toInstant(),
        )

        log.info("Saved paymentId=${result.id}")
        result
    }

    suspend fun insert(
        refund: RefundEntity,
        rawPayload: String,
        entries: (previousRefundTotal: BigDecimal) -> List<LedgerEntry>,
    ): LedgerWrite = io {
        log.info("Insert new refund ${refund.id}")

        dsl.transactionResult { cfg ->
            val db = DSL.using(cfg)

            val gross = paymentGrossForUpdate(db, refund.paymentId)
            val transactionId = randomUuid()

            val inserted: UUID? = db
                .insertInto(REFUND)
                .set(REFUND.ID, refund.id)
                .set(REFUND.REFUND_REFERENCE, refund.refundReference)
                .set(REFUND.PAYMENT_ID, refund.paymentId)
                .set(REFUND.AMOUNT, refund.amount)
                .set(REFUND.CURRENCY, refund.currency.name)
                .set(REFUND.REASON, refund.reason.id)
                .set(REFUND.FEE_RETURNED, refund.feeReturned)
                .set(REFUND.REFUNDED_AT, refund.refundedAt.atOffset(ZoneOffset.UTC))
                .set(REFUND.LEDGER_TRANSACTION_ID, transactionId)
                .onConflict(REFUND.REFUND_REFERENCE)
                .doNothing()
                .returningResult(REFUND.ID)
                .fetchOne()
                ?.value1()

            if (inserted == null) {
                val stored = storedRefund(db, refund.refundReference)

                if (stored.paymentId != refund.paymentId ||
                    stored.amount.compareTo(refund.amount) != 0 ||
                    stored.currency != refund.currency ||
                    stored.reason != refund.reason ||
                    stored.feeReturned != refund.feeReturned ||
                    stored.refundedAt != refund.refundedAt
                ) {
                    insertProcessingError(
                        db,
                        ProcessingError(
                            id = randomUuid(),
                            eventType = EventType.REFUND,
                            externalReference = refund.refundReference,
                            payload = rawPayload,
                            code = ProcessingErrorCode.IDEMPOTENCY_CONFLICT,
                            detail = "same refundReference received with different immutable refund data",
                        ),
                    )
                    return@transactionResult LedgerWrite.Conflict(
                        "same refundReference received with different immutable refund data"
                    )
                }

                log.info("outcome=replay nothing written")
                return@transactionResult LedgerWrite.Duplicate(paymentStatus(db, refund.paymentId))
            }

            val previousRefundTotal = db.select(DSL.coalesce(DSL.sum(REFUND.AMOUNT), BigDecimal.ZERO))
                .from(REFUND)
                .where(REFUND.PAYMENT_ID.eq(refund.paymentId))
                .and(REFUND.ID.ne(refund.id))
                .fetchOne(0, BigDecimal::class.java) ?: BigDecimal.ZERO
            val refundedSoFar = previousRefundTotal + refund.amount
            val ledgerEntries = entries(previousRefundTotal)

            ledgerEntries.groupBy { it.currency }.forEach { (currency, group) ->
                val sum = group.fold(BigDecimal.ZERO) { total, entry -> total + entry.amount }
                require(sum.signum() == 0) { "entries for $currency do not sum to zero: $sum" }
            }

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
                LEDGER_ENTRY.OCCURRED_AT,
            )

            ledgerEntries.forEach { e ->
                lines.values(
                    transactionId, e.purpose.id, e.purposeKey,
                    e.amount, e.currency.name, refund.refundedAt.atOffset(ZoneOffset.UTC),
                )
            }

            lines.execute()

            val status = if (refundedSoFar.compareTo(gross) >= 0) PaymentStatus.REFUNDED
            else PaymentStatus.PARTIALLY_REFUNDED

            db.update(PAYMENT)
                .set(PAYMENT.STATUS, status.id)
                .where(PAYMENT.ID.eq(refund.paymentId))
                .execute()

            log.info("event=stored status={} amount={}", status, refund.amount)

            if (refundedSoFar.compareTo(gross) > 0) {
                insertProcessingError(
                    db,
                    ProcessingError(
                        id = randomUuid(),
                        eventType = EventType.REFUND,
                        externalReference = refund.refundReference,
                        payload = rawPayload,
                        code = ProcessingErrorCode.OVER_REFUND,
                        detail = "refunds total $refundedSoFar against gross $gross, excess booked to SUSPENSE",
                    ),
                )
                LedgerWrite.RecordedOverRefund(status, refundedSoFar, gross)
            } else {
                LedgerWrite.Inserted(status)
            }
        }
    }

    private fun paymentGrossForUpdate(db: DSLContext, paymentId: UUID): BigDecimal =
        db.select(PAYMENT.GROSS)
            .from(PAYMENT)
            .where(PAYMENT.ID.eq(paymentId))
            .forUpdate()
            .fetchOne(PAYMENT.GROSS)
            ?: error("payment $paymentId disappeared inside its own transaction")

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
