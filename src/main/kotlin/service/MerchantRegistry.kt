package org.example.service

import java.util.UUID

// TODO read mor.merchant. Until then the allowlist is empty, so every capture is HELD.
class MerchantRegistry(
    private val known: Set<UUID> = emptySet(),
) {
    fun exists(merchantId: UUID): Boolean = merchantId in known
}
