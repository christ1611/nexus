package com.nextasean.wawebhook.webhook

import com.nextasean.wawebhook.documents.DownloadedMedia
import com.nextasean.wawebhook.documents.MediaDownloader
import com.nextasean.wawebhook.whatsapp.OutboundMessage
import com.nextasean.wawebhook.whatsapp.WhatsAppSender
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.HexFormat
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class RecordingSender : WhatsAppSender {
    val sent = CopyOnWriteArrayList<Pair<String, OutboundMessage>>()
    override fun send(to: String, message: OutboundMessage): Boolean {
        sent += to to message
        return true
    }

    fun last(): OutboundMessage = sent.last().second
    fun clear() = sent.clear()
}

class FakeMediaDownloader : MediaDownloader {
    val downloaded = CopyOnWriteArrayList<String>()
    var failNext = false
    override fun download(mediaId: String): DownloadedMedia {
        if (failNext) { failNext = false; error("simulated download failure") }
        downloaded += mediaId
        return DownloadedMedia("fake-image-bytes-$mediaId".toByteArray(), "image/jpeg", null)
    }
}

/** Replaces the Graph API client and media download everywhere a Spring context is started in tests. */
@TestConfiguration
class FakeIntegrationsConfig {
    @Bean @Primary fun recordingSender(): RecordingSender = RecordingSender()
    @Bean @Primary fun fakeMediaDownloader(): FakeMediaDownloader = FakeMediaDownloader()
}

/** Human-readable text of any outbound message, for assertions. */
val OutboundMessage.bodyText: String
    get() = when (this) {
        is OutboundMessage.Text -> body
        is OutboundMessage.Buttons -> body
        is OutboundMessage.ListMenu -> body
    }

val OutboundMessage.replyIds: List<String>
    get() = when (this) {
        is OutboundMessage.Text -> emptyList()
        is OutboundMessage.Buttons -> buttons.map { it.id }
        is OutboundMessage.ListMenu -> sections.flatMap { s -> s.rows.map { it.id } }
    }

fun sign(secret: String, body: ByteArray): String {
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body))
}

fun textMessagePayload(messageId: String, from: String, text: String): String = """
    {"object":"whatsapp_business_account","entry":[{"id":"WABA_ID","changes":[{"field":"messages","value":{
      "messaging_product":"whatsapp",
      "metadata":{"display_phone_number":"15550000000","phone_number_id":"PHONE_ID"},
      "contacts":[{"profile":{"name":"Test User"},"wa_id":"$from"}],
      "messages":[{"from":"$from","id":"$messageId","timestamp":"1700000000","type":"text","text":{"body":"$text"}}]
    }}]}]}
""".trimIndent()

fun imageMessagePayload(messageId: String, from: String, mediaId: String, caption: String = "hi"): String = """
    {"object":"whatsapp_business_account","entry":[{"id":"WABA_ID","changes":[{"field":"messages","value":{
      "messaging_product":"whatsapp",
      "metadata":{"display_phone_number":"15550000000","phone_number_id":"PHONE_ID"},
      "contacts":[{"profile":{"name":"Test User"},"wa_id":"$from"}],
      "messages":[{"from":"$from","id":"$messageId","timestamp":"1700000000","type":"image",
        "image":{"mime_type":"image/jpeg","sha256":"abc","id":"$mediaId","caption":"$caption"}}]
    }}]}]}
""".trimIndent()

fun statusPayload(messageId: String, status: String, withError: Boolean = false): String = """
    {"object":"whatsapp_business_account","entry":[{"id":"WABA_ID","changes":[{"field":"messages","value":{
      "messaging_product":"whatsapp",
      "metadata":{"display_phone_number":"15550000000","phone_number_id":"PHONE_ID"},
      "statuses":[{"id":"$messageId","status":"$status","timestamp":"1700000000","recipient_id":"6281200000000"
        ${if (withError) ""","errors":[{"code":131047,"title":"Re-engagement message","message":"Re-engagement message","error_data":{"details":"Message failed to send because more than 24 hours have passed"}}]""" else ""}
      }]
    }}]}]}
""".trimIndent()

fun interactivePayload(messageId: String, from: String, kind: String, replyId: String, title: String): String = """
    {"object":"whatsapp_business_account","entry":[{"id":"WABA_ID","changes":[{"field":"messages","value":{
      "messaging_product":"whatsapp",
      "metadata":{"display_phone_number":"15550000000","phone_number_id":"PHONE_ID"},
      "contacts":[{"profile":{"name":"Test User"},"wa_id":"$from"}],
      "messages":[{"from":"$from","id":"$messageId","timestamp":"1700000000","type":"interactive",
        "interactive":{"type":"$kind","$kind":{"id":"$replyId","title":"$title"}}}]
    }}]}]}
""".trimIndent()
