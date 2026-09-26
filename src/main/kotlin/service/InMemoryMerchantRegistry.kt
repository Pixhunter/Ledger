package org.example.service

import java.util.UUID

class InMemoryMerchantRegistry(private val known: Set<UUID> = emptySet()) : MerchantRegistry {
    override suspend fun exists(merchantId: UUID): Boolean = merchantId in known
}
