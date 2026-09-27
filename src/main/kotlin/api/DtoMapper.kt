package org.example.api

import org.example.utils.Constants
import org.example.api.generated.model.CurrencyDto
import org.example.api.generated.model.ErrorReasonDto
import org.example.api.generated.model.LedgerResponseDto
import org.example.api.generated.model.PaymentRequestDto
import org.example.api.generated.model.RefundReasonDto
import org.example.api.generated.model.RefundRequestDto
import org.example.api.generated.model.ResponseStatusDto
import org.example.model.Money
import org.example.model.PaymentModel
import org.example.model.RefundModel
import org.example.model.enums.Currency
import org.example.model.enums.RefundReason

object DtoMapper {

    /** @throws IllegalArgumentException -> 400 INVALID_REQUEST */
    fun PaymentRequestDto.toModel(): PaymentModel {
        require(Constants.Api.EXTERNAL_REFERENCE.matches(pspReference)) {
            "pspReference must be 1-64 chars of [A-Za-z0-9_-], was '${pspReference}'"
        }
        require(amount.signum() > 0) { "amount must be positive, was ${amount}" }

        val domainCurrency = currency.toDomain()

        customerVatId?.let {
            require(Constants.Api.VAT_ID.matches(it)) { "customerVatId is malformed: '$it'" }
        }

        return PaymentModel(
            pspReference = pspReference,
            merchantId = merchantId.toUuid("merchantId"),
            amount = Money.amount(amount, domainCurrency),
            currency = domainCurrency,
            success = success,
            billingCountry = billingAddress?.country?.toCountry("billingAddress.country"),
            stateOrProvince = billingAddress?.stateOrProvince,
            cardIssuingCountry = cardIssuingCountry.toCountry("cardIssuingCountry"),
            ipCountry = ipCountry?.toCountry("ipCountry"),
            customerVatId = customerVatId,
            paymentTime = paymentTime.toInstant("paymentTime"),
        )
    }


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

    fun CurrencyDto.toDomain(): Currency = when (this) {
        CurrencyDto.EUR -> Currency.EUR
    }

    fun Currency.toDto(): CurrencyDto = when (this) {
        Currency.EUR -> CurrencyDto.EUR
    }

    fun recorded() = LedgerResponseDto(ResponseStatusDto.SUCCESS)
    fun rejected(reason: ErrorReasonDto) = LedgerResponseDto(ResponseStatusDto.FAILED, reason)
}