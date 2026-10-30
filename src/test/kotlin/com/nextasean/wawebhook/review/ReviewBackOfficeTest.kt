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
import com.nextasean.wawebhook.webhook.statusPayload
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
    private val copilot: ReviewCopilotService,
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
        assertTrue(resubmitted.extractedNik!!.startsWith("317105170590") && resubmitted.extractedNik!!.endsWith("1"), resubmitted.extractedNik)
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
                ReviewEventKind.RISK_ASSESSED, ReviewEventKind.NOTE_ADDED, ReviewEventKind.APPROVED, ReviewEventKind.REPORT_GENERATED),
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
    fun `the copilot explains the stored score from the case file without an API key, and keeps the conversation`() {
        sender.clear()
        val number = onboard("6287000000031")   // own phone: a shared phone would raise cross-check flags on the other tests' applications
        val ask = { body: String ->
            mockMvc.post("/admin/applications/$number/copilot") { header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON; content = body }
        }
        // Stub mode: rule-based answers built from the stored assessment, never from the model.
        ask("""{"question":"왜 이 점수가 나왔어?","reviewer":"Kim"}""").andExpect {
            status { isOk() }
            jsonPath("$.mode") { value("rules") }
            jsonPath("$.answer") { value(containsString("등급 A")) }
            jsonPath("$.answer") { value(containsString("0 / 100")) }
        }
        ask("""{"question":"승인해도 될까?","history":[{"role":"user","content":"왜 이 점수가 나왔어?"},{"role":"assistant","content":"등급 A"}]}""").andExpect {
            status { isOk() }
            jsonPath("$.answer") { value(containsString("승인")) }
        }
        ask("""{"question":"   "}""").andExpect { status { isBadRequest() } }
        mockMvc.post("/admin/applications/APP-0000-000000/copilot") { header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON; content = """{"question":"x"}""" }
            .andExpect { status { isNotFound() } }
        mockMvc.post("/admin/applications/$number/copilot") { contentType = MediaType.APPLICATION_JSON; content = """{"question":"x"}""" }
            .andExpect { status { isUnauthorized() } }
        // The case file the model would receive masks the ID number and carries the flags and timeline.
        val app = applications.findByApplicationNumber(number)!!
        // Stub mode still runs the rule-based review agent, so the officer always gets a summary.
        assertEquals("SUMMARY_WRITTEN", app.reviewDecision)
        assertTrue(app.reviewSummary!!.contains("DOCUMENT INTAKE SUMMARY"), app.reviewSummary!!)
        assertTrue(app.reviewSummaryKo!!.contains("서류 접수 요약"), "the same summary is stored in Korean: " + app.reviewSummaryKo)
        val caseFile = copilot.caseFile(app)
        assertTrue(caseFile.contains("Score 0 / 100"), caseFile)
        assertTrue(caseFile.contains("## AI agent timeline"), caseFile)
        assertTrue(!caseFile.contains(app.extractedNik!!), "NIK must be masked")
        assertTrue(caseFile.contains(app.extractedNik!!.takeLast(4)))
        // Close the case so it does not sit at the top of the queue for the other tests.
        mockMvc.post("/admin/applications/$number/decision") { header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON; content = """{"decision":"APPROVED","reviewer":"Kim"}""" }
            .andExpect { status { isOk() } }
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
            content { string(containsString("NEXUS ASEAN 심사")) }
        }
        mockMvc.get("/admin/applications?queue=true").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `back office creates an application and invites the customer, who then starts with any message`() {
        sender.clear()
        val customer = "6287000000021"
        val body = mockMvc.post("/admin/applications") {
            header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON
            content = """{"customerPhone":"+62 870-0000-0021","customerName":"Dewi","reviewer":"Officer Demo"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.application.customerPhone") { value(customer) }
            jsonPath("$.application.customerName") { value("Dewi") }
            jsonPath("$.application.status") { value("DRAFT") }
            jsonPath("$.customerInvited") { value(true) }
            jsonPath("$.inviteSent") { value(true) }
            jsonPath("$.startLink") { value(containsString("wa.me/15550000000?text=START%20APP-")) }
            jsonPath("$.formLink") { value(containsString("/apply/")) }
            jsonPath("$.application.events[*].kind") { value(hasItem(ReviewEventKind.CREATED.name)) }
        }.andReturn().response.contentAsString
        val number = Regex("APP-\\d{4}-\\d{6}").find(body)!!.value
        val invitation = lastTo(customer).bodyText
        assertTrue(invitation.contains(number) && invitation.contains("START"), invitation)
        assertTrue(sender.sent.none { it.first == DEALER }, "the dealer phone is not involved")

        // Any first message from the invited phone starts the journey (no START <number> needed).
        sender.clear()
        text(customer, "hi")
        assertTrue(lastTo(customer).bodyText.contains("Do you agree?"), lastTo(customer).bodyText)
        assertEquals(number, applications.findById(sessions.findActiveByPhone(customer)!!.applicationId).get().applicationNumber)
        button(customer, CustomerReplyIds.CONSENT_AGREE)
        image(customer, "ktp-" + UUID.randomUUID())
        image(customer, "selfie-" + UUID.randomUUID())
        assertEquals(ApplicationStatus.READY_FOR_REVIEW, applications.findByApplicationNumber(number)!!.status)
        assertTrue(lastTo(customer).bodyText.contains("Your documents are submitted"), lastTo(customer).bodyText)

        // Validation and the unknown-dealer case.
        mockMvc.post("/admin/applications") { header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON; content = """{"customerPhone":"12"}""" }
            .andExpect { status { isBadRequest() } }
        mockMvc.post("/admin/applications") { header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON; content = """{"dealerCode":"DLR-NOPE"}""" }
            .andExpect { status { isNotFound() } }
        mockMvc.post("/admin/applications").andExpect { status { isUnauthorized() } }

        // Clean up: decide it so the queue is as other tests expect.
        mockMvc.post("/admin/applications/$number/decision") {
            header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON
            content = """{"decision":"APPROVED","reviewer":"Officer Demo"}"""
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `a WhatsApp delivery failure reported by Meta lands on the invited customer's timeline`() {
        val phone = "6281200000000" // recipient used by statusPayload()
        val body = mockMvc.post("/admin/applications") {
            header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON
            content = """{"customerPhone":"$phone","customerName":"Rina","invite":true}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val number = Regex("APP-\\d{4}-\\d{6}").find(body)!!.value

        processor.process(statusPayload("wamid.invite-1", "failed", withError = true).toByteArray())
        mockMvc.get("/admin/applications/$number") { header("X-Admin-Key", KEY) }.andExpect {
            status { isOk() }
            jsonPath("$.events[-1].kind") { value(ReviewEventKind.MESSAGE_FAILED.name) }
            jsonPath("$.events[-1].actor") { value("system") }
            jsonPath("$.events[-1].detail") { value(containsString("131047")) }
            jsonPath("$.events[-1].detail") { value(containsString("send any message")) }
        }
        // A delivered status adds nothing.
        processor.process(statusPayload("wamid.invite-2", "delivered").toByteArray())
        mockMvc.get("/admin/applications/$number") { header("X-Admin-Key", KEY) }.andExpect {
            jsonPath("$.events[?(@.kind == '${ReviewEventKind.MESSAGE_FAILED.name}')]", hasSize<Any>(1))
        }
        // Clean up so the queue is as other tests expect.
        mockMvc.post("/admin/applications/$number/decision") {
            header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON
            content = """{"decision":"REJECTED","reviewer":"Officer Demo"}"""
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `reviewer statistics show the queue, the decision throughput and who did what`() {
        val waitingNumber = onboard("6287000000011")
        val decidedNumber = onboard("6287000000012")
        mockMvc.post("/admin/applications/$decidedNumber/note") {
            header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON
            content = """{"note":"checked the address","reviewer":"Officer Stats"}"""
        }.andExpect { status { isOk() } }
        mockMvc.post("/admin/applications/$decidedNumber/decision") {
            header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON
            content = """{"decision":"REJECTED","note":"income too low","reviewer":"Officer Stats"}"""
        }.andExpect { status { isOk() } }
        assertEquals(ApplicationStatus.READY_FOR_REVIEW, applications.findByApplicationNumber(waitingNumber)!!.status)

        mockMvc.get("/admin/review/stats") { header("X-Admin-Key", KEY) }.andExpect {
            status { isOk() }
            jsonPath("$.workload.readyForReview") { value(greaterThanOrEqualTo(1)) }
            jsonPath("$.workload.waitingUnder24h") { value(greaterThanOrEqualTo(1)) }
            jsonPath("$.workload.queueByCountry[0].code") { value("ID") }
            jsonPath("$.workload.queueByCountry[0].waiting") { value(greaterThanOrEqualTo(1)) }
            jsonPath("$.workload.queueByGrade[0].grade") { value("A") }
            jsonPath("$.throughput.decidedToday") { value(greaterThanOrEqualTo(1)) }
            jsonPath("$.throughput.decided7d") { value(greaterThanOrEqualTo(1)) }
            jsonPath("$.throughput.rejected30d") { value(greaterThanOrEqualTo(1)) }
            jsonPath("$.daily", hasSize<Any>(14))
            jsonPath("$.daily[-1].rejected") { value(greaterThanOrEqualTo(1)) }
            jsonPath("$.reviewers[*].name") { value(hasItem("Officer Stats")) }
            jsonPath("$.reviewers[?(@.name == 'Officer Stats')].rejected") { value(hasItem(1)) }
            jsonPath("$.reviewers[?(@.name == 'Officer Stats')].notes") { value(hasItem(1)) }
            jsonPath("$.reviewers[*].name") { value(not(hasItem("system"))) }
            jsonPath("$.gradeOutcomes[0].grade") { value("A") }
            jsonPath("$.gradeOutcomes[0].rejected") { value(greaterThanOrEqualTo(1)) }
            jsonPath("$.agentAlignment.notFlaggedDecided") { value(greaterThanOrEqualTo(1)) }
        }
        mockMvc.get("/admin/review/stats").andExpect { status { isUnauthorized() } }

        // Leave the queue as we found it for the other tests.
        mockMvc.post("/admin/applications/$waitingNumber/decision") {
            header("X-Admin-Key", KEY); contentType = MediaType.APPLICATION_JSON
            content = """{"decision":"APPROVED","reviewer":"Officer Stats"}"""
        }.andExpect { status { isOk() } }
        mockMvc.get("/admin/review/stats") { header("X-Admin-Key", KEY) }.andExpect {
            status { isOk() }
            jsonPath("$.reviewers[?(@.name == 'Officer Stats')].approved") { value(hasItem(1)) }
            jsonPath("$.reviewers[?(@.name == 'Officer Stats')].decisions7d") { value(hasItem(2)) }
        }
    }
}
