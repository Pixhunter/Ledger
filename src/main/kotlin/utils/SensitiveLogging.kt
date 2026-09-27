package org.example.utils

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * What this service may and may not write to a log, and the mechanism that
 * enforces it.
 *
 * ## Why
 *
 * - **GDPR Art. 5(1)(c) — data minimisation.** A log line records the decision
 *   and its inputs' *shape*, not the inputs. `taxCountry=ES agreeing=2/3`, not
 *   the three raw country signals.
 * - **GDPR Art. 5(1)(e) — storage limitation.** Logs outlive their purpose
 *   fastest. Invoice retention (Directive 2006/112/EC Arts. 244-248) justifies
 *   keeping records in the *database* for years; it never justifies keeping
 *   them in logs.
 * - **GDPR Art. 32 — security of processing.** Credentials and payout
 *   destinations in a log are a breach waiting for whoever can read the log,
 *   which is a far wider set of people than can read the database.
 * - **GDPR Art. 4(5) — pseudonymisation.** `pspReference` and `merchantId` are
 *   opaque outside our own storage, so logging them in full is both safe and
 *   what makes an incident traceable.
 * - **PCI DSS v4.0 Reqs 3.3 / 3.4** — a log is storage. A PAN must be masked
 *   and authentication data (CVV, PIN) must never be stored at all. This
 *   service is never sent a card number, CVV or cardholder name - only
 *   `cardIssuingCountry` - which is what keeps it out of PCI storage scope.
 *   Do not add a card field without revisiting that.
 *
 * ## The policy
 *
 * | Field                                          | Log            |
 * |------------------------------------------------|----------------|
 * | pspReference, refundReference, merchantId      | in full        |
 * | amount, currency, success, paymentTime         | in full        |
 * | the tax decision: country + agreeing count     | in full        |
 * | billing / card / ip country, raw               | DEBUG only     |
 * | customerVatId                                  | validity only  |
 * | pspAccountId, iban, accountHolder, address     | never          |
 * | database password, psp secret                  | never          |
 *
 * ## Why a type and not a filtering logger
 *
 * A logger that inspects its arguments cannot help: by the time a secret has
 * been concatenated into a string, or picked up by a data class's generated
 * `toString()`, the value is already a String and indistinguishable from any
 * other. Only the type system can prevent it, so a field that must not be
 * logged is declared [Sensitive] and there is no way to print it by accident.
 */
@JvmInline
value class Sensitive<T : Any>(private val value: T) {

    /** Every path that prints a value - "$x", {}, toString() - lands here. */
    override fun toString(): String = REDACTED

    /**
     * The one way out. Grep `.reveal()` to review every place a protected
     * value is used: binding it to SQL, or handing it to the party it belongs
     * to. It must never be an argument to a log call.
     */
    fun reveal(): T = value

    companion object {
        const val REDACTED = "***"
    }
}

fun <T : Any> T.sensitive(): Sensitive<T> = Sensitive(this)

/**
 * Last [keep] characters, for the rare line that must be correlated with a
 * statement the merchant can see. Still personal data, so DEBUG at most, and
 * never for credentials.
 */
fun String.maskTail(keep: Int = 4): String =
    if (length <= keep) Sensitive.REDACTED else Sensitive.REDACTED + takeLast(keep)

/**
 * One logger per class, named after that class, with no string to drift and no
 * `::class.java` to copy wrongly.
 *
 *     private val log = logger<PaymentService>()
 */
inline fun <reified T> logger(): Logger = LoggerFactory.getLogger(T::class.java)

/** For a top-level entry point, which has no class to name the logger after. */
fun logger(name: String): Logger = LoggerFactory.getLogger(name)
