package com.nextasean.wawebhook.demo

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

private const val KEY = "test-admin-key"

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
        mockMvc.post("/admin/demo/seed") { header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON; content = """{"count":60,"reset":true}""" }.andExpect {
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
        mockMvc.post("/admin/applications/${waiting.applicationNumber}/decision") { header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON; content = """{"decision":"APPROVED","reviewer":"Demo"}""" }
            .andExpect { status { isOk() }; jsonPath("$.dealerNotified") { value(false) }; jsonPath("$.customerNotified") { value(false) } }
        assertTrue(sender.sent.isEmpty(), "no WhatsApp messages for demo rows")
        // Documents exist on disk and the review detail is complete.
        val completed = apps.first { it.status == ApplicationStatus.APPROVED && it.riskGrade == "A" }
        mockMvc.get("/admin/applications/${completed.applicationNumber}") { header("X-Admin-Key", KEY) }.andExpect {
            status { isOk() }
            jsonPath("$.documents[0].fileAvailable") { value(true) }
            jsonPath("$.agentTrace.length()") { value(greaterThanOrEqualTo(6)) }
            jsonPath("$.events[-2].kind") { value("APPROVED") }
            jsonPath("$.events[-1].kind") { value("REPORT_GENERATED") }
            jsonPath("$.decisionReport.available") { value(true) }
            jsonPath("$.risk.grade") { value("A") }
        }
        mockMvc.get("/admin/dashboard/stats") { header("X-Admin-Key", KEY) }.andExpect {
            status { isOk() }
            jsonPath("$.totals.demoApplications") { value(60) }
            jsonPath("$.byCountry[1].applications") { value(greaterThanOrEqualTo(1)) }
            jsonPath("$.byCountry[2].applications") { value(greaterThanOrEqualTo(1)) }
            jsonPath("$.reviewQueue.length()") { value(greaterThanOrEqualTo(1)) }
            jsonPath("$.agent.providerLookups") { value(greaterThanOrEqualTo(10)) }
        }
        mockMvc.get("/admin/demo/seed") { header("X-Admin-Key", KEY) }.andExpect { jsonPath("$.demoApplications") { value(60) } }

        val dir = Path.of("build/test-documents-demo").resolve(completed.applicationNumber)
        assertTrue(Files.isDirectory(dir))
        mockMvc.delete("/admin/demo/seed") { header("X-Admin-Key", KEY) }.andExpect { status { isOk() }; jsonPath("$.removed") { value(60) } }
        assertEquals(0, applications.findByDemoSeedTrue().size)
        assertTrue(!Files.exists(dir), "document folder removed")
    }
}
