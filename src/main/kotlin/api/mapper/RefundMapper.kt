package org.example.api.mapper

import org.example.api.generated.model.RefundReasonDto
import org.example.api.generated.model.RefundRequestDto
import org.example.model.RefundModel
import org.example.model.Money
import org.example.model.enums.RefundReason
import org.example.toInstant
import org.example.Constants

object RefundMapper {

    /** @throws IllegalArgumentException -> 400 INVALID_REQUEST */
    fun RefundRequestDto.toModel(): RefundModel {
        require(Constants.Api.EXTERNAL_REFERENCE.matches(refundReference)) {
            "refundReference must be 1-64 chars of [A-Za-z0-9_-], was '$refundReference'"
        }
        require(Constants.Api.EXTERNAL_REFERENCE.matches(pspReference)) {
            "pspReference must be 1-64 chars of [A-Za-z0-9_-], was '$pspReference'"
        }
        require(amount.signum() > 0) { "amount must be positive, was $amount" }

        val domainCurrency = currency.toDomain()

        return RefundModel(
            refundReference = refundReference,
            pspReference = pspReference,
            amount = Money.amount(amount, domainCurrency),
            currency = domainCurrency,
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
