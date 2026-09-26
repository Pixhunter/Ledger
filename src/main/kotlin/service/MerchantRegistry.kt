package org.example.service

import java.util.UUID

fun interface MerchantRegistry {
    suspend fun exists(merchantId: UUID): Boolean
}
