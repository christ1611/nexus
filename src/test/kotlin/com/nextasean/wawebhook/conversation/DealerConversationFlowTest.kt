package com.nextasean.wawebhook.conversation

import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.webhook.FakeIntegrationsConfig
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
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val DEALER = "6281111111111"
private const val STRANGER = "6289999999999"

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(FakeIntegrationsConfig::class)
@TestPropertySource(properties = ["spring.datasource.url=jdbc:h2:mem:dealertest;DB_CLOSE_DELAY=-1"])
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DealerConversationFlowTest @Autowired constructor(
    private val processor: WebhookProcessor,
    private val sender: RecordingSender,
    private val applications: ApplicationRepository,
    private val conversations: ConversationRepository,
    private val mockMvc: MockMvc,
) {


    /** JUnit creates a new instance per test, so IDs must be globally unique to get past de-duplication. */
    private fun nextId() = "wamid." + java.util.UUID.randomUUID()

    @BeforeEach
    fun reset() = sender.clear()

    private fun text(from: String, body: String) = processor.process(textMessagePayload(nextId(), from, body).toByteArray())
    private fun listReply(from: String, id: String) = processor.process(interactivePayload(nextId(), from, "list_reply", id, id).toByteArray())
    private fun buttonReply(from: String, id: String) = processor.process(interactivePayload(nextId(), from, "button_reply", id, id).toByteArray())

    @Test
    @Order(1)
    fun `unregistered numbers get a polite rejection and nothing else`() {
        text(STRANGER, "hello")
        val (to, message) = sender.sent.single()
        assertEquals(STRANGER, to)
        assertIs<OutboundMessage.Text>(message)
        assertTrue(message.body.contains("not registered"))
    }

    @Test
    @Order(2)
    fun `registered dealer gets the main menu as a list and the state is stored`() {
        text(DEALER, "hi")
        val menu = assertIs<OutboundMessage.ListMenu>(sender.last())
        assertTrue(menu.body.contains("Test Dealer One"))
        assertEquals(listOf(ReplyIds.MENU_NEW, ReplyIds.MENU_STATUS, ReplyIds.MENU_HELP), menu.replyIds)
        assertEquals(ConversationState.MAIN_MENU, conversations.findAll().single().state)
    }

    @Test
    @Order(3)
    fun `new application asks for confirmation then creates number and one-time link`() {
        listReply(DEALER, ReplyIds.MENU_NEW)
        val confirm = assertIs<OutboundMessage.Buttons>(sender.last())
        assertEquals(listOf(ReplyIds.NEW_CONFIRM, ReplyIds.NEW_CANCEL), confirm.replyIds)
        assertEquals(ConversationState.NEW_APPLICATION_CONFIRM, conversations.findAll().single().state)

        buttonReply(DEALER, ReplyIds.NEW_CONFIRM)
        val created = sender.sent.map { it.second }.filterIsInstance<OutboundMessage.Buttons>().last()
        val forward = assertIs<OutboundMessage.Text>(sender.last())
        assertTrue(forward.body.contains("https://wa.me/15550000000?text=START%20APP-"), forward.body)
        val number = Regex("APP-\\d{4}-\\d{6}").find(created.body)?.value
        assertNotNull(number)
        assertTrue(created.body.contains("https://example.test/apply/"))
        val app = applications.findByApplicationNumber(number)
        assertNotNull(app)
        assertEquals(ApplicationStatus.DRAFT, app.status)
        assertNotNull(app.linkTokenHash)
        assertEquals(ConversationState.MAIN_MENU, conversations.findAll().single().state)
    }

    @Test
    @Order(4)
    fun `one-time link opens once, then the form submission moves the application to SUBMITTED and notifies the dealer`() {
        buttonReply(DEALER, ReplyIds.MENU_STATUS)
        val list = assertIs<OutboundMessage.ListMenu>(sender.last())
        val statusId = list.replyIds.single()
        val number = statusId.removePrefix(ReplyIds.STATUS_PREFIX)

        buttonReply(DEALER, statusId)
        val detail = assertIs<OutboundMessage.Buttons>(sender.last())
        assertTrue(detail.body.contains("Draft"))
        buttonReply(DEALER, ReplyIds.link(number))
        val token = Regex("/apply/([A-Za-z0-9_-]+)").find(sender.last().bodyText)!!.groupValues[1]

        mockMvc.get("/apply/$token").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString(number)) }
        }
        mockMvc.get("/apply/$token").andExpect { status { isGone() } }

        sender.clear()
        mockMvc.post("/apply/$token").andExpect { status { isOk() } }
        assertEquals(ApplicationStatus.SUBMITTED, applications.findByApplicationNumber(number)!!.status)
        val notice = assertIs<OutboundMessage.Buttons>(sender.last())
        assertTrue(notice.body.contains("under review"))
        mockMvc.post("/apply/$token").andExpect { status { isNotFound() } }
    }

    @Test
    @Order(5)
    fun `admin resubmission request flags the application and sends the dealer a fresh link`() {
        val number = applications.findAll().single().applicationNumber
        mockMvc.post("/admin/applications/$number/request-resubmission") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"ID document is blurry"}"""
        }.andExpect { status { isUnauthorized() } }

        mockMvc.post("/admin/applications/$number/request-resubmission") {
            header("X-Admin-Key", "test-admin-key")
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"ID document is blurry"}"""
        }.andExpect { status { isOk() } }

        assertEquals(ApplicationStatus.RESUBMISSION_REQUESTED, applications.findByApplicationNumber(number)!!.status)
        val notice = assertIs<OutboundMessage.Buttons>(sender.last())
        assertTrue(notice.body.contains("ID document is blurry"))
        assertTrue(notice.body.contains("/apply/"))

        sender.clear()
        buttonReply(DEALER, ReplyIds.status(number))
        val detail = assertIs<OutboundMessage.Buttons>(sender.last())
        assertTrue(detail.body.contains("Resubmission requested"))
        assertEquals(listOf(ReplyIds.link(number), ReplyIds.MAIN_MENU), detail.replyIds)
    }

    @Test
    @Order(6)
    fun `admin decision notifies the dealer and locks the application`() {
        val number = applications.findAll().single().applicationNumber
        mockMvc.post("/admin/applications/$number/decision") {
            header("X-Admin-Key", "test-admin-key")
            contentType = MediaType.APPLICATION_JSON
            content = """{"decision":"APPROVED","note":"Welcome aboard"}"""
        }.andExpect { status { isOk() } }

        assertEquals(ApplicationStatus.APPROVED, applications.findByApplicationNumber(number)!!.status)
        val notice = assertIs<OutboundMessage.Buttons>(sender.last())
        assertTrue(notice.body.contains("APPROVED"))
        assertTrue(notice.body.contains("Welcome aboard"))

        mockMvc.post("/admin/applications/$number/decision") {
            header("X-Admin-Key", "test-admin-key")
            contentType = MediaType.APPLICATION_JSON
            content = """{"decision":"REJECTED"}"""
        }.andExpect { status { isConflict() } }
    }

    @Test
    @Order(7)
    fun `text shortcuts, cancel, help and images keep working`() {
        listReply(DEALER, ReplyIds.MENU_NEW)
        text(DEALER, "no")
        assertIs<OutboundMessage.ListMenu>(sender.last())
        assertTrue(sender.last().bodyText.contains("nothing was created"))

        listReply(DEALER, ReplyIds.MENU_HELP)
        assertTrue(sender.last().bodyText.contains("registered dealers"))

        processor.process(imageMessagePayload(nextId(), DEALER, "MEDIA42").toByteArray())
        assertEquals("Image received, ID: MEDIA42", sender.last().bodyText)

        text(DEALER, "what?")
        assertTrue(sender.last().bodyText.contains("did not understand"))

        listReply(DEALER, ReplyIds.MENU_STATUS)
        text(DEALER, "APP-0000-000000")
        assertTrue(sender.last().bodyText.contains("could not find"))
        assertEquals(ConversationState.STATUS_SELECT, conversations.findAll().single().state)
    }
}
