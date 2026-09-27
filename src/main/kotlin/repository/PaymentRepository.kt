package org.example.repository

import org.example.api.io
import org.example.database.JsonbMapper
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
import model.enums.enumById
import org.example.model.enums.PaymentStatus
import org.example.model.enums.TaxCategory
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.ZoneOffset
import java.util.UUID
import java.math.BigDecimal
import org.example.api.randomUuid
import org.example.utils.logger
import repository.store.PaymentStore

class PaymentRepository(private val dsl: DSLContext) : PaymentStore {

    private val log = logger<PaymentRepository>()

    override suspend fun insert(
        payment: PaymentEntity,
        entries: List<LedgerEntry>,
        rawPayload: String,
        errors: List<ProcessingError>,
    ): LedgerWrite = io {
        log.info("Insert payment for merchantId=${payment.merchantId} paymentId=${payment.id} paymentTime=${payment.paymentTime}")

        entries.groupBy { it.currency }.forEach { (currency, group) ->
            val sum = group.fold(BigDecimal.ZERO) { total, entry -> total + entry.amount }
            require(sum.signum() == 0) { "Entries for $currency do not sum to zero for payment: $sum" }
        }

        dsl.transactionResult { cfg ->
            val db = DSL.using(cfg)
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
                .set(PAYMENT.EVIDENCE, JsonbMapper.toJsonb(payment.evidence))
                .set(PAYMENT.STATUS, payment.status.id)
                .set(PAYMENT.PAYMENT_TIME, payment.paymentTime.atOffset(ZoneOffset.UTC))
                .onConflict(PAYMENT.PSP_REFERENCE)
                .doNothing()
                .returningResult(PAYMENT.ID)
                .fetchOne()
                ?.value1()

            if (inserted == null) {
                val existing = db
                    .selectFrom(PAYMENT)
                    .where(PAYMENT.PSP_REFERENCE.eq(payment.pspReference))
                    .fetchOne()
                    ?: error("psp_reference ${payment.pspReference} conflicted but cannot be read back")

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
                        JsonbMapper.fromJsonb<Map<String, String?>>(existing.evidence) == payment.evidence &&
                        existing.paymentTime.toInstant() == payment.paymentTime

                if (!sameEvent) {
                    insertProcessingError(
                        db,
                        ProcessingError(
                            id = randomUuid(),
                            eventType = EventType.CAPTURE,
                            externalReference = payment.pspReference,
                            payload = rawPayload,
                            code = ProcessingErrorCode.IDEMPOTENCY_CONFLICT,
                            detail = "same pspReference received with different immutable payment data",
                        ),
                    )
                    log.info("Same pspReference received with different immutable payment data - failed to save new payment")

                    return@transactionResult LedgerWrite.Conflict(
                        "same pspReference received with different immutable payment data"
                    )
                }

                log.info("Replay payment - nothing written")

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

            val transactionId = randomUuid()

            db.insertInto(LEDGER_TRANSACTION)
                .set(LEDGER_TRANSACTION.ID, transactionId)
                .set(LEDGER_TRANSACTION.TYPE, LedgerTransactionType.CAPTURE.id)
                .set(LEDGER_TRANSACTION.PAYMENT_ID, payment.id)
                .execute()

            val insert = db.insertInto(
                LEDGER_ENTRY,
                LEDGER_ENTRY.TRANSACTION_ID,
                LEDGER_ENTRY.PURPOSE,
                LEDGER_ENTRY.PURPOSE_KEY,
                LEDGER_ENTRY.AMOUNT,
                LEDGER_ENTRY.CURRENCY,
                LEDGER_ENTRY.OCCURRED_AT,
            )

            entries.forEach { e ->
                insert.values(
                    transactionId,
                    e.purpose.id,
                    e.purposeKey,
                    e.amount,
                    e.currency.name,
                    payment.paymentTime.atOffset(ZoneOffset.UTC),
                )
            }

            insert.execute()

            insertProcessingErrors(db, errors)

            log.info(
                "Stored status=${payment.status} held=${payment.holdReasons.takeIf { it.isNotEmpty() } ?: "-"} entries=${errors.size}"
            )

            LedgerWrite.Inserted(payment.status)
        }
    }
}
