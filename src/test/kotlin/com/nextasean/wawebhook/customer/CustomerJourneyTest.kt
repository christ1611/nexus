package com.nextasean.wawebhook.customer

import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.conversation.ReplyIds
import com.nextasean.wawebhook.documents.DocumentRepository
import com.nextasean.wawebhook.documents.DocumentType
import com.nextasean.wawebhook.verification.ProviderOutcome
import com.nextasean.wawebhook.webhook.FakeIntegrationsConfig
import com.nextasean.wawebhook.webhook.FakeMediaDownloader
import com.nextasean.wawebhook.webhook.RecordingSender
import com.nextasean.wawebhook.webhook.WebhookProcessor
import com.nextasean.wawebhook.webhook.bodyText
import com.nextasean.wawebhook.webhook.imageMessagePayload
import com.nextasean.wawebhook.webhook.interactivePayload
import com.nextasean.wawebhook.webhook.replyIds
import com.nextasean.wawebhook.webhook.textMessagePayload
import com.nextasean.wawebhook.whatsapp.OutboundMessage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DEALER = "6282222222222"
private const val CUSTOMER = "6283333333333"
private const val OTHER = "6284444444444"

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(FakeIntegrationsConfig::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CustomerJourneyTest @Autowired constructor(
    private val processor: WebhookProcessor,
    private val sender: RecordingSender,
    private val media: FakeMediaDownloader,
    private val applications: ApplicationRepository,
    private val sessions: CustomerSessionRepository,
    private val documents: DocumentRepository,
) {
    @BeforeEach
    fun reset() = sender.clear()

    private fun id() = "wamid." + UUID.randomUUID()
    private fun text(from: String, body: String) = processor.process(textMessagePayload(id(), from, body).toByteArray())
    private fun button(from: String, replyId: String) = processor.process(interactivePayload(id(), from, "button_reply", replyId, replyId).toByteArray())
    private fun image(from: String, mediaId: String, caption: String = "") = processor.process(imageMessagePayload(id(), from, mediaId, caption).toByteArray())
    private fun sentTo(phone: String) = sender.sent.filter { it.first == phone }.map { it.second }
    private fun number() = applications.findAll().single { it.dealerId == dealerTwoId() }.applicationNumber
    private fun dealerTwoId() = applications.findAll().map { it.dealerId }.max()

    companion object {
        lateinit var appNumber: String
    }

    @Test
    @Order(1)
    fun `dealer creates an application and receives a wa-me START link for the customer`() {
        text(DEALER, "menu")
        button(DEALER, ReplyIds.MENU_NEW)
        button(DEALER, ReplyIds.NEW_CONFIRM)
        val forward = assertIs<OutboundMessage.Text>(sender.last())
        val match = Regex("https://wa\\.me/15550000000\\?text=START%20(APP-\\d{4}-\\d{6})").find(forward.body)
        assertNotNull(match, forward.body)
        appNumber = match.groupValues[1]
    }

    @Test
    @Order(2)
    fun `START from a stranger links the phone and asks for consent, unknown numbers are explained`() {
        text(OTHER, "START APP-1999-000001")
        assertTrue(sender.last().bodyText.contains("could not find"))

        text(CUSTOMER, "start $appNumber")
        val consent = assertIs<OutboundMessage.Buttons>(sender.last())
        assertEquals(listOf(CustomerReplyIds.CONSENT_AGREE, CustomerReplyIds.CONSENT_DECLINE), consent.replyIds)
        assertEquals(CUSTOMER, applications.findByApplicationNumber(appNumber)!!.customerPhone)
        assertEquals(CustomerSessionState.AWAITING_CONSENT, sessions.findActiveByPhone(CUSTOMER)!!.state)

        text(OTHER, "START $appNumber")
        assertTrue(sender.last().bodyText.contains("already linked"))
    }

    @Test
    @Order(3)
    fun `consent is stored with a timestamp and the first document is requested`() {
        text(CUSTOMER, "what is this?")
        assertIs<OutboundMessage.Buttons>(sender.last())

        button(CUSTOMER, CustomerReplyIds.CONSENT_AGREE)
        assertEquals("Thank you.\n\nStep 1 of 2: Please send a photo of your KTP.", sender.last().bodyText)
        val session = sessions.findActiveByPhone(CUSTOMER)!!
        assertEquals(CustomerSessionState.AWAITING_KTP, session.state)
        assertNotNull(session.consentAt)
    }

    @Test
    @Order(4)
    fun `text while an image is expected repeats the request, blurry photo fails and is asked again`() {
        text(CUSTOMER, "here you go")
        assertEquals("Step 1 of 2: Please send a photo of your KTP.", sender.last().bodyText)

        image(CUSTOMER, "MEDIA_BLUR", caption = "a bit blur")
        val replies = sentTo(CUSTOMER).map { it.bodyText }
        assertTrue(replies.any { it == "Received: KTP ✅ (1 of 2)" }, replies.toString())
        assertTrue(replies.last().contains("Photo is blurry") && replies.last().contains("Step 1 of 2"), replies.last())
        assertEquals(CustomerSessionState.AWAITING_KTP, sessions.findActiveByPhone(CUSTOMER)!!.state)

        val stored = documents.findAll().single()
        assertEquals(false, stored.checkPassed)
        assertTrue(Files.exists(Path.of(stored.filePath)), stored.filePath)
        assertTrue(stored.filePath.contains(appNumber) && stored.filePath.contains("KTP-"))
        assertEquals(listOf("MEDIA_BLUR"), media.downloaded)
    }

    @Test
    @Order(5)
    fun `good KTP passes, fields are extracted and the selfie is requested`() {
        image(CUSTOMER, "MEDIA_KTP", caption = "nik7")
        val replies = sentTo(CUSTOMER).map { it.bodyText }
        assertEquals("Received: KTP ✅ (1 of 2)", replies.first())
        assertEquals("Step 2 of 2: Please send a selfie of yourself holding your KTP next to your face.", replies.last())
        assertEquals(CustomerSessionState.AWAITING_SELFIE, sessions.findActiveByPhone(CUSTOMER)!!.state)

        val app = applications.findByApplicationNumber(appNumber)!!
        assertEquals("Budi Santoso", app.extractedName)
        assertEquals("3171051705900007", app.extractedNik)
    }

    @Test
    @Order(6)
    fun `selfie completes the journey, verification results are saved and both sides are notified`() {
        image(CUSTOMER, "MEDIA_SELFIE")
        val customerReplies = sentTo(CUSTOMER).map { it.bodyText }
        assertEquals("Received: Selfie with KTP ✅ (2 of 2)", customerReplies[0])
        assertEquals("Checking your data...", customerReplies[1])
        assertTrue(customerReplies[2].contains("Your documents are submitted"), customerReplies[2])

        val app = applications.findByApplicationNumber(appNumber)!!
        assertEquals(ApplicationStatus.READY_FOR_REVIEW, app.status)
        assertEquals(ProviderOutcome.MISMATCH, app.idVerificationStatus)
        assertEquals(ProviderOutcome.MISMATCH, app.creditCheckStatus)
        assertNull(app.creditScore)
        assertNotNull(app.verifiedAt)
        assertNull(app.linkTokenHash)

        val dealerNotice = assertIs<OutboundMessage.Buttons>(sentTo(DEALER).single())
        assertTrue(dealerNotice.body.contains(appNumber) && dealerNotice.body.contains("Data mismatch"), dealerNotice.body)
        assertEquals(CustomerSessionState.COMPLETED, sessions.findByPhoneOrderByCreatedAtDesc(CUSTOMER).first().state)
        assertNull(sessions.findActiveByPhone(CUSTOMER))
        assertEquals(2, documents.findAll().count { it.checkPassed == true })
        assertEquals(listOf(DocumentType.KTP, DocumentType.KTP, DocumentType.SELFIE_KTP), documents.findAll().sortedBy { it.id }.map { it.type })
    }

    @Test
    @Order(7)
    fun `after submission START is refused, and decline and cancel paths work on a fresh application`() {
        text(CUSTOMER, "START $appNumber")
        assertTrue(sender.last().bodyText.contains("not accepting documents"))

        // Dealer creates a second application; a customer declines consent, then another cancels mid-way.
        button(DEALER, ReplyIds.MENU_NEW)
        button(DEALER, ReplyIds.NEW_CONFIRM)
        val second = Regex("START%20(APP-\\d{4}-\\d{6})").find(sender.last().bodyText)!!.groupValues[1]

        text(OTHER, "START $second")
        button(OTHER, CustomerReplyIds.CONSENT_DECLINE)
        assertTrue(sender.last().bodyText.contains("will not proceed"))
        val declined = sessions.findByPhoneOrderByCreatedAtDesc(OTHER).first()
        assertEquals(CustomerSessionState.DECLINED, declined.state)
        assertNotNull(declined.declinedAt)

        text(OTHER, "START $second")
        text(OTHER, "agree")
        assertTrue(sender.last().bodyText.contains("Step 1 of 2"))
        text(OTHER, "cancel")
        assertTrue(sender.last().bodyText.contains("cancelled"))
        assertNull(sessions.findActiveByPhone(OTHER))

        // A dealer phone can act as a customer too; CANCEL returns it to the dealer menu.
        text(DEALER, "START $second")
        assertTrue(sender.last().bodyText.contains("already linked"))
    }
}
