package org.example.bootstrap

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.example.model.MerchantSeed
import org.example.repository.MerchantRepository
import org.example.utils.logger

/**
 * Merchants a payment can be attributed to.
 *
 * A real MoR onboards them elsewhere - KYB, a signed contract, verified bank
 * details - and the ledger consumes the result. This reads a file at startup so
 * the service is usable without that: insert what is new, update what changed,
 * leave what matches alone, never delete.
 */
class MerchantSeeder(
    private val store: MerchantRepository,
    private val resource: String,
) {
    private val log = logger<MerchantSeeder>()

    suspend fun run() {
        val stream = javaClass.classLoader.getResourceAsStream(resource)

        if (stream == null) {
            log.warn("event=merchant_seed outcome=missing resource={}", resource)
            return
        }

        val seeds: List<MerchantSeed> = stream.use { mapper.readValue(it) }
        val changed = seeds.count { store.upsert(it) }

        log.info("event=merchant_seed resource={} changed={} of={}", resource, changed, seeds.size)
    }

    private companion object {
        val mapper = jacksonObjectMapper()
    }
}