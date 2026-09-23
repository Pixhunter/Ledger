package org.example.api.model

enum class FailureReasonDto {
    /** Bad signature, malformed body, or a field that cannot be parsed. */
    INVALID_REQUEST,

    /** The capture is smaller than our own fee: there is nothing to split. */
    AMOUNT_BELOW_FEE,
}
