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
import com.nextasean.wawebhook.trace.AgentTraceService
import com.nextasean.wawebhook.trace.TraceKind
import com.nextasean.wawebhook.trace.TraceStatus
import com.nextasean.wawebhook.webhook.FakeIntegrationsConfig
import com.nextasean.wawebhook.webhook.RecordingSender
import com.nextasean.wawebhook.webhook.WebhookProcessor
import com.nextasean.wawebhook.webhook.bodyText
import com.nextasean.wawebhook.webhook.imageMessagePayload
import com.nextasean.wawebhook.webhook.interactivePayload
import com.nextasean.wawebhook.webhook.textMessagePayload
import com.nextasean.wawebhook.whatsapp.OutboundMessage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stand-in for the Claude agent: replays scripted decisions and records what it was shown. */
class ScriptedReviewAgent : ReviewAgent {
    val contexts = CopyOnWriteArrayList<ReviewContext>()
    val script = ArrayDeque<ReviewDecision>()
    override fun decide(context: ReviewContext): ReviewDecision {
        contexts += context
        return script.removeFirst()
    }
}

@TestConfiguration
class ScriptedReviewAgentConfig {
    @Bean fun scriptedReviewAgent() = ScriptedReviewAgent()
}

private const val DEALER = "6281111111111"
private const val CUSTOMER = "6285555555555"

/** The agent step wired into the customer flow (document check itself stays the stub here). */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeIntegrationsConfig::class, ScriptedReviewAgentConfig::class)
class ReviewAgentFlowTest @Autowired constructor(
    private val processor: WebhookProcessor,
    private val sender: RecordingSender,
    private val agent: ScriptedReviewAgent,
    private val applications: ApplicationRepository,
    private val sessions: CustomerSessionRepository,
    private val documents: DocumentRepository,
    private val trace: AgentTraceService,
) {
    private fun id() = "wamid." + UUID.randomUUID()
    private fun text(from: String, body: String) = processor.process(textMessagePayload(id(), from, body).toByteArray())
    private fun button(from: String, replyId: String) = processor.process(interactivePayload(id(), from, "button_reply", replyId, replyId).toByteArray())
    private fun image(from: String, mediaId: String, caption: String = "") = processor.process(imageMessagePayload(id(), from, mediaId, caption).toByteArray())
    private fun sentTo(phone: String) = sender.sent.filter { it.first == phone }.map { it.second }

    @Test
    fun `agent sends the customer back for the KTP only, then writes the summary and verification runs`() {
        text(DEALER, "menu")
        button(DEALER, ReplyIds.MENU_NEW)
        button(DEALER, ReplyIds.NEW_CONFIRM)
        val number = Regex("START%20(APP-\\d{4}-\\d{6})").find(sender.last().bodyText)!!.groupValues[1]
        text(CUSTOMER, "START $number")
        button(CUSTOMER, CustomerReplyIds.CONSENT_AGREE)
        image(CUSTOMER, "KTP_1")
        assertEquals(CustomerSessionState.AWAITING_SELFIE, sessions.findActiveByPhone(CUSTOMER)!!.state)

        // Round 1: the agent wants a better KTP photo.
        agent.script += ReviewDecision.RequestDocuments(listOf(DocumentRequest(DocumentType.KTP, "the NIK line has glare on the right")), note = "Tip: switch off the flash.")
        sender.clear()
        image(CUSTOMER, "SELFIE_1")

        val ctx1 = agent.contexts.single()
        assertEquals(0, ctx1.round)
        assertTrue(ctx1.documents.all { it.received }, "both documents were shown to the agent")
        val replies = sentTo(CUSTOMER).map { it.bodyText }
        assertEquals("Checking your data...", replies[1])
        assertEquals(
            "Thank you. Before we can continue, we need the following again:\n" +
                "• KTP: the NIK line has glare on the right.\n\nTip: switch off the flash.\n\nStep 1 of 2: Please send a photo of your KTP.",
            replies[2],
        )
        assertEquals(CustomerSessionState.AWAITING_KTP, sessions.findActiveByPhone(CUSTOMER)!!.state)
        val app1 = applications.findByApplicationNumber(number)!!
        assertEquals("REQUEST_DOCUMENTS", app1.reviewDecision)
        assertEquals(1, app1.reviewRounds)
        assertNull(app1.extractedNik, "fields from the superseded KTP are cleared")
        assertEquals(ApplicationStatus.DRAFT, app1.status)
        val byMedia = documents.findByApplicationIdOrderByCreatedAtAsc(app1.id!!).associateBy { it.mediaId }
        assertTrue(byMedia.getValue("KTP_1").superseded)
        assertTrue(!byMedia.getValue("SELFIE_1").superseded)
        assertTrue(sentTo(DEALER).isEmpty(), "dealer is not bothered while documents are being re-collected")

        // Round 2: new KTP arrives; the selfie is still valid so the agent runs again immediately.
        agent.script += ReviewDecision.SummaryWritten("APPLICATION $number\nAll documents legible. NIK validation passed.", officerReviewRequired = false, summaryKo = "신청 $number\n모든 서류 판독 가능. NIK 검증 통과.")
        sender.clear()
        image(CUSTOMER, "KTP_2", caption = "nik3")

        assertEquals(2, agent.contexts.size)
        val ctx2 = agent.contexts[1]
        assertEquals(1, ctx2.round)
        assertEquals("KTP_2", ctx2.documents.first { it.type == DocumentType.KTP }.record!!.mediaId)
        assertEquals("SELFIE_1", ctx2.documents.first { it.type == DocumentType.SELFIE_KTP }.record!!.mediaId)

        val customerReplies = sentTo(CUSTOMER).map { it.bodyText }
        assertEquals("Received: KTP ✅ (1 of 2)", customerReplies[0])
        assertEquals("Checking your data...", customerReplies[1], "selfie was not requested again")
        assertTrue(customerReplies[2].contains("Your documents are submitted"), customerReplies[2])

        val app2 = applications.findByApplicationNumber(number)!!
        assertEquals(ApplicationStatus.READY_FOR_REVIEW, app2.status)
        assertEquals("SUMMARY_WRITTEN", app2.reviewDecision)
        assertEquals(false, app2.officerReviewRequired)
        assertTrue(app2.reviewSummary!!.startsWith("APPLICATION $number"))
        assertTrue(app2.reviewSummaryKo!!.startsWith("신청 $number"), "the Korean summary is stored for the Korean back office")
        assertTrue(app2.extractedNik!!.startsWith("317105170590") && app2.extractedNik!!.endsWith("3"), "sample NIK with per-application serial: " + app2.extractedNik)
        assertNotNull(app2.idVerificationStatus, "providers ran because the NIK was usable")
        assertNull(sessions.findActiveByPhone(CUSTOMER))
        val dealerNotice = assertIs<OutboundMessage.Buttons>(sentTo(DEALER).single())
        assertTrue(dealerNotice.body.contains(number) && dealerNotice.body.contains("ID verification"), dealerNotice.body)

        // The agent timeline tells the whole story in order: receive -> check -> decision -> customer messaged -> re-receive -> ... -> ready.
        val steps = trace.steps(app2.id)
        assertEquals(
            listOf(
                TraceKind.DOCUMENT_RECEIVED, TraceKind.QUALITY_CHECK, TraceKind.FIELD_EXTRACTION,          // KTP_1
                TraceKind.DOCUMENT_RECEIVED, TraceKind.QUALITY_CHECK,                                     // SELFIE_1
                TraceKind.AGENT_DECISION, TraceKind.CUSTOMER_MESSAGED,                                    // round 1: request KTP
                TraceKind.DOCUMENT_RECEIVED, TraceKind.QUALITY_CHECK, TraceKind.FIELD_EXTRACTION,          // KTP_2
                TraceKind.AGENT_DECISION, TraceKind.ID_VERIFICATION, TraceKind.CREDIT_CHECK, TraceKind.RISK_ASSESSMENT, TraceKind.SUBMITTED,
            ),
            steps.map { it.kind },
        )
        val request = steps.first { it.kind == TraceKind.AGENT_DECISION }
        assertEquals(TraceStatus.DECISION, request.status)
        assertTrue(request.title.contains("request KTP again (round 1 of"), request.title)
        assertTrue(request.detail!!.contains("the NIK line has glare on the right"), request.detail)
        assertEquals(0, request.round)
        val summary = steps.last { it.kind == TraceKind.AGENT_DECISION }
        assertEquals(TraceStatus.OK, summary.status)
        assertEquals(1, summary.round)
        assertTrue(steps.first { it.kind == TraceKind.ID_VERIFICATION }.actor.startsWith("provider:"))
        assertEquals("rules", steps.first { it.kind == TraceKind.RISK_ASSESSMENT }.actor)
    }
}
