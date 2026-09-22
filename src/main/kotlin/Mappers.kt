package org.example

import org.example.api.model.PaymentRequestDto
import org.example.model.PaymentModel
import org.example.model.enums.Currency
import java.util.UUID

fun PaymentRequestDto.toModel(): PaymentModel {
    val requestId = requestId.toUuid("requestId")
    val merchantId = merchantId.toUuid("merchantId")
    val customerId = customerId.toUuid("customerId")

    require(amount > 0) { "amount must be positive, was $amount" }

    val currency = runCatching { Currency.valueOf(currency.uppercase()) }
        .getOrElse {
            throw IllegalArgumentException(
                "unsupported currency '${currency}', expected one of ${Currency.entries.joinToString()}"
            )
        }

    return PaymentModel(requestId, merchantId, customerId, amount, currency)
}

private fun String.toUuid(field: String): UUID = runCatching { UUID.fromString(this) }
    .getOrElse { throw IllegalArgumentException("$field is not a valid uuid: '$this'") }
