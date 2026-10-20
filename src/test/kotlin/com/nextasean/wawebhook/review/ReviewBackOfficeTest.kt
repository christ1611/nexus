package com.nextasean.wawebhook.review

import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.conversation.ReplyIds
import com.nextasean.wawebhook.customer.CustomerReplyIds
import com.nextasean.wawebhook.customer.CustomerSessionRepository
import com.nextasean.wawebhook.customer.CustomerSessionState
import com.nextasean.wawebhook.customer.findActiveByPhone
import com.nextasean.wawebhook.documents.DocumentRepository
import com.nextasean.wawebhook.documents.DocumentType
import com.nextasean.wawebhook.trace.TraceKind
import com.nextasean.wawebhook.webhook.FakeIntegrationsConfig
import com.nextasean.wawebhook.webhook.RecordingSender
import com.nextasean.wawebhook.webhook.WebhookProcessor
import com.nextasean.wawebhook.webhook.bodyText
import com.nextasean.wawebhook.webhook.imageMessagePayload
import com.nextasean.wawebhook.webhook.interactivePayload
import com.nextasean.wawebhook.webhook.textMessagePayload
import com.nextasean.wawebhook.whatsapp.OutboundMessage
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DEALER = "6282222222222"
private const val CUSTOMER = "6287000000001"
private const val KEY = "test-admin-key"

/** Step 4: the reviewer back office - documents, officer document re-requests, decisions with customer notification, audit trail. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(FakeIntegrationsConfig::class)
@TestPropertySource(properties = ["spring.datasource.url=jdbc:h2:mem:reviewtest;DB_CLOSE_DELAY=-1"])
class ReviewBackOfficeTest @Autowired constructor(
    private val processor: WebhookProcessor,
    private val sender: RecordingSender,
    private val applications: ApplicationRepository,
    private val sessions: CustomerSessionRepository,
    private val documents: DocumentRepository,
    private val events: ReviewEventRepository,
    private val trace: com.nextasean.wawebhook.trace.AgentTraceService,
    private val mockMvc: MockMvc,
) {
    private fun id() = "wamid." + UUID.randomUUID()
    private fun text(from: String, body: String) = processor.process(textMessagePayload(id(), from, body).toByteArray())
    private fun button(from: String, replyId: String) = processor.process(interactivePayload(id(), from, "button_reply", replyId, replyId).toByteArray())
    private fun image(from: String, mediaId: String, caption: String = "") = processor.process(imageMessagePayload(id(), from, mediaId, caption).toByteArray())
    private fun lastTo(phone: String) = sender.sent.last { it.first == phone }.second

    private fun createApplication(): String {
        text(DEALER, "menu")
        button(DEALER, ReplyIds.MENU_NEW)
        button(DEALER, ReplyIds.NEW_CONFIRM)
        return Regex("START%20(APP-\\d{4}-\\d{6})").find(lastTo(DEALER).bodyText)!!.groupValues[1]
    }

    private fun onboard(customer: String, ktpCaption: String = ""): String {
        val number = createApplication()
        text(customer, "START $number")
        button(customer, CustomerReplyIds.CONSENT_AGREE)
        image(customer, "ktp-" + UUID.randomUUID(), ktpCaption)
        image(customer, "selfie-" + UUID.randomUUID())
        return number
    }

    @Test
    fun `officer sees documents and evidence, sends the customer back for the KTP, then approves and both sides are told`() {
        sender.clear()
        val number = onboard(CUSTOMER)
        val app = applications.findByApplicationNumber(number)!!
        assertEquals(ApplicationStatus.READY_FOR_REVIEW, app.status)
        assertEquals("A", app.riskGrade)

        // Work queue lists it first with both accepted documents; the detail view carries documents, risk and the timeline.
        mockMvc.get("/admin/applications?queue=true") { header("X-Admin-Key", KEY) }.andExpect {
            status { isOk() }
            jsonPath("$[0].applicationNumber") { value(number) }
            jsonPath("$[0].documentCount") { value(2) }
            jsonPath("$[0].statusLabel") { value(ApplicationStatus.READY_FOR_REVIEW.label) }
        }
        val ktp = documents.findByApplicationIdOrderByCreatedAtAsc(app.id!!).first { it.type == DocumentType.KTP }
        mockMvc.get("/admin/applications/$number") { header("X-Admin-Key", KEY) }.andExpect {
            status { isOk() }
            jsonPath("$.documents", hasSize<Any>(2))
            jsonPath("$.documents[0].id") { value(ktp.id!!) }
            jsonPath("$.documents[0].fileUrl") { value("/admin/applications/$number/documents/${ktp.id}/file") }
            jsonPath("$.documents[0].fileAvailable") { value(true) }
            jsonPath("$.risk.grade") { value("A") }
            jsonPath("$.events[*].kind") { value(hasItem(ReviewEventKind.DOCUMENTS_COMPLETE.name)) }
            jsonPath("$.events[*].kind") { value(hasItem(ReviewEventKind.RISK_ASSESSED.name)) }
            jsonPath("$.agentTrace[0].kind") { value("DOCUMENT_RECEIVED") }
            jsonPath("$.agentTrace[0].actor") { value("customer") }
            jsonPath("$.agentTrace[1].kind") { value("QUALITY_CHECK") }
            jsonPath("$.agentTrace[1].labelKo") { value("품질 확인") }
            jsonPath("$.agentTrace[-1].kind") { value("SUBMITTED") }
        }
        // The timeline can be polled on its own; it says whether the application is still live.
        mockMvc.get("/admin/applications/$number/agent-trace") { header("X-Admin-Key", KEY) }.andExpect {
            status { isOk() }
            jsonPath("$.live") { value(true) }
            jsonPath("$.steps[*].kind") { value(hasItem("RISK_ASSESSMENT")) }
        }
        // The stored image is served behind the admin key, with its MIME type.
        mockMvc.get("/admin/applications/$number/documents/${ktp.id}/file") { header("X-Admin-Key", KEY) }.andExpect {
            status { isOk() }
            content { contentType(MediaType.IMAGE_JPEG) }
            content { string("fake-image-bytes-${ktp.mediaId}") }
        }
        mockMvc.get("/admin/applications/$number/documents/${ktp.id}/file").andExpect { status { isUnauthorized() } }
        mockMvc.get("/admin/applications/$number/documents/999999/file") { header("X-Admin-Key", KEY) }.andExpect { status { isNotFound() } }

        // Officer asks the customer for the KTP again: customer and dealer are messaged, the session reopens at step 1.
        sender.clear()
        mockMvc.post("/admin/applications/$number/request-documents") {
            header("X-Admin-Key", KEY)
            contentType = MediaType.APPLICATION_JSON
            content = """{"items":[{"type":"KTP","reason":"the photo is blurry"}],"note":"Please use good light.","reviewer":"Officer Kim"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.application.status") { value("DOCUMENTS_REQUESTED") }
            jsonPath("$.customerNotified") { value(true) }
            jsonPath("$.dealerNotified") { value(true) }
            jsonPath("$.application.events[*].kind") { value(hasItem(ReviewEventKind.DOCUMENTS_REQUESTED.name)) }
            jsonPath("$.application.events[-1].actor") { value("Officer Kim") }
        }
        val customerMsg = lastTo(CUSTOMER).bodyText
        assertTrue(customerMsg.contains("needs the following again") && customerMsg.contains("KTP: the photo is blurry") && customerMsg.contains("Please use good light.") && customerMsg.contains("Step 1 of 2"), customerMsg)
        val dealerMsg = lastTo(DEALER) as OutboundMessage.Buttons
        assertTrue(dealerMsg.body.contains("asked the customer") && dealerMsg.body.contains("KTP: the photo is blurry"), dealerMsg.body)
        val requested = applications.findByApplicationNumber(number)!!
        assertEquals(ApplicationStatus.DOCUMENTS_REQUESTED, requested.status)
        assertNull(requested.extractedNik)
        assertEquals(1, requested.reviewRounds)
        assertEquals(CustomerSessionState.AWAITING_KTP, sessions.findActiveByPhone(CUSTOMER)!!.state)
        assertNotNull(sessions.findActiveByPhone(CUSTOMER)!!.consentAt)
        assertTrue(documents.findById(ktp.id!!).get().superseded)

        // The dashboard counts it as "documents requested" and not as waiting for review.
        mockMvc.get("/admin/dashboard/stats") { header("X-Admin-Key", KEY) }.andExpect {
            status { isOk() }
            jsonPath("$.totals.documentsRequested") { value(1) }
            jsonPath("$.reviewQueue[*].applicationNumber") { value(not(hasItem(number))) }
            jsonPath("$.trend", hasSize<Any>(8))
        }

        // Customer resends only the KTP (selfie is still valid); checks and verification run again.
        sender.clear()
        image(CUSTOMER, "ktp-again", "nik1")
        val replies = sender.sent.filter { it.first == CUSTOMER }.map { it.second.bodyText }
        assertTrue(replies.any { it.contains("Your documents are submitted") }, replies.toString())
        val resubmitted = applications.findByApplicationNumber(number)!!
        assertEquals(ApplicationStatus.READY_FOR_REVIEW, resubmitted.status)
        assertEquals("3171051705900001", resubmitted.extractedNik)
        assertEquals("B", resubmitted.riskGrade)
        assertEquals(3, documents.findByApplicationIdOrderByCreatedAtAsc(app.id).size)
        assertEquals(1, documents.findByApplicationIdOrderByCreatedAtAsc(app.id).count { it.superseded })
        mockMvc.get("/admin/dashboard/stats") { header("X-Admin-Key", KEY) }.andExpect {
            jsonPath("$.reviewQueue[*].applicationNumber") { value(hasItem(number)) }
        }

        // Officer note and approval; dealer and customer both get the decision; metadata and timeline are stored.
        mockMvc.post("/admin/applications/$number/note") {
            header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON
            content = """{"note":"Second KTP is fine","reviewer":"Officer Kim"}"""
        }.andExpect { status { isOk() }; jsonPath("$.events[*].kind") { value(hasItem(ReviewEventKind.NOTE_ADDED.name)) } }

        sender.clear()
        mockMvc.post("/admin/applications/$number/decision") {
            header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON
            content = """{"decision":"APPROVED","note":"Welcome aboard","reviewer":"Officer Kim"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.application.status") { value("APPROVED") }
            jsonPath("$.application.decidedBy") { value("Officer Kim") }
            jsonPath("$.dealerNotified") { value(true) }
            jsonPath("$.customerNotified") { value(true) }
        }
        val customerDecision = lastTo(CUSTOMER).bodyText
        assertTrue(customerDecision.contains("APPROVED") && customerDecision.contains("Welcome aboard"), customerDecision)
        assertTrue((lastTo(DEALER) as OutboundMessage.Buttons).header!!.contains("approved"))
        val decided = applications.findByApplicationNumber(number)!!
        assertNotNull(decided.decidedAt)
        assertEquals(
            listOf(ReviewEventKind.DOCUMENTS_COMPLETE, ReviewEventKind.RISK_ASSESSED, ReviewEventKind.DOCUMENTS_REQUESTED, ReviewEventKind.DOCUMENTS_COMPLETE,
                ReviewEventKind.RISK_ASSESSED, ReviewEventKind.NOTE_ADDED, ReviewEventKind.APPROVED),
            events.findByApplicationIdOrderByCreatedAtAsc(app.id).map { it.kind },
        )

        // Final applications cannot be sent back, drop out of the open queue, and are found by the status filter.
        mockMvc.post("/admin/applications/$number/request-documents") {
            header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON
            content = """{"items":[{"type":"SELFIE_KTP","reason":"x"}]}"""
        }.andExpect { status { isConflict() } }
        mockMvc.get("/admin/applications?queue=true") { header("X-Admin-Key", KEY) }.andExpect { jsonPath("$[*].applicationNumber") { value(not(hasItem(number))) } }
        mockMvc.get("/admin/applications?status=approved&grade=B&q=${number.takeLast(4)}") { header("X-Admin-Key", KEY) }.andExpect { jsonPath("$[*].applicationNumber") { value(hasItem(number)) } }
        mockMvc.get("/admin/dashboard/stats") { header("X-Admin-Key", KEY) }.andExpect {
            jsonPath("$.totals.avgDecisionHours") { isNumber() }
            jsonPath("$.agent.providerLookups") { value(greaterThanOrEqualTo(4)) }
            jsonPath("$.agent.customerRetakes") { value(greaterThanOrEqualTo(0)) }
        }
        mockMvc.get("/admin/applications/$number/agent-trace") { header("X-Admin-Key", KEY) }.andExpect {
            jsonPath("$.live") { value(false) }
            jsonPath("$.steps[*].kind") { value(hasItem("OFFICER_REQUEST")) }
            jsonPath("$.steps[-1].kind") { value("OFFICER_DECISION") }
            jsonPath("$.steps[-1].actor") { value("officer:Officer Kim") }
        }
    }

    @Test
    fun `a final decision closes the customer session, and a photo sent afterwards cannot re-open the case`() {
        val customer = "6287000000002"
        sender.clear()
        val number = onboard(customer)
        // Officer sends the customer back, then rejects before the customer answers.
        mockMvc.post("/admin/applications/$number/request-documents") {
            header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON
            content = """{"items":[{"type":"KTP","reason":"the photo is blurry"}],"reviewer":"Officer Lee"}"""
        }.andExpect { status { isOk() } }
        assertEquals(CustomerSessionState.AWAITING_KTP, sessions.findActiveByPhone(customer)!!.state)
        mockMvc.post("/admin/applications/$number/decision") {
            header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON
            content = """{"decision":"REJECTED","note":"Could not verify","reviewer":"Officer Lee"}"""
        }.andExpect { status { isOk() }; jsonPath("$.application.status") { value("REJECTED") } }
        assertNull(sessions.findActiveByPhone(customer), "decision closes the open customer session")

        // A late photo does not run the pipeline again; the decision stands and the customer is told.
        sender.clear()
        image(customer, "late-ktp")
        val app = applications.findByApplicationNumber(number)!!
        assertEquals(ApplicationStatus.REJECTED, app.status)
        assertEquals("Officer Lee", app.decidedBy)
        assertTrue(sender.sent.none { it.first == DEALER }, "dealer is not told about documents on a decided application")
        // With no open session the photo falls through to the dealer flow's polite rejection; it is never treated as a document.
        assertTrue(sender.sent.filter { it.first == customer }.none { it.second.bodyText.startsWith("Received:") }, "photo not accepted as a document")
        assertTrue(trace.steps(app.id).none { it.kind == TraceKind.DOCUMENT_RECEIVED && it.createdAt.isAfter(app.decidedAt) }, "no pipeline steps after the decision")

        // Risk cannot be assessed on an empty draft; documents cannot be requested while the dealer form is pending.
        val draft = createApplication()
        mockMvc.post("/admin/applications/$draft/assess-risk") { header("X-Admin-Key", KEY) }.andExpect { status { isConflict() } }
        mockMvc.post("/admin/applications/$draft/request-resubmission") {
            header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON; content = """{"reason":"income proof missing"}"""
        }.andExpect { status { isOk() } }
        text(customer, "START $draft")
        mockMvc.post("/admin/applications/$draft/request-documents") {
            header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON; content = """{"items":[{"type":"KTP","reason":"x"}]}"""
        }.andExpect { status { isConflict() } }
    }

    @Test
    fun `documents cannot be requested before a customer is linked, and the review page is public while its data is not`() {
        val number = createApplication()
        mockMvc.post("/admin/applications/$number/request-documents") {
            header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON
            content = """{"items":[{"type":"KTP","reason":"blurry"}]}"""
        }.andExpect { status { isConflict() }; jsonPath("$.error") { value(containsString("no customer phone")) } }
        mockMvc.post("/admin/applications/$number/request-documents") {
            header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON
            content = """{"items":[]}"""
        }.andExpect { status { isBadRequest() } }

        mockMvc.get("/review").andExpect {
            status { isOk() }
            content { string(containsString("NEXT ASEAN 심사")) }
        }
        mockMvc.get("/admin/applications?queue=true").andExpect { status { isUnauthorized() } }
    }
}
