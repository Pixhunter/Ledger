package org.example.merchant

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory

/**
 * Merchants a payment can be attributed to.
 *
 * A real MoR onboards them elsewhere - KYB, a signed contract, verified bank
 * details - and the ledger consumes the result. This reads a file at startup so
 * the service is usable without that: insert what is new, update what changed,
 * leave what matches alone, never delete.
 */
class MerchantSeeder(
    private val store: MerchantSeedStore,
    private val resource: String,
) {
    private val log = LoggerFactory.getLogger(MerchantSeeder::class.java)

    suspend fun run() {
        val stream = javaClass.classLoader.getResourceAsStream(resource)

        if (stream == null) {
            log.warn("no {} on the classpath, no merchants seeded", resource)
            return
        }

        val seeds: List<MerchantSeed> = stream.use { mapper.readValue(it) }
        val changed = seeds.count { store.upsert(it) }

        log.info("merchants seeded from {}: {} of {} changed", resource, changed, seeds.size)
    }

    private companion object {
        val mapper = jacksonObjectMapper()
    }
}
