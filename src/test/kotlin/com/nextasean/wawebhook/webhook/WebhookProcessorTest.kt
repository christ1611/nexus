package com.nextasean.wawebhook.webhook

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.nextasean.wawebhook.config.WhatsAppProperties
import com.nextasean.wawebhook.conversation.IncomingMessageHandler
import com.nextasean.wawebhook.whatsapp.BusinessPhoneHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebhookProcessorTest {

    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val handled = mutableListOf<Triple<String, String?, Message>>()
    private val businessPhone = BusinessPhoneHolder(WhatsAppProperties("1", "t", "s", "v"))
    private val processor = WebhookProcessor(mapper, ProcessedMessageStore(100), IncomingMessageHandler { from, name, m -> handled += Triple(from, name, m) }, businessPhone)

    @Test
    fun `routes text messages with sender and contact name`() {
        processor.process(textMessagePayload("wamid.1", "6281234567890", "hello").toByteArray())
        val (from, name, message) = handled.single()
        assertEquals("6281234567890", from)
        assertEquals("Test User", name)
        assertEquals("hello", message.text?.body)
    }

    @Test
    fun `learns the business number from webhook metadata`() {
        processor.process(textMessagePayload("wamid.meta", "6281234567890", "hello").toByteArray())
        assertEquals("https://wa.me/15550000000?text=START%20APP-2026-000001", businessPhone.startLink("APP-2026-000001"))
    }

    @Test
    fun `routes image messages with the media id`() {
        processor.process(imageMessagePayload("wamid.2", "6281234567890", "MEDIA123").toByteArray())
        assertEquals("MEDIA123", handled.single().third.image?.id)
    }

    @Test
    fun `ignores duplicate message ids`() {
        val payload = textMessagePayload("wamid.3", "6281234567890", "again").toByteArray()
        processor.process(payload)
        processor.process(payload)
        assertEquals(1, handled.size)
    }

    @Test
    fun `status updates are logged but not routed`() {
        processor.process(statusPayload("wamid.4", "delivered").toByteArray())
        processor.process(statusPayload("wamid.5", "failed", withError = true).toByteArray())
        assertTrue(handled.isEmpty())
    }

    @Test
    fun `tolerates garbage and unrelated objects`() {
        processor.process("not json".toByteArray())
        processor.process("""{"object":"page","entry":[]}""".toByteArray())
        assertTrue(handled.isEmpty())
    }

    @Test
    fun `a failing handler does not stop other messages`() {
        val failing = WebhookProcessor(mapper, ProcessedMessageStore(100), IncomingMessageHandler { _, _, m -> if (m.id == "wamid.x") error("boom") else handled += Triple("", null, m) }, businessPhone)
        failing.process(textMessagePayload("wamid.x", "6281234567890", "first").toByteArray())
        failing.process(textMessagePayload("wamid.y", "6281234567890", "second").toByteArray())
        assertEquals("wamid.y", handled.single().third.id)
    }

    @Test
    fun `parses interactive button and list replies`() {
        val button = mapper.readValue<WebhookEvent>(interactivePayload("wamid.7", "6281234567890", "button_reply", "BTN_YES", "Yes"))
            .entry.single().changes.single().value.messages.single()
        assertEquals("interactive", button.type)
        assertEquals("button_reply", button.interactive?.type)
        assertEquals("BTN_YES" to "Yes", button.interactive?.buttonReply?.let { it.id to it.title })
        assertNull(button.interactive?.listReply)

        val list = mapper.readValue<WebhookEvent>(interactivePayload("wamid.8", "6281234567890", "list_reply", "ROW_2", "Option 2"))
            .entry.single().changes.single().value.messages.single()
        assertEquals("list_reply", list.interactive?.type)
        assertEquals("ROW_2" to "Option 2", list.interactive?.listReply?.let { it.id to it.title })
    }
}
