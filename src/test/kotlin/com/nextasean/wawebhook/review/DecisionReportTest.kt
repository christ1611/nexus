package com.nextasean.wawebhook.review

import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.webhook.FakeIntegrationsConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val KEY = "test-admin-key"

/** Every decision leaves a stored evidence report (PDF); before the decision the same endpoint renders a draft. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(FakeIntegrationsConfig::class)
@TestPropertySource(properties = ["spring.datasource.url=jdbc:h2:mem:decisionreporttest;DB_CLOSE_DELAY=-1", "app.documents-dir=build/test-documents-report", "app.reports-dir=build/test-reports"])
class DecisionReportTest @Autowired constructor(
    private val applications: ApplicationRepository,
    private val events: ReviewEventRepository,
    private val mockMvc: MockMvc,
) {
    @Test
    fun `a decision stores a PDF report and the endpoint serves it`() {
        Path.of("build/test-reports").takeIf { Files.isDirectory(it) }?.let { root -> Files.walk(root).use { w -> w.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
        mockMvc.post("/admin/demo/seed") { header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON; content = """{"count":20,"reset":true}""" }.andExpect { status { isOk() } }
        val waiting = applications.findByDemoSeedTrue().first { it.status == ApplicationStatus.READY_FOR_REVIEW && it.customerPhone != null && it.riskGrade != null }
        val n = waiting.applicationNumber

        // Before the decision: a draft, rendered on demand.
        val draft = mockMvc.get("/admin/applications/$n/decision-report.pdf") { header("X-Admin-Key", KEY) }
            .andExpect { status { isOk() }; header { string("Content-Disposition", org.hamcrest.Matchers.containsString("draft")) } }
            .andReturn().response.contentAsByteArray
        assertTrue(String(draft, Charsets.ISO_8859_1).startsWith("%PDF-"), "draft is a PDF")
        mockMvc.get("/admin/applications/$n") { header("X-Admin-Key", KEY) }.andExpect { jsonPath("$.decisionReport.available") { value(false) } }

        // The decision writes the report and notes it on the timeline.
        mockMvc.post("/admin/applications/$n/decision") { header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON; content = """{"decision":"APPROVED","note":"All checks clear","reviewer":"Officer Test"}""" }
            .andExpect { status { isOk() }; jsonPath("$.application.decisionReport.available") { value(true) } }
        val dir = Path.of("build/test-reports").resolve(n)
        assertTrue(Files.isDirectory(dir), "report folder created")
        val files = dir.listDirectoryEntries("decision-report-*.pdf")
        assertEquals(1, files.size, "one stored report")
        assertTrue(Files.size(files.first()) > 2_000, "report has content")
        assertTrue(events.findByApplicationIdOrderByCreatedAtAsc(waiting.id!!).any { it.kind == ReviewEventKind.REPORT_GENERATED }, "timeline notes the report")

        // After the decision the endpoint serves the stored file, not a draft.
        val stored = mockMvc.get("/admin/applications/$n/decision-report.pdf") { header("X-Admin-Key", KEY) }
            .andExpect { status { isOk() }; header { string("Content-Disposition", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("draft"))) } }
            .andReturn().response.contentAsByteArray
        assertEquals(Files.size(files.first()), stored.size.toLong(), "served bytes are the stored file")
        mockMvc.get("/admin/applications/$n/decision-report.pdf").andExpect { status { isUnauthorized() } }
    }
}
