package org.example.model

import org.example.api.model.PaymentRequestDto
import org.example.api.model.TaxLocationDto
import org.example.model.enums.Currency
import java.util.UUID

/**
 * Internal payment model.
 */
data class PaymentModel(
    val requestId: UUID,
    val merchantId: UUID,
    val customerId: UUID,
    val amount: Long,               // minor units
    val currency: Currency,
    val taxLocation: TaxLocation,
) {
    companion object {

        /** @throws IllegalArgumentException on anything the API should reject. */
        fun from(dto: PaymentRequestDto): PaymentModel {
            val requestId = dto.requestId.toUuid("requestId")
            val merchantId = dto.merchantId.toUuid("merchantId")
            val customerId = dto.customerId.toUuid("customerId")

            require(dto.amount > 0) { "amount must be positive, was ${dto.amount}" }

            val currency = runCatching { Currency.valueOf(dto.currency.uppercase()) }
                .getOrElse {
                    throw IllegalArgumentException(
                        "unsupported currency '${dto.currency}', expected one of ${Currency.entries.joinToString()}"
                    )
                }

            return PaymentModel(
                requestId = requestId,
                merchantId = merchantId,
                customerId = customerId,
                amount = dto.amount,
                currency = currency,
                taxLocation = dto.taxLocation.toModel(),
            )
        }

        private fun TaxLocationDto.toModel(): TaxLocation = TaxLocation.of(
            country = country,
            subdivision = subdivision,
            postalCode = postalCode,
            taxId = taxId,
            customerType = customerType.toCustomerType(),
            evidence = evidence.map { e ->
                TaxEvidence(
                    type = runCatching { TaxEvidenceType.valueOf(e.type.uppercase()) }
                        .getOrElse { throw IllegalArgumentException("unknown evidence type '${e.type}'") },
                    value = e.value,
                )
            },
        )

        private fun String?.toCustomerType(): CustomerType =
            if (isNullOrBlank()) CustomerType.INDIVIDUAL
            else runCatching { CustomerType.valueOf(uppercase()) }
                .getOrElse { throw IllegalArgumentException("unknown customerType '$this'") }

        private fun String.toUuid(field: String): UUID =
            runCatching { UUID.fromString(this) }
                .getOrElse { throw IllegalArgumentException("$field is not a valid uuid: '$this'") }
    }
}
