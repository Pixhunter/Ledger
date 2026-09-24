package org.example.service

import org.example.model.enums.RefundReason

class RefundFeePolicy(private val returnFeeFor: Set<RefundReason>) {

    fun feeReturned(reason: RefundReason): Boolean = reason in returnFeeFor

    companion object {
        fun of(reasons: List<String>) = RefundFeePolicy(
            reasons.map { RefundReason.valueOf(it.trim().uppercase()) }.toSet()
        )
    }
}
