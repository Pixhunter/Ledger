package org.example.repository

import kotlinx.coroutines.runBlocking
import org.example.database.JsonbMapper
import org.example.jooq.tables.references.MERCHANT_PAYMENT_DETAILS
import org.example.model.MerchantSeed
import org.example.model.PaymentDetailsSeed
import org.example.model.enums.MerchantStatus
import org.example.model.enums.TaxCategory
import org.example.support.PostgresTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MerchantRepositoryTest : PostgresTest() {

    @Test
    fun `address-only change updates merchant payment details`() = runBlocking {
        val repository = MerchantRepository(dsl)
        val original = merchantSeed(mapOf("country" to "DE", "city" to "Berlin"))

        assertTrue(repository.upsert(original))
        val changed = original.copy(
            paymentDetails = original.paymentDetails.copy(
                address = mapOf("country" to "DE", "city" to "Hamburg"),
            )
        )

        assertTrue(repository.upsert(changed))
        val stored = dsl.select(MERCHANT_PAYMENT_DETAILS.ADDRESS)
            .from(MERCHANT_PAYMENT_DETAILS)
            .where(MERCHANT_PAYMENT_DETAILS.MERCHANT_ID.eq(original.id))
            .fetchSingle(MERCHANT_PAYMENT_DETAILS.ADDRESS)

        assertEquals("Hamburg", JsonbMapper.fromJsonb<Map<String, String>>(stored)?.get("city"))
        assertFalse(repository.upsert(changed))
    }

    private fun merchantSeed(address: Map<String, String>) = MerchantSeed(
        id = UUID.randomUUID(),
        name = "Test merchant",
        currency = "EUR",
        feeRateBps = 500,
        taxCategory = TaxCategory.STANDARD,
        status = MerchantStatus.ACTIVE,
        paymentDetails = PaymentDetailsSeed(
            pspAccountId = "acct-test",
            accountHolder = "Test merchant",
            iban = "DE89370400440532013000",
            bankCountry = "DE",
            address = address,
        ),
    )
}
