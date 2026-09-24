package org.example.api.mapper

import org.example.api.generated.model.RefundReasonDto
import org.example.api.generated.model.RefundRequestDto
import org.example.model.RefundModel
import org.example.model.enums.RefundReason
import org.example.toInstant

object RefundMapper {

    private val REFERENCE = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** @throws IllegalArgumentException -> 400 INVALID_REQUEST */
    fun RefundRequestDto.toModel(): RefundModel {
        require(REFERENCE.matches(refundReference)) {
            "refundReference must be 1-64 chars of [A-Za-z0-9_-], was '$refundReference'"
        }
        require(REFERENCE.matches(pspReference)) {
            "pspReference must be 1-64 chars of [A-Za-z0-9_-], was '$pspReference'"
        }
        require(amount > 0) { "amount must be positive, was $amount" }

        return RefundModel(
            refundReference = refundReference,
            pspReference = pspReference,
            amount = amount,
            currency = currency.toDomain(),
            success = success,
            reason = (reason ?: RefundReasonDto.OTHER).toDomain(),
            refundedAt = refundedAt.toInstant("refundedAt"),
        )
    }

    private fun RefundReasonDto.toDomain(): RefundReason = when (this) {
        RefundReasonDto.DUPLICATE -> RefundReason.DUPLICATE
        RefundReasonDto.FRAUD -> RefundReason.FRAUD
        RefundReasonDto.CUSTOMER_REQUEST -> RefundReason.CUSTOMER_REQUEST
        RefundReasonDto.PRODUCT_ISSUE -> RefundReason.PRODUCT_ISSUE
        RefundReasonDto.OTHER -> RefundReason.OTHER
    }
}
