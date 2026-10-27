package com.nextasean.wawebhook.web

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Liveness probe for the hosting platform: answers without touching the database or any external service. */
@RestController
class HealthController {
    @GetMapping("/healthz", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun healthz(): String = "ok"
}
