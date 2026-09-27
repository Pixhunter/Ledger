package org.example.api.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.example.utils.Constants
import org.example.utils.logger

/**
 * HMAC over the raw body, as every PSP webhook does.
 *
 * Small security - TODO - add mode defence
 *
 * Fails closed: no secret means every request is rejected.
 */
class PspSignature(
    private val secret: String?,
    private val allowSecretHeader: Boolean = false,
) {
    private val log = logger<PspSignature>()

    val enabled: Boolean get() = !secret.isNullOrBlank()

    fun verify(rawBody: ByteArray, header: String?): Boolean {
        if (!enabled || header.isNullOrBlank()) {
            log.info("Verifying signature FAILED for ${rawBody.size} bytes")
            return false
        }

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
