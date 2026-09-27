package org.example.api.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.example.utils.Constants

/**
 * HMAC over the raw body, as every PSP webhook does.
 *
 * This endpoint is a trust boundary: whoever can reach it can invent money.
 * Without a signature check, a leaked URL is a leaked ledger.
 *
 * Two details that are the whole point:
 *  - the RAW bytes are signed, not a re-serialised object; re-encoding
 *    changes whitespace and key order and the signature stops matching
 *  - constant-time comparison, so an attacker cannot time-probe the digest
 *
 * Fails closed: no secret means every request is rejected.
 */
class PspSignature(
    private val secret: String?,
    private val allowSecretHeader: Boolean = false,
) {

    val enabled: Boolean get() = !secret.isNullOrBlank()

    fun verify(rawBody: ByteArray, header: String?): Boolean {
        if (!enabled || header.isNullOrBlank()) return false

        // Swagger cannot compute an HMAC over the body it is about to send, so
        // in dev the header may be the shared secret itself. A missing or wrong
        // header is still rejected, which is the half worth demonstrating.
        if (allowSecretHeader && constantTimeEquals(secret!!, header.trim())) return true

        val mac = Mac.getInstance(ALGORITHM).apply {
            init(SecretKeySpec(secret!!.toByteArray(), ALGORITHM))
        }
        val expected = mac.doFinal(rawBody).toHex()
        return constantTimeEquals(expected, header.trim())
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    companion object {
        val HEADER = Constants.Api.PSP_SIGNATURE_HEADER
        private val ALGORITHM = Constants.Api.PSP_SIGNATURE_ALGORITHM
    }
}
