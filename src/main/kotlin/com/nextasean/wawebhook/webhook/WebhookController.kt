package com.nextasean.wawebhook.webhook

import com.nextasean.wawebhook.config.WhatsAppProperties
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import java.security.MessageDigest

@RestController
@RequestMapping("/webhook")
class WebhookController(
    private val props: WhatsAppProperties,
    private val signatureVerifier: SignatureVerifier,
    private val processor: WebhookProcessor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Meta's one-time verification handshake when the callback URL is registered.
     *
     * The `hub.*` parameters normally arrive as the query string. Behind a webhook relay (axhub `hooks.*`
     * endpoints) the query string is not forwarded, so the original request URI is also looked up in the
     * usual proxy headers before the handshake is rejected.
     */
    @GetMapping
    fun verify(request: HttpServletRequest): ResponseEntity<String> {
        val params = handshakeParams(request)
        val mode = params["hub.mode"]
        val token = params["hub.verify_token"]
        val challenge = params["hub.challenge"]
        val tokenMatches = token != null && constantTimeEquals(token, props.verifyToken)
        if (mode == "subscribe" && tokenMatches && challenge != null) {
            log.info("Webhook verification succeeded")
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge)
        }
        log.warn(
            "Webhook verification rejected | mode={} | tokenMatches={} | challengePresent={} | query={} | headers={}",
            mode, tokenMatches, challenge != null, request.queryString != null, describeHeaders(request),
        )
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

    /** `hub.*` parameters from the query string, or from a proxy header carrying the original request URI. */
    private fun handshakeParams(request: HttpServletRequest): Map<String, String> {
        val direct = HUB_PARAMS.mapNotNull { name -> request.getParameter(name)?.let { name to it } }.toMap()
        if (direct.containsKey("hub.mode")) return direct
        for (header in ORIGINAL_URI_HEADERS) {
            val value = request.getHeader(header)?.takeIf { it.isNotBlank() } ?: continue
            val parsed = parseHubParams(value)
            if (parsed.containsKey("hub.mode")) {
                log.info("Webhook verification parameters taken from header {}", header)
                return parsed
            }
        }
        return direct
    }

    private fun parseHubParams(uriOrQuery: String): Map<String, String> = runCatching {
        val query = if (uriOrQuery.contains('?') || uriOrQuery.contains("://") || uriOrQuery.startsWith("/")) {
            UriComponentsBuilder.fromUriString(uriOrQuery).build().query
        } else {
            uriOrQuery
        } ?: return emptyMap()
        val values = UriComponentsBuilder.newInstance().query(query).build().queryParams
        HUB_PARAMS.mapNotNull { name -> values.getFirst(name)?.let { name to java.net.URLDecoder.decode(it, Charsets.UTF_8) } }.toMap()
    }.getOrElse { emptyMap() }

    /** Header names only, plus the values of routing headers, so a relay that strips the query can be diagnosed. */
    private fun describeHeaders(request: HttpServletRequest): String =
        request.headerNames.toList().joinToString(",") { name ->
            if (ROUTING_HEADER_HINT.containsMatchIn(name)) "$name=${request.getHeader(name)}" else name
        }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private companion object {
        val HUB_PARAMS = listOf("hub.mode", "hub.verify_token", "hub.challenge")
        val ORIGINAL_URI_HEADERS = listOf(
            "X-Original-URI", "X-Original-URL", "X-Forwarded-Uri", "X-Forwarded-Query", "X-Original-Query",
            "X-Axhub-Original-Uri", "X-Axhub-Original-Url", "X-Axhub-Query", "X-Axhub-Original-Query",
            "X-Relay-Original-Uri", "X-Relay-Query",
        )
        val ROUTING_HEADER_HINT = Regex("(?i)uri|url|query|forward|original|axhub|relay|host")
    }
}
