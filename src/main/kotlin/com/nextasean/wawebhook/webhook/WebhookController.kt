package com.nextasean.wawebhook.webhook

import com.nextasean.wawebhook.config.WhatsAppProperties
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest

@RestController
@RequestMapping("/webhook")
class WebhookController(
    private val props: WhatsAppProperties,
    private val signatureVerifier: SignatureVerifier,
    private val processor: WebhookProcessor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Meta's one-time verification handshake when the callback URL is registered. */
    @GetMapping
    fun verify(
        @RequestParam("hub.mode", required = false) mode: String?,
        @RequestParam("hub.verify_token", required = false) token: String?,
        @RequestParam("hub.challenge", required = false) challenge: String?,
    ): ResponseEntity<String> {
        val tokenMatches = token != null && constantTimeEquals(token, props.verifyToken)
        if (mode == "subscribe" && tokenMatches && challenge != null) {
            log.info("Webhook verification succeeded")
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge)
        }
        log.warn("Webhook verification rejected | mode={} | tokenMatches={} | challengePresent={}", mode, tokenMatches, challenge != null)
        return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.TEXT_PLAIN).body("Forbidden")
    }

    /** Event delivery. Verify the signature over the raw bytes, ack with 200, process in the background. */
    @PostMapping
    fun receive(
        @RequestHeader(name = "X-Hub-Signature-256", required = false) signature: String?,
        @RequestBody rawBody: ByteArray,
    ): ResponseEntity<Void> {
        if (!signatureVerifier.isValid(signature, rawBody)) {
            log.warn("Webhook POST rejected: missing or invalid X-Hub-Signature-256 | bodyBytes={}", rawBody.size)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        processor.processAsync(rawBody)
        return ResponseEntity.ok().build()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
