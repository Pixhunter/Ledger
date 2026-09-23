package org.example.service

import java.util.UUID

/**
 * Placeholder until merchants have a table. An empty allowlist accepts
 * everyone, so the HELD branch stays reachable in tests by configuring one.
 */
class MerchantRegistry(
    private val known: Set<UUID> = emptySet(),
)  {
    fun exists(merchantId: UUID): Boolean =
        known.isEmpty() || merchantId in known
}
