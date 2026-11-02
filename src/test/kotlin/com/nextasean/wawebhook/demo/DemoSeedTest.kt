package com.nextasean.wawebhook.demo

import com.fasterxml.jackson.databind.ObjectMapper
import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.webhook.FakeIntegrationsConfig
import com.nextasean.wawebhook.webhook.RecordingSender
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


/** The demo seeder fills every corner of the dashboard and review page, sends nothing, and can be removed again. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(FakeIntegrationsConfig::class)
@TestPropertySource(properties = ["spring.datasource.url=jdbc:h2:mem:demoseedtest;DB_CLOSE_DELAY=-1", "app.documents-dir=build/test-documents-demo"])
class DemoSeedTest @Autowired constructor(
    private val applications: ApplicationRepository,
    private val sender: RecordingSender,
    private val trace: com.nextasean.wawebhook.trace.AgentTraceService,
    private val mockMvc: MockMvc,
) {
    @Test
    fun `seeding produces a varied, consistent data set across countries and can be cleared`() {
        sender.clear()
        mockMvc.post("/admin/demo/seed") { contentType = MediaType.APPLICATION_JSON; content = """{"count":60,"reset":true}""" }.andExpect {
            status { isOk() }
            jsonPath("$.created") { value(60) }
            jsonPath("$.total") { value(60) }
        }
        assertTrue(sender.sent.isEmpty(), "seeding must not send WhatsApp messages")

        val apps = applications.findByDemoSeedTrue()
        assertEquals(60, apps.size)
        assertTrue(apps.all { it.demoSeed })
        val statuses = apps.groupingBy { it.status }.eachCount()
        assertTrue(statuses.getOrDefault(ApplicationStatus.APPROVED, 0) >= 5, "approved: $statuses")
        assertTrue(statuses.getOrDefault(ApplicationStatus.REJECTED, 0) >= 2, "rejected: $statuses")
        assertTrue(statuses.getOrDefault(ApplicationStatus.READY_FOR_REVIEW, 0) >= 3, "waiting: $statuses")
        assertTrue(statuses.getOrDefault(ApplicationStatus.DOCUMENTS_REQUESTED, 0) >= 1, "officer re-request showcase: $statuses")
        assertTrue(apps.any { it.officerReviewRequired == true && it.verifiedAt == null }, "officer hand-off showcase (invalid NIK)")
        assertTrue(apps.map { it.country }.toSet().containsAll(listOf("ID", "MM", "KH")), "all three countries")
        val grades = apps.mapNotNull { it.riskGrade }.toSet()
        assertTrue(grades.containsAll(listOf("A", "B", "C")), "grades: $grades")
        assertTrue(apps.any { it.riskMethod == "CLAUDE_VISION_RULES" }, "some rows carry vision evidence")
        assertTrue(apps.any { it.riskFlagsJson?.contains("INVALID_ID_NUMBER") == true || it.riskFlagsJson?.contains("SCREEN_OR_COPY") == true }, "fraud flags from document evidence")
        assertTrue(apps.filter { it.status.isFinal }.all { it.decidedAt != null && it.decidedBy != null })
        assertTrue(apps.all { !it.createdAt.isAfter(it.updatedAt) }, "createdAt <= updatedAt")
        val now = java.time.Instant.now()
        assertTrue(apps.all { !it.updatedAt.isAfter(now) && (it.riskAssessedAt == null || !it.riskAssessedAt!!.isAfter(now)) && (it.decidedAt == null || !it.decidedAt!!.isAfter(now)) }, "no timestamps in the future")
        assertTrue(apps.flatMap { trace.steps(it.id) }.none { it.createdAt.isAfter(now) }, "no agent steps in the future")
        assertTrue(apps.all { it.customerPhone == null || it.customerPhone!!.startsWith("620000") || it.customerPhone!!.startsWith("950000") || it.customerPhone!!.startsWith("855000") }, "unreachable demo phones")
        // Officer actions on demo rows never reach a phone.
        val waiting = apps.first { it.status == ApplicationStatus.READY_FOR_REVIEW && it.customerPhone != null }
        mockMvc.post("/admin/applications/${waiting.applicationNumber}/decision") { contentType = MediaType.APPLICATION_JSON; content = """{"decision":"APPROVED","reviewer":"Demo"}""" }
            .andExpect { status { isOk() }; jsonPath("$.dealerNotified") { value(false) }; jsonPath("$.customerNotified") { value(false) } }
        assertTrue(sender.sent.isEmpty(), "no WhatsApp messages for demo rows")
        // Documents exist on disk and the review detail is complete.
        val completed = apps.first { it.status == ApplicationStatus.APPROVED && it.riskGrade == "A" }
        mockMvc.get("/admin/applications/${completed.applicationNumber}").andExpect {
            status { isOk() }
            jsonPath("$.documents[0].fileAvailable") { value(true) }
            jsonPath("$.agentTrace.length()") { value(greaterThanOrEqualTo(6)) }
            jsonPath("$.events[-2].kind") { value("APPROVED") }
            jsonPath("$.events[-1].kind") { value("REPORT_GENERATED") }
            jsonPath("$.decisionReport.available") { value(true) }
            jsonPath("$.risk.grade") { value("A") }
        }
        mockMvc.get("/admin/dashboard/stats").andExpect {
            status { isOk() }
            jsonPath("$.totals.demoApplications") { value(60) }
            jsonPath("$.byCountry[1].applications") { value(greaterThanOrEqualTo(1)) }
            jsonPath("$.byCountry[2].applications") { value(greaterThanOrEqualTo(1)) }
            jsonPath("$.reviewQueue.length()") { value(greaterThanOrEqualTo(1)) }
            jsonPath("$.agent.providerLookups") { value(greaterThanOrEqualTo(10)) }
        }
        mockMvc.get("/admin/demo/seed").andExpect { jsonPath("$.demoApplications") { value(60) } }

        val dir = Path.of("build/test-documents-demo").resolve(completed.applicationNumber)
        assertTrue(Files.isDirectory(dir))
        mockMvc.delete("/admin/demo/seed").andExpect { status { isOk() }; jsonPath("$.removed") { value(60) } }
        assertEquals(0, applications.findByDemoSeedTrue().size)
        assertTrue(!Files.exists(dir), "document folder removed")
    }

    @Test
    fun `the default seed gives dealers records that match their tier`() {
        mockMvc.post("/admin/demo/seed") { contentType = MediaType.APPLICATION_JSON; content = """{"reset":true}""" }.andExpect { status { isOk() } }
        val body = mockMvc.get("/admin/dashboard/stats").andReturn().response.contentAsString
        val stats = ObjectMapper().readTree(body)
        val roster = stats["dealers"].filter { it["code"].asText().startsWith("DLR-DEMO-") }
        assertEquals(12, roster.size)
        val report = roster.joinToString("\n") { d -> val c = d["scorecard"]; "${d["code"].asText()} ${d["tier"].asText()} score=${c["score"]} grade=${c["grade"]} cap=${c["cap"]} decided=${c["decided"]} approval=${c["approvalRate"]} highRisk=${c["highRiskRate"]} fraud=${c["fraudWarnings"]} id=${c["identityMatchRate"]} rework=${c["reworkRate"]} meets=${c["meetsStandard"]}" }
        roster.filter { it["tier"].asText() == "EXCELLENT" }.forEach { d ->
            val c = d["scorecard"]
            assertTrue(c["meetsStandard"].asBoolean() && c["grade"].asText() in setOf("S", "A") && c["fraudWarnings"].asInt() == 0, "excellent dealer must meet the standard:\n$report")
        }
        roster.filter { it["tier"].asText() == "STANDARD" }.forEach { d -> assertTrue(!d["scorecard"]["meetsStandard"].asBoolean(), "standard dealer must not meet it:\n$report") }
        val grades = roster.map { it["scorecard"]["grade"].asText() }.toSet()
        assertTrue(grades.containsAll(listOf("S", "A", "B", "C")), "every grade appears:\n$report")
        assertTrue(roster.any { it["scorecard"]["cap"].asText() == "FRAUD" }, "a good score capped by fraud:\n$report")
        // The ranking follows the grades, best first.
        val byRank = roster.filter { it["scorecard"]["rated"].asBoolean() }.sortedBy { it["scorecard"]["rank"].asInt() }.map { it["scorecard"]["grade"].asText() }
        assertEquals(byRank.sortedBy { listOf("S", "A", "B", "C").indexOf(it) }, byRank, "ranks follow grades:\n$report")
        // Every score is explained by its components and ends the six-month line.
        roster.filter { it["scorecard"]["rated"].asBoolean() }.forEach { d ->
            val c = d["scorecard"]
            assertEquals(c["score"].asDouble(), c["components"].sumOf { it["points"].asDouble() }, 0.3)
            assertEquals(c["score"].asDouble(), c["trend"].last()["score"].asDouble(), 0.01)
            assertEquals(6, c["trend"].size())
        }
        // The other screens stay believable: a short queue with nothing waiting for weeks, nothing half done for months.
        val queue = stats["reviewQueue"]
        assertTrue(queue.size() in 5..30, "queue size ${queue.size()}")
        assertTrue(queue.all { it["waitingHours"].asDouble() < 24 * 5 }, "nothing waits for weeks")
        assertTrue(stats["inProgress"].all { it["idleHours"].isNull || it["idleHours"].asDouble() < 24 * 8 }, "nothing is stuck for months")
        // Pressing a stage number filters the list: each number must match that stage's rows, so the list has no cap.
        stats["pipeline"].forEach { s -> assertEquals(s["count"].asInt(), stats["inProgress"].count { it["current"].asText() == s["code"].asText() }, "rows at ${s["code"].asText()}") }
        // The statistics tab adds up: every month's parts make its total, and the markets add up to all of them.
        val reviewStats = stats["reviewStats"]
        listOf("ID", "MM", "KH", "ALL").forEach { key ->
            assertEquals(6, reviewStats[key]["months"].size())
            reviewStats[key]["months"].forEach { m -> assertEquals(m["applications"].asInt(), m["approved"].asInt() + m["pending"].asInt() + m["rejected"].asInt()) }
            assertEquals(reviewStats[key]["rejected"].asInt(), reviewStats[key]["rejectReasons"].sumOf { it["count"].asInt() }, "reasons cover every rejection")
            assertEquals(reviewStats[key]["months"].sumOf { it["rejected"].asInt() }, reviewStats[key]["rejected"].asInt())
            val outcome = reviewStats[key]["fraudOutcome"]
            assertEquals(reviewStats[key]["months"].sumOf { it["fraudApplications"].asInt() }, outcome["rejected"].asInt() + outcome["pending"].asInt() + outcome["approved"].asInt())
        }
        assertEquals(reviewStats["ALL"]["months"].sumOf { it["applications"].asInt() }, listOf("ID", "MM", "KH").sumOf { c -> reviewStats[c]["months"].sumOf { it["applications"].asInt() } })
        // Every high-risk application is listed, so a ring's members can all be shown.
        assertEquals(stats["totals"]["highRisk"].asInt(), stats["highRiskApplications"].size())
        // The fraud console has rings to show, and they stay away from excellent dealers.
        val network = ObjectMapper().readTree(mockMvc.get("/admin/fraud-network").andReturn().response.contentAsString)
        assertTrue(network["clusters"].size() >= 3 && network["clusters"].any { it["applications"].size() >= 5 }, "rings: ${network["clusters"]}")
        val excellent = roster.filter { it["tier"].asText() == "EXCELLENT" }.map { it["code"].asText() }.toSet()
        assertTrue(network["clusters"].flatMap { c -> c["dealers"].map { it.asText() } }.none { it in excellent }, "no ring through an excellent dealer")
        assertTrue(stats["highRiskApplications"].none { r -> roster.any { it["tier"].asText() == "EXCELLENT" && it["code"] == r["dealerCode"] } && r["fraudWarnings"].asInt() > 0 })
    }
}
