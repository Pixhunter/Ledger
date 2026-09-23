package org.example.api.model

/**
 * Why a payment was refused. Nothing was written when one of these is returned.
 *
 * Deliberately short: after capture the PSP has already taken the customer's
 * money, so valid money is never rejected. Anything we cannot attribute -
 * unknown merchant, unresolved tax country - is recorded as HELD and answered
 * with 200. Only a broken request, where nothing was owed to anyone, fails.
 */
enum class FailureReason {
    /** Bad signature, malformed body, or a field that cannot be parsed. */
    INVALID_REQUEST,

    /** The capture is smaller than our own fee: there is nothing to split. */
    AMOUNT_BELOW_FEE,
}
