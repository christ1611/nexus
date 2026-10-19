package com.nextasean.wawebhook.web

import org.springframework.core.io.ClassPathResource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Serves the back-office dashboard page. The page itself holds no data; it asks `/admin/dashboard/stats` with the admin key. */
@RestController
class DashboardPageController {
    private val page: ByteArray = ClassPathResource("static/dashboard.html").inputStream.use { it.readAllBytes() }

    @GetMapping("/dashboard", produces = ["text/html;charset=UTF-8"])
    fun dashboard(): ResponseEntity<ByteArray> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType(MediaType.TEXT_HTML, Charsets.UTF_8)).body(page)
}
