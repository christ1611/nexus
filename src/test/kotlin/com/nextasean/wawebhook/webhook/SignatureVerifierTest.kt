package com.nextasean.wawebhook.webhook

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignatureVerifierTest {

    private val secret = "test-app-secret"
    private val verifier = SignatureVerifier(secret)
    private val body = """{"object":"whatsapp_business_account"}""".toByteArray()

    @Test
    fun `accepts a correct signature`() {
        assertTrue(verifier.isValid(sign(secret, body), body))
    }

    @Test
    fun `accepts upper-case hex`() {
        assertTrue(verifier.isValid(sign(secret, body).uppercase().replaceFirst("SHA256=", "sha256="), body))
    }

    @Test
    fun `rejects a signature made with another secret`() {
        assertFalse(verifier.isValid(sign("other-secret", body), body))
    }

    @Test
    fun `rejects a signature for a different body`() {
        assertFalse(verifier.isValid(sign(secret, "tampered".toByteArray()), body))
    }

    @Test
    fun `rejects missing, malformed or wrong-prefix headers`() {
        assertFalse(verifier.isValid(null, body))
        assertFalse(verifier.isValid("", body))
        assertFalse(verifier.isValid("sha1=abcd", body))
        assertFalse(verifier.isValid("sha256=not-hex", body))
        assertFalse(verifier.isValid("sha256=abc", body))
    }
}
