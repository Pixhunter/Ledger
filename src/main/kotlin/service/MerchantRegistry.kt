package org.example.service

import java.util.UUID

/**
 * Is this merchant ours?
 *
 * A capture for an unknown merchant is money we hold and cannot attribute.
 * It is never rejected - the PSP has already taken it from the customer, and
 * an error would only make the PSP retry forever while the cash sits
 * unrecorded.
 */
fun interface MerchantRegistry {
    suspend fun exists(merchantId: UUID): Boolean
}
