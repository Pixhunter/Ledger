package model

import org.example.model.enums.Currency
import org.example.utils.Constants
import org.example.utils.Sensitive
import java.math.BigDecimal
import java.time.LocalDate

data class DueTransfer(
    val kind: TransferKind,
    val key: String,
    val period: LocalDate,
    val destination: Sensitive<String>,
    val amount: BigDecimal,
    val currency: Currency = Currency.EUR,
) {
    /** Idempotency key: the receiver must pay one (kind, key, period) once. */
    val reference: String get() = "${kind.reference}-$key-$period"
}

enum class TransferKind(val reference: String) {
    PAYOUT("payout"),
    TAX("tax"),
}

sealed interface TransferResult {

    data class Accepted(val reference: String) : TransferResult

    data class Failed(val reason: String) : TransferResult
}

interface TransferClient {
    suspend fun send(transfer: DueTransfer): TransferResult
}

/** Rows a disbursement worker claims, and what it does with the answer. */
interface TransferStore {

    suspend fun due(limit: Int = Constants.Jobs.CLAIM_LIMIT): List<DueTransfer>

    suspend fun markSent(transfer: DueTransfer, externalReference: String)

    suspend fun release(transfer: DueTransfer)
}