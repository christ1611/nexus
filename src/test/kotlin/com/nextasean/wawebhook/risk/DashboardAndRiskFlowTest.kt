package com.nextasean.wawebhook.risk

import com.nextasean.wawebhook.admin.DashboardController
import com.nextasean.wawebhook.admin.DashboardCopilotService
import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.conversation.ReplyIds
import com.nextasean.wawebhook.customer.CustomerReplyIds
import com.nextasean.wawebhook.dealer.DealerRepository
import com.nextasean.wawebhook.dealer.DealerTier
import com.nextasean.wawebhook.webhook.FakeIntegrationsConfig
import com.nextasean.wawebhook.webhook.RecordingSender
import com.nextasean.wawebhook.webhook.WebhookProcessor
import com.nextasean.wawebhook.webhook.bodyText
import com.nextasean.wawebhook.webhook.imageMessagePayload
import com.nextasean.wawebhook.webhook.interactivePayload
import com.nextasean.wawebhook.webhook.textMessagePayload
import com.nextasean.wawebhook.whatsapp.OutboundMessage
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.hasItem
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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Dealer two (EXCELLENT) is in Indonesia; dealer three is the Myanmar demo dealer. Customers use phones no other test touches. */
private const val DEALER_ID = "6282222222222"
private const val DEALER_MM = "6285555555555"
private const val GOOD_CUSTOMER = "6286000000001"
private const val RISKY_CUSTOMER = "6286000000002"
private const val MM_CUSTOMER = "6286000000003"
private const val FIRST_CUSTOMER = "6286000000004"
private const val COPYCAT_CUSTOMER = "6286000000005"

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(FakeIntegrationsConfig::class)
// Own in-memory database: this test creates several applications and the older flow tests count rows with single()/size.
@TestPropertySource(properties = ["spring.datasource.url=jdbc:h2:mem:dashboardtest;DB_CLOSE_DELAY=-1"])
class DashboardAndRiskFlowTest @Autowired constructor(
    private val processor: WebhookProcessor,
    private val sender: RecordingSender,
    private val applications: ApplicationRepository,
    private val dealers: DealerRepository,
    private val mockMvc: MockMvc,
    private val dashboard: DashboardController,
    private val copilot: DashboardCopilotService,
) {
    private fun id() = "wamid." + UUID.randomUUID()
    private fun text(from: String, body: String) = processor.process(textMessagePayload(id(), from, body).toByteArray())
    private fun button(from: String, replyId: String) = processor.process(interactivePayload(id(), from, "button_reply", replyId, replyId).toByteArray())
    private fun image(from: String, mediaId: String, caption: String = "") = processor.process(imageMessagePayload(id(), from, mediaId, caption).toByteArray())

    /** Runs dealer -> customer -> KTP -> selfie and returns the application number. */
    private fun onboard(dealer: String, customer: String, ktpCaption: String): String {
        sender.clear()
        text(dealer, "menu")
        button(dealer, ReplyIds.MENU_NEW)
        button(dealer, ReplyIds.NEW_CONFIRM)
        val number = Regex("START%20(APP-\\d{4}-\\d{6})").find(sender.sent.last { it.first == dealer }.second.bodyText)!!.groupValues[1]
        text(customer, "START $number")
        button(customer, CustomerReplyIds.CONSENT_AGREE)
        image(customer, "ktp-" + UUID.randomUUID(), ktpCaption)
        image(customer, "selfie-" + UUID.randomUUID())
        return number
    }

    @Test
    fun `happy path is grade A with badges, nik7 is a high-risk fraud flag, and both reach the dashboard`() {
        assertEquals(DealerTier.EXCELLENT, dealers.findByPhone(DEALER_ID)!!.tier)

        val good = onboard(DEALER_ID, GOOD_CUSTOMER, "")
        val goodApp = applications.findByApplicationNumber(good)!!
        assertEquals("A", goodApp.riskGrade)
        assertEquals("ID", goodApp.country)
        assertTrue(goodApp.customerBadges!!.contains(CustomerBadge.FINANCIAL_STABILITY.name), goodApp.customerBadges)
        assertTrue(goodApp.customerBadges!!.contains(CustomerBadge.EXCELLENT_DEALER_REFERRAL.name), goodApp.customerBadges)
        val goodSummary = sender.sent.last { it.first == DEALER_ID }.second as OutboundMessage.Buttons
        assertTrue(goodSummary.body.contains("Risk grade: *A*"), goodSummary.body)
        assertTrue(goodSummary.body.contains("Strong financial consistency"), goodSummary.body)

        val risky = onboard(DEALER_ID, RISKY_CUSTOMER, "nik7")
        val riskyApp = applications.findByApplicationNumber(risky)!!
        assertEquals("C", riskyApp.riskGrade)
        assertTrue(riskyApp.riskFlagsJson!!.contains(RiskCodes.IDENTITY_MISMATCH))
        val riskySummary = sender.sent.last { it.first == DEALER_ID }.second as OutboundMessage.Buttons
        assertTrue(riskySummary.body.contains("fraud/forgery warning"), riskySummary.body)

        val mm = onboard(DEALER_MM, MM_CUSTOMER, "")
        val mmApp = applications.findByApplicationNumber(mm)!!
        assertEquals("MM", mmApp.country)
        assertTrue(mmApp.idVerificationDetail!!.startsWith("Myanmar NRC registry"), mmApp.idVerificationDetail)
        assertNotNull(mmApp.riskGrade)

        // Admin view carries the risk block.
        mockMvc.get("/admin/applications/$risky") { header("X-Admin-Key", "test-admin-key") }.andExpect {
            status { isOk() }
            jsonPath("$.risk.grade") { value("C") }
            jsonPath("$.risk.highRisk") { value(true) }
            jsonPath("$.risk.fraudWarnings") { value(1) }
            jsonPath("$.risk.flags[*].code") { value(hasItem(RiskCodes.IDENTITY_MISMATCH)) }
            jsonPath("$.country") { value("ID") }
        }

        // Dashboard statistics: all three countries are always listed; counts reflect what we just created (other tests may add more).
        mockMvc.get("/admin/dashboard/stats") { header("X-Admin-Key", "test-admin-key") }.andExpect {
            status { isOk() }
            jsonPath("$.countries") { value(contains("ID", "MM", "KH")) }
            jsonPath("$.totals.highRisk") { value(greaterThanOrEqualTo(1)) }
            jsonPath("$.totals.fraudWarnings") { value(greaterThanOrEqualTo(1)) }
            jsonPath("$.byCountry[0].code") { value("ID") }
            jsonPath("$.byCountry[0].applications") { value(greaterThanOrEqualTo(2)) }
            jsonPath("$.byCountry[0].highRisk") { value(greaterThanOrEqualTo(1)) }
            jsonPath("$.byCountry[0].badges.EXCELLENT_DEALER_REFERRAL") { value(greaterThanOrEqualTo(2)) }
            jsonPath("$.byCountry[1].code") { value("MM") }
            jsonPath("$.byCountry[1].applications") { value(greaterThanOrEqualTo(1)) }
            jsonPath("$.byCountry[2].code") { value("KH") }
            jsonPath("$.byCountry[2].applications") { value(0) }
            jsonPath("$.highRiskApplications[*].applicationNumber") { value(hasItem(risky)) }
            jsonPath("$.flagCounts[*].code") { value(hasItem(RiskCodes.IDENTITY_MISMATCH)) }
            jsonPath("$.riskGrades[0].grade") { value("A") }
            jsonPath("$.funnel[0].stageKo") { value("딜러 신청 생성") }
        }

        // Re-running the rules is idempotent and reachable from the admin API.
        mockMvc.post("/admin/applications/$risky/assess-risk") { header("X-Admin-Key", "test-admin-key") }.andExpect {
            status { isOk() }
            jsonPath("$.risk.grade") { value("C") }
        }
        mockMvc.post("/admin/risk/reassess-all") { header("X-Admin-Key", "test-admin-key") }.andExpect {
            status { isOk() }
            jsonPath("$.reassessed") { value(greaterThanOrEqualTo(3)) }
        }
    }

    @Test
    fun `the dashboard copilot summarises the portfolio from the statistics without an API key`() {
        onboard(DEALER_ID, "6286000000006", "")
        val ask = { body: String ->
            mockMvc.post("/admin/dashboard/copilot") { header("X-Admin-Key", "test-admin-key"); contentType = MediaType.APPLICATION_JSON; content = body }
        }
        // Stub mode: rule-based summaries built from the same statistics the page shows, by topic of the question.
        ask("""{"question":"국가별 현황을 요약해줘"}""").andExpect {
            status { isOk() }
            jsonPath("$.mode") { value("rules") }
            jsonPath("$.answer") { value(containsString("인도네시아")) }
        }
        ask("""{"question":"심사 대기 큐에서 먼저 볼 신청은?","history":[{"role":"user","content":"현황"},{"role":"assistant","content":"요약"}]}""").andExpect {
            status { isOk() }
            jsonPath("$.answer") { value(containsString("APP-")) }
        }
        ask("""{"question":"what did the AI agents do?"}""").andExpect { status { isOk() }; jsonPath("$.answer") { value(containsString("비전 분석")) } }
        ask("""{"question":"   "}""").andExpect { status { isBadRequest() } }
        mockMvc.post("/admin/dashboard/copilot") { contentType = MediaType.APPLICATION_JSON; content = """{"question":"x"}""" }
            .andExpect { status { isUnauthorized() } }
        // The portfolio file the model would receive carries every section of the dashboard and no customer names.
        val file = copilot.portfolioFile(dashboard.stats())
        for (section in listOf("## Snapshot", "## Review queue", "## Weekly trend", "## Countries", "## Risk flags", "## Dealers", "## AI agent activity"))
            assertTrue(file.contains(section), "missing $section in:\n$file")
    }

    @Test
    fun `dashboard page is public but its data needs the admin key`() {
        mockMvc.get("/dashboard").andExpect {
            status { isOk() }
            content { string(containsString("NEXT ASEAN 온보딩 대시보드")) }
        }
        mockMvc.get("/admin/dashboard/stats").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `two customers submitting the same ID number are both flagged, and different customers never collide by accident`() {
        val first = onboard(DEALER_ID, FIRST_CUSTOMER, "")
        val firstApp = applications.findByApplicationNumber(first)!!
        assertEquals("A", firstApp.riskGrade, "a fresh customer has a unique sample NIK: " + firstApp.riskFlagsJson)
        assertTrue(firstApp.extractedNik!!.endsWith("4"))
        assertEquals(first.takeLast(3), firstApp.extractedNik!!.substring(12, 15), "serial digits come from the application number")

        // The caption "dup" makes the stub submit the previous customer's NIK: a different person with the same ID card.
        val copycat = onboard(DEALER_ID, COPYCAT_CUSTOMER, "dup")
        val copycatApp = applications.findByApplicationNumber(copycat)!!
        assertEquals(firstApp.extractedNik, copycatApp.extractedNik)
        assertTrue(copycatApp.riskFlagsJson!!.contains(RiskCodes.SHARED_ID_NUMBER), copycatApp.riskFlagsJson)
        assertTrue(copycatApp.riskFlagsJson!!.contains(first), "the other application number is in the evidence: " + copycatApp.riskFlagsJson)
        assertTrue(copycatApp.riskGrade == "C" || copycatApp.riskGrade == "D", copycatApp.riskGrade)
        val summary = sender.sent.last { it.first == DEALER_ID }.second as OutboundMessage.Buttons
        assertTrue(summary.body.contains("fraud/forgery warning"), summary.body)

        // The first application is still open, so it is re-assessed and now carries the warning as well.
        val firstAgain = applications.findByApplicationNumber(first)!!
        assertTrue(firstAgain.riskFlagsJson!!.contains(RiskCodes.SHARED_ID_NUMBER), firstAgain.riskFlagsJson)
        assertTrue(firstAgain.riskFlagsJson!!.contains(copycat), firstAgain.riskFlagsJson)
        assertTrue(firstAgain.riskGrade == "C" || firstAgain.riskGrade == "D", firstAgain.riskGrade)

        mockMvc.get("/admin/applications/$copycat") { header("X-Admin-Key", "test-admin-key") }.andExpect {
            status { isOk() }
            jsonPath("$.risk.flags[*].code") { value(hasItem(RiskCodes.SHARED_ID_NUMBER)) }
            jsonPath("$.risk.flags[?(@.code == '${RiskCodes.SHARED_ID_NUMBER}')].sourceKo") { value(hasItem("중복 신청 조회")) }
        }
        mockMvc.get("/admin/dashboard/stats") { header("X-Admin-Key", "test-admin-key") }.andExpect {
            status { isOk() }
            jsonPath("$.flagCounts[?(@.code == '${RiskCodes.SHARED_ID_NUMBER}')].count") { value(hasItem(2)) }
            jsonPath("$.flagCounts[?(@.code == '${RiskCodes.SHARED_ID_NUMBER}')].fraud") { value(hasItem(true)) }
        }
    }
}
