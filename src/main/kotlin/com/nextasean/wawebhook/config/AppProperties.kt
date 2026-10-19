package com.nextasean.wawebhook.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.ZoneId

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val publicBaseUrl: String = "http://localhost:8080",
    val linkTtl: Duration = Duration.ofHours(24),
    val documentsDir: String = "./data/documents",
    val timeZone: String = "Asia/Jakarta",
    val adminApiKey: String = "",
    /** `stub` (demo, default) or `claude` (Claude vision analysis + review agent). */
    val documentCheck: String = "stub",
) {
    val zoneId: ZoneId get() = ZoneId.of(timeZone)

    override fun toString(): String =
        "AppProperties(publicBaseUrl=$publicBaseUrl, linkTtl=$linkTtl, documentsDir=$documentsDir, timeZone=$timeZone, documentCheck=$documentCheck, adminApiKey=***)"
}

@ConfigurationProperties(prefix = "demo")
data class DemoProperties(val dealers: List<DemoDealer> = emptyList()) {
    data class DemoDealer(val code: String = "", val name: String = "", val phone: String = "")
}

@ConfigurationProperties(prefix = "demo-data")
data class DemoDataProperties(
    val checkDelay: Duration = Duration.ofSeconds(2),
    val extracted: Extracted = Extracted(),
) {
    data class Extracted(
        val name: String = "Demo Customer",
        val nik: String = "0000000000000000",
        val dob: String = "1990-01-01",
        val address: String = "Demo address",
    )
}

@ConfigurationProperties(prefix = "verification")
data class VerificationProperties(
    val country: String = "ID",
    val minLatency: Duration = Duration.ofSeconds(1),
    val maxLatency: Duration = Duration.ofSeconds(3),
)
