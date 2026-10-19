package com.nextasean.wawebhook.webhook

import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val APP_SECRET = "test-app-secret"
private const val VERIFY_TOKEN = "test-verify-token"

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(FakeIntegrationsConfig::class)
class WebhookControllerTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val sender: RecordingSender,
) {


    @BeforeEach
    fun reset() {
        sender.sent.clear()
    }

    @Test
    fun `GET returns the challenge as plain text for a valid subscribe request`() {
        mockMvc.get("/webhook") {
            param("hub.mode", "subscribe")
            param("hub.verify_token", VERIFY_TOKEN)
            param("hub.challenge", "1158201444")
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.TEXT_PLAIN) }
            content { string("1158201444") }
        }
    }

    @Test
    fun `GET returns 403 for a wrong verify token`() {
        mockMvc.get("/webhook") {
            param("hub.mode", "subscribe")
            param("hub.verify_token", "wrong")
            param("hub.challenge", "1158201444")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `GET returns 403 when mode is not subscribe or params are missing`() {
        mockMvc.get("/webhook") {
            param("hub.mode", "unsubscribe")
            param("hub.verify_token", VERIFY_TOKEN)
            param("hub.challenge", "1")
        }.andExpect { status { isForbidden() } }
        mockMvc.get("/webhook").andExpect { status { isForbidden() } }
    }

    @Test
    fun `POST with a valid signature returns 200 and echoes asynchronously`() {
        val body = textMessagePayload("wamid.ctrl.1", "6281234567890", "ping").toByteArray()
        mockMvc.post("/webhook") {
            contentType = MediaType.APPLICATION_JSON
            header("X-Hub-Signature-256", sign(APP_SECRET, body))
            content = body
        }.andExpect { status { isOk() } }

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertEquals(1, sender.sent.size)
            assertEquals("6281234567890", sender.sent.single().first)
            assertTrue(sender.last().bodyText.contains("not registered"))
        }
    }

    @Test
    fun `POST with an invalid or missing signature returns 401 and is not processed`() {
        val body = textMessagePayload("wamid.ctrl.2", "6281234567890", "ping").toByteArray()
        mockMvc.post("/webhook") {
            contentType = MediaType.APPLICATION_JSON
            header("X-Hub-Signature-256", sign("wrong-secret", body))
            content = body
        }.andExpect { status { isUnauthorized() } }
        mockMvc.post("/webhook") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isUnauthorized() } }

        Thread.sleep(300)
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `POST with a redelivered message id is only handled once`() {
        val body = textMessagePayload("wamid.ctrl.3", "6281234567890", "dup").toByteArray()
        repeat(2) {
            mockMvc.post("/webhook") {
                contentType = MediaType.APPLICATION_JSON
                header("X-Hub-Signature-256", sign(APP_SECRET, body))
                content = body
            }.andExpect { status { isOk() } }
        }

        await().atMost(Duration.ofSeconds(5)).untilAsserted { assertEquals(1, sender.sent.size) }
        Thread.sleep(300)
        assertEquals(1, sender.sent.size)
    }
}
