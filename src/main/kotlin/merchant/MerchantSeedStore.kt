package org.example.merchant

interface MerchantSeedStore {

    /** True when the row was inserted or actually changed. */
    suspend fun upsert(seed: MerchantSeed): Boolean
}
