package com.nextasean.wawebhook.webhook

import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Validates Meta's `X-Hub-Signature-256` header: `sha256=` + hex(HMAC-SHA256(appSecret, rawBody)).
 */
class SignatureVerifier(appSecret: String) {

    private val key = SecretKeySpec(appSecret.toByteArray(Charsets.UTF_8), ALGORITHM)

    fun isValid(signatureHeader: String?, rawBody: ByteArray): Boolean {
        if (signatureHeader.isNullOrBlank() || !signatureHeader.startsWith(PREFIX)) return false
        val provided = try {
            HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length).trim())
        } catch (_: IllegalArgumentException) {
            return false
        }
        val expected = Mac.getInstance(ALGORITHM).apply { init(key) }.doFinal(rawBody)
        return MessageDigest.isEqual(expected, provided)
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val PREFIX = "sha256="
    }
}
