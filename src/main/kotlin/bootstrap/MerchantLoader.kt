package org.example.bootstrap

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.example.model.MerchantSeed
import org.example.repository.MerchantRepository
import org.example.utils.logger

/**
 * Merchants a payment can be attributed to.
 */
class MerchantLoader(
    private val store: MerchantRepository,
    private val resource: String,
) {
    private val log = logger<MerchantLoader>()

    suspend fun run() {
        val stream = javaClass.classLoader.getResourceAsStream(resource)

        if (stream == null) {
            log.warn("Merchants didn't create: outcome=missing resource=$resource")
            return
        }

        val seeds: List<MerchantSeed> = stream.use { mapper.readValue(it) }
        val changed = seeds.count { store.upsert(it) }

        log.info("Merchants created: resource=$resource changed=$changed of=${seeds.size}")
    }

    private companion object {
        val mapper = jacksonObjectMapper()
    }
}