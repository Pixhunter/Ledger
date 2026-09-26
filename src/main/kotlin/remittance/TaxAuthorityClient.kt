package org.example.remittance

import org.example.model.enums.Currency
import java.math.BigDecimal
import java.time.LocalDate

data class RemittanceRequest(
    val reference: String,
    val country: String,
    val periodStart: LocalDate,
    val amount: BigDecimal,
    val currency: Currency,
)

sealed interface RemittanceResult {

    data class Accepted(val reference: String) : RemittanceResult

    data class Failed(val reason: String) : RemittanceResult
}

interface TaxAuthorityClient {
    suspend fun remit(request: RemittanceRequest): RemittanceResult
}
