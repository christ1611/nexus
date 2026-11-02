package com.nextasean.wawebhook.demo

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The dashboard and the review page show the same figures under the same labels, computed from the same rows. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(FakeIntegrationsConfig::class)
@TestPropertySource(properties = ["spring.datasource.url=jdbc:h2:mem:consistencytest;DB_CLOSE_DELAY=-1", "app.documents-dir=build/test-documents-consistency"])
class DashboardReviewConsistencyTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val json: ObjectMapper,
) {
    private fun read(path: String): JsonNode = json.readTree(mockMvc.get(path).andReturn().response.contentAsString)

    @Test
    fun `dashboard and review page agree on the shared numbers`() {
        mockMvc.post("/admin/demo/seed") { contentType = MediaType.APPLICATION_JSON; content = """{"count":60,"reset":true}""" }
            .andExpect { status { isOk() } }

        val dash = read("/admin/dashboard/stats")
        val review = read("/admin/review/stats")
        val list = read("/admin/applications")
        val t = dash["totals"]
        val w = review["workload"]

        assertTrue(t["readyForReview"].asInt() > 0, "the seed has a queue")
        assertEquals(t["readyForReview"].asInt(), w["readyForReview"].asInt(), "ready for review")
        assertEquals(t["readyForReview"].asInt(), dash["reviewQueue"].size(), "dashboard queue table")
        assertEquals(t["readyForReview"].asInt(), list.count { it["status"].asText() == "READY_FOR_REVIEW" }, "review page list badge")
        assertEquals(t["documentsRequested"].asInt(), w["documentsRequested"].asInt(), "documents requested")
        assertEquals(t["officerReviewRequired"].asInt(), w["officerReviewRequired"].asInt(), "officer review required")
        assertEquals(dash["reviewQueue"].count { it["officerReviewRequired"].asBoolean() }, w["officerReviewRequired"].asInt(), "officer review chips in the dashboard queue")
        assertEquals(t["oldestWaitingHours"].asDouble(), w["oldestWaitingHours"].asDouble(), 0.05, "oldest waiting")
        assertEquals(dash["reviewQueue"].maxOf { it["waitingHours"].asDouble() }, t["oldestWaitingHours"].asDouble(), 0.05, "oldest waiting is the longest wait in the queue")
        assertEquals(t["applications"].asInt(), list.size(), "all applications")
        assertEquals(t["demoApplications"].asInt(), review["demoApplications"].asInt(), "demo rows")

        // High-risk waiting per market: dashboard queue rows vs the review page's queue-by-country table.
        val byCountry = w["queueByCountry"].associate { it["code"].asText() to it }
        dash["byCountry"].forEach { c ->
            val code = c["code"].asText()
            assertEquals(c["readyForReview"].asInt(), byCountry.getValue(code)["waiting"].asInt(), "waiting in $code")
            assertEquals(
                dash["reviewQueue"].count { it["country"].asText() == code && it["grade"].asText() in setOf("C", "D") },
                byCountry.getValue(code)["highRisk"].asInt(), "high-risk waiting in $code",
            )
        }

        // Grades on the review list match the dashboard's grade distribution.
        val listGrades = list.mapNotNull { it["risk"]?.takeIf { r -> !r.isNull }?.get("grade")?.asText() }.groupingBy { it }.eachCount()
        dash["riskGrades"].forEach { g -> assertEquals(g["count"].asInt(), listGrades[g["grade"].asText()] ?: 0, "grade ${g["grade"].asText()}") }
    }
}
