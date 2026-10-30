package com.nextasean.wawebhook.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.ZoneId

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val publicBaseUrl: String = "http://localhost:8080",
    val linkTtl: Duration = Duration.ofHours(24),
    val documentsDir: String = "./data/documents",
    /** Where decision evidence reports (PDF) are kept, one folder per application. */
    val reportsDir: String = "./data/reports",
    /** Fonts with Hangul glyphs tried in order for the PDF (first one that exists and loads wins); a `.ttc` takes a `,index` suffix. */
    val reportFonts: List<String> = listOf(
        "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
        "/System/Library/Fonts/AppleSDGothicNeo.ttc,0",
        "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
        "C:/Windows/Fonts/malgun.ttf",
    ),
    val timeZone: String = "Asia/Jakarta",
    /** `stub` (demo, default) or `claude` (Claude vision analysis + review agent). */
    val documentCheck: String = "stub",
    /** Name the copilots answer under in the back office (badge and message footer). */
    val copilotLabel: String = "NEXUS AI",
    /** Show which engine produced a copilot answer (Claude model or rules) next to the label. Off for demos. */
    val copilotShowEngine: Boolean = false,
) {
    val zoneId: ZoneId get() = ZoneId.of(timeZone)

    override fun toString(): String =
        "AppProperties(publicBaseUrl=$publicBaseUrl, linkTtl=$linkTtl, documentsDir=$documentsDir, reportsDir=$reportsDir, timeZone=$timeZone, documentCheck=$documentCheck, copilotLabel=$copilotLabel, copilotShowEngine=$copilotShowEngine)"
}

@ConfigurationProperties(prefix = "demo")
data class DemoProperties(
    val dealers: List<DemoDealer> = emptyList(),
    /** Seed fake applications at startup (dashboard / review demo) when none exist yet. */
    val seedApplications: Boolean = false,
    val seedCount: Int = 40,
) {
    data class DemoDealer(
        val code: String = "",
        val name: String = "",
        val phone: String = "",
        /** Market code (ID / MM / KH); unknown values fall back to ID. */
        val country: String = "ID",
        /** STANDARD or EXCELLENT. */
        val tier: String = "STANDARD",
    )
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
