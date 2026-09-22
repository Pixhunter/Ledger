package org.example.tax

import org.example.ledger.Accounts
import org.example.ledger.EntryKind
import org.example.ledger.LedgerEntry
import org.example.ledger.LedgerRepository
import org.example.model.PaymentModel
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Turns a captured payment into ledger entries: computes the tax, the MoR
 * fee and the merchant's net, then posts all of it atomically.
 *
 * Accounting is synchronous and immediate - the moment money is received it
 * must be attributed, or the balances report lies about cash the MoR is
 * holding. Settlement (remitting tax, paying merchants) is the asynchronous,
 * batched part and lives elsewhere.
 */
class TaxService(
    private val ledger: LedgerRepository,
    private val rates: TaxRates,
    private val feeRate: BasisPoints,
) {
    private val log = LoggerFactory.getLogger(TaxService::class.java)

    /**
     * @return the split that was posted, or null if this requestId had
     *         already been posted (PSP replay).
     */
    suspend fun recordCapture(
        payment: PaymentModel,
        taxRateFromEvent: BasisPoints? = null,
        taxMode: TaxMode = TaxMode.INCLUSIVE,
        occurredAt: Instant = Instant.now(),
    ): Split? {
        val location = payment.taxLocation

        val rate = try {
            rates.resolve(location, taxRateFromEvent)
        } catch (e: UnknownJurisdiction) {
            // Never guess a jurisdiction: a wrong one means a wrong tax
            // return. Park the money instead, with the books still balanced.
            log.warn("unknown jurisdiction for ${payment.requestId}, booking to suspense", e)
            postToSuspense(payment, occurredAt)
            return null
        }

        val split = TaxCalculator.split(
            amount = payment.amount,
            currency = payment.currency,
            jurisdiction = location.jurisdiction,
            taxRate = rate,
            taxMode = taxMode,
            feeRate = feeRate,
            reverseCharge = location.isBusiness,
        )

        val requestId = payment.requestId.toString()

        // Debits positive, credits negative, sum zero. Cash comes in; the
        // other three are what the MoR now owes or has earned.
        val entries = buildList {
            add(entry(requestId, Accounts.MOR_CASH, split.gross, payment, occurredAt))

            if (split.tax != 0L) {
                add(
                    entry(
                        requestId,
                        Accounts.tax(split.jurisdiction),
                        -split.tax,
                        payment,
                        occurredAt,
                        jurisdiction = split.jurisdiction,
                    )
                )
            }

            if (split.fee != 0L) {
                add(entry(requestId, Accounts.MOR_REVENUE, -split.fee, payment, occurredAt))
            }

            add(
                entry(
                    requestId,
                    Accounts.merchantPayable(payment.merchantId),
                    -split.merchant,
                    payment,
                    occurredAt,
                )
            )
        }

        val posted = ledger.append(entries)

        if (!posted) {
            log.info("replay of $requestId, ledger unchanged")
            return null
        }

        log.info(
            "captured $requestId: gross=${split.gross} tax=${split.tax} " +
                "fee=${split.fee} merchant=${split.merchant} ${split.currency} " +
                "[${split.jurisdiction} @ ${split.taxRate.value}bp ${split.taxMode}]"
        )
        return split
    }

    /** Received but unattributable. Booked, flagged, resolved by a human. */
    private suspend fun postToSuspense(payment: PaymentModel, occurredAt: Instant) {
        val requestId = payment.requestId.toString()
        ledger.append(
            listOf(
                entry(requestId, Accounts.MOR_CASH, payment.amount, payment, occurredAt),
                entry(requestId, Accounts.SUSPENSE, -payment.amount, payment, occurredAt),
            )
        )
    }

    private fun entry(
        requestId: String,
        account: String,
        amount: Long,
        payment: PaymentModel,
        occurredAt: Instant,
        jurisdiction: String? = null,
    ) = LedgerEntry(
        transactionId = requestId,   // one capture event, one transaction
        requestId = requestId,
        kind = EntryKind.CAPTURE,
        account = account,
        amount = amount,
        currency = payment.currency,
        jurisdiction = jurisdiction,
        occurredAt = occurredAt,
    )
}
