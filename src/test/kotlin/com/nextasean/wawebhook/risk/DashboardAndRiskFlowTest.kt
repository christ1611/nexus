package com.nextasean.wawebhook.risk

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
    fun `dashboard page is public but its data needs the admin key`() {
        mockMvc.get("/dashboard").andExpect {
            status { isOk() }
            content { string(containsString("NEXT ASEAN 온보딩 대시보드")) }
        }
        mockMvc.get("/admin/dashboard/stats").andExpect { status { isUnauthorized() } }
    }
}
