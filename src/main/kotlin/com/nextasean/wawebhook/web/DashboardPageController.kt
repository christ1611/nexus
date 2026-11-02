package com.nextasean.wawebhook.web

import org.springframework.core.io.ClassPathResource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Serves the back-office pages. The pages themselves hold no data; they call the admin API, which is open in the
 * demo build (no login).
 * - `/`: landing page (title and ASEAN map) with a START button to the dashboard
 * - `/dashboard`: statistics (Step 5)
 * - `/review`: reviewer work queue and application detail with documents, evidence and decisions (Step 4)
 */
@RestController
class DashboardPageController {
    private val dashboard: ByteArray = ClassPathResource("static/dashboard.html").inputStream.use { it.readAllBytes() }
    private val review: ByteArray = ClassPathResource("static/review.html").inputStream.use { it.readAllBytes() }
    private val landing: ByteArray = ClassPathResource("static/landing.html").inputStream.use { it.readAllBytes() }

    @GetMapping("/", produces = ["text/html;charset=UTF-8"])
    fun root(): ResponseEntity<ByteArray> = html(landing)

    @GetMapping("/dashboard", produces = ["text/html;charset=UTF-8"])
    fun dashboard(): ResponseEntity<ByteArray> = html(dashboard)

    @GetMapping("/review", produces = ["text/html;charset=UTF-8"])
    fun review(): ResponseEntity<ByteArray> = html(review)

    private fun html(page: ByteArray): ResponseEntity<ByteArray> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType(MediaType.TEXT_HTML, Charsets.UTF_8)).body(page)
}
