package org.example.api.mapper

import org.example.api.model.PaymentRequestDto
import org.example.model.PaymentModel
import org.example.toCountry
import org.example.toCurrency
import org.example.toInstant
import org.example.toUuid

object PaymentMapper {

    private val PSP_REF = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val VAT_ID = Regex("^[A-Z]{2}[A-Za-z0-9]{2,13}$")

    /** @throws IllegalArgumentException -> 400 INVALID_REQUEST */
    fun PaymentRequestDto.toModel(): PaymentModel {
        require(PSP_REF.matches(pspReference)) {
            "pspReference must be 1-64 chars of [A-Za-z0-9_-], was '${pspReference}'"
        }
        require(amount > 0) { "amount must be positive, was ${amount}" }

        customerVatId?.let {
            require(VAT_ID.matches(it)) { "customerVatId is malformed: '$it'" }
        }

        return PaymentModel(
            pspReference = pspReference,
            merchantId = merchantId.toUuid("merchantId"),
            amount = amount,
            currency = currency.toCurrency(),
            billingCountry = billingAddress?.country?.toCountry("billingAddress.country"),
            stateOrProvince = billingAddress?.stateOrProvince,
            cardIssuingCountry = cardIssuingCountry.toCountry("cardIssuingCountry"),
            ipCountry = ipCountry?.toCountry("ipCountry"),
            customerVatId = customerVatId,
            paymentTime = paymentTime.toInstant("paymentTime"),
        )
    }
}
