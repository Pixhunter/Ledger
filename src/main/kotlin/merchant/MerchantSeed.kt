package org.example.merchant

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.example.model.enums.MerchantStatus
import org.example.model.enums.TaxCategory
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class MerchantSeed(
    val id: UUID,
    val name: String,
    val currency: String,
    val feeRateBps: Int,
    val taxCategory: TaxCategory,
    val status: MerchantStatus,
    val paymentDetails: PaymentDetailsSeed,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PaymentDetailsSeed(
    val pspAccountId: String,
    val accountHolder: String,
    val iban: String? = null,
    val bic: String? = null,
    val accountNumber: String? = null,
    val routingCode: String? = null,
    val bankCountry: String,
    val address: Map<String, String>,
)
