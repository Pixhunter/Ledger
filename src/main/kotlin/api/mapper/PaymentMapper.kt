package org.example.api.mapper

import org.example.api.generated.model.PaymentRequestDto
import org.example.model.PaymentModel
import org.example.model.Money
import org.example.toCountry
import org.example.toInstant
import org.example.toUuid
import org.example.Constants

object PaymentMapper {

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
}
