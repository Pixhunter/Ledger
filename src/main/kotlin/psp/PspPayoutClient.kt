package org.example.psp

// reference is the idempotency key: the PSP must pay one (merchant, day) once,
// however many times we ask.
interface PspPayoutClient {
    suspend fun payout(request: PayoutRequest): PayoutResult
}
