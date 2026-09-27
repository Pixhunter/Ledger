package org.example.repository

import org.example.database.JsonbMapper
import org.example.jooq.tables.references.MERCHANT
import org.example.jooq.tables.references.MERCHANT_PAYMENT_DETAILS
import org.example.model.MerchantSeed
import org.example.model.enums.MerchantStatus
import org.example.service.MerchantRegistry
import org.example.api.io
import org.example.utils.logger
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

class MerchantRepository(private val dsl: DSLContext) : MerchantRegistry {
    private val log = logger<MerchantRepository>()

    override suspend fun exists(merchantId: UUID): Boolean = io {
        log.info("Checking existence for $merchantId")

        dsl.fetchExists(
            DSL.selectFrom(MERCHANT)
                .where(MERCHANT.ID.eq(merchantId))
                .and(MERCHANT.STATUS.eq(MerchantStatus.ACTIVE.id))
        )
    }

    suspend fun upsert(seed: MerchantSeed): Boolean = io {
        log.info("Upsert new merchant ${seed.id}")

        dsl.transactionResult { cfg ->
            val db = DSL.using(cfg)

            val merchant = db.insertInto(MERCHANT)
                .set(MERCHANT.ID, seed.id)
                .set(MERCHANT.NAME, seed.name)
                .set(MERCHANT.CURRENCY, seed.currency)
                .set(MERCHANT.FEE_RATE_BPS, seed.feeRateBps)
                .set(MERCHANT.TAX_CATEGORY, seed.taxCategory.id)
                .set(MERCHANT.STATUS, seed.status.id)
                .onConflict(MERCHANT.ID)
                .doUpdate()
                .set(MERCHANT.NAME, seed.name)
                .set(MERCHANT.CURRENCY, seed.currency)
                .set(MERCHANT.FEE_RATE_BPS, seed.feeRateBps)
                .set(MERCHANT.TAX_CATEGORY, seed.taxCategory.id)
                .set(MERCHANT.STATUS, seed.status.id)
                .where(
                    MERCHANT.NAME.ne(seed.name)
                        .or(MERCHANT.CURRENCY.ne(seed.currency))
                        .or(MERCHANT.FEE_RATE_BPS.ne(seed.feeRateBps))
                        .or(MERCHANT.TAX_CATEGORY.ne(seed.taxCategory.id))
                        .or(MERCHANT.STATUS.ne(seed.status.id))
                )
                .execute()

            val details = seed.paymentDetails
            val address = JsonbMapper.toJsonb(details.address)

            val payment = db.insertInto(MERCHANT_PAYMENT_DETAILS)
                .set(MERCHANT_PAYMENT_DETAILS.MERCHANT_ID, seed.id)
                .set(MERCHANT_PAYMENT_DETAILS.PSP_ACCOUNT_ID, details.pspAccountId)
                .set(MERCHANT_PAYMENT_DETAILS.ACCOUNT_HOLDER, details.accountHolder)
                .set(MERCHANT_PAYMENT_DETAILS.IBAN, details.iban)
                .set(MERCHANT_PAYMENT_DETAILS.BIC, details.bic)
                .set(MERCHANT_PAYMENT_DETAILS.ACCOUNT_NUMBER, details.accountNumber)
                .set(MERCHANT_PAYMENT_DETAILS.ROUTING_CODE, details.routingCode)
                .set(MERCHANT_PAYMENT_DETAILS.BANK_COUNTRY, details.bankCountry)
                .set(MERCHANT_PAYMENT_DETAILS.ADDRESS, address)
                .onConflict(MERCHANT_PAYMENT_DETAILS.MERCHANT_ID)
                .doUpdate()
                .set(MERCHANT_PAYMENT_DETAILS.PSP_ACCOUNT_ID, details.pspAccountId)
                .set(MERCHANT_PAYMENT_DETAILS.ACCOUNT_HOLDER, details.accountHolder)
                .set(MERCHANT_PAYMENT_DETAILS.IBAN, details.iban)
                .set(MERCHANT_PAYMENT_DETAILS.BIC, details.bic)
                .set(MERCHANT_PAYMENT_DETAILS.ACCOUNT_NUMBER, details.accountNumber)
                .set(MERCHANT_PAYMENT_DETAILS.ROUTING_CODE, details.routingCode)
                .set(MERCHANT_PAYMENT_DETAILS.BANK_COUNTRY, details.bankCountry)
                .set(MERCHANT_PAYMENT_DETAILS.ADDRESS, address)
                .set(MERCHANT_PAYMENT_DETAILS.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(
                    MERCHANT_PAYMENT_DETAILS.PSP_ACCOUNT_ID.ne(details.pspAccountId)
                        .or(MERCHANT_PAYMENT_DETAILS.ACCOUNT_HOLDER.ne(details.accountHolder))
                        .or(MERCHANT_PAYMENT_DETAILS.BANK_COUNTRY.ne(details.bankCountry))
                        .or(MERCHANT_PAYMENT_DETAILS.IBAN.isDistinctFrom(details.iban))
                        .or(MERCHANT_PAYMENT_DETAILS.BIC.isDistinctFrom(details.bic))
                        .or(MERCHANT_PAYMENT_DETAILS.ACCOUNT_NUMBER.isDistinctFrom(details.accountNumber))
                        .or(MERCHANT_PAYMENT_DETAILS.ROUTING_CODE.isDistinctFrom(details.routingCode))
                        .or(MERCHANT_PAYMENT_DETAILS.ADDRESS.isDistinctFrom(address))
                )
                .execute()

            log.info("Got result ${merchant + payment > 0}")
            merchant + payment > 0
        }
    }
}
