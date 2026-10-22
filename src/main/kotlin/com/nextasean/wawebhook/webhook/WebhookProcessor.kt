package com.nextasean.wawebhook.webhook

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.nextasean.wawebhook.conversation.IncomingMessageHandler
import com.nextasean.wawebhook.dealer.normalizePhone
import com.nextasean.wawebhook.whatsapp.BusinessPhoneHolder
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

@Service
class WebhookProcessor(
    private val objectMapper: ObjectMapper,
    private val store: ProcessedMessageStore,
    private val handler: IncomingMessageHandler,
    private val businessPhone: BusinessPhoneHolder,
    private val deliveryFailures: DeliveryFailureListener,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Entry point used by the controller: runs on the application task executor after the 200 is sent. */
    @Async
    fun processAsync(rawBody: ByteArray) {
        try {
            process(rawBody)
        } catch (e: Exception) {
            log.error("Unhandled error while processing webhook payload", e)
        }
    }

    fun process(rawBody: ByteArray) {
        val event = try {
            objectMapper.readValue<WebhookEvent>(rawBody)
        } catch (e: Exception) {
            log.warn("Could not parse webhook payload ({} bytes): {}", rawBody.size, e.message)
            return
        }
        if (event.objectType != "whatsapp_business_account") {
            log.info("Ignoring webhook for object={}", event.objectType)
            return
        }
        for (entry in event.entry) {
            for (change in entry.changes) {
                if (change.field != "messages") {
                    log.info("Ignoring change | field={} | wabaId={}", change.field, entry.id)
                    continue
                }
                val value = change.value
                businessPhone.remember(value.metadata?.displayPhoneNumber)
                value.errors.forEach { log.warn("Webhook error | {}", it.describe()) }
                value.messages.forEach { handleMessage(it, value) }
                value.statuses.forEach { logStatus(it) }
            }
        }
    }

    private fun handleMessage(message: Message, value: ChangeValue) {
        val id = message.id
        val from = message.from
        if (id == null || from == null) {
            log.warn("Skipping message without id/from | type={}", message.type)
            return
        }
        if (!store.markIfNew(id)) {
            log.info("Duplicate message ignored | id={} | from={}", id, from)
            return
        }
        val name = value.contacts.firstOrNull { it.waId == from }?.profile?.name
        logIncoming(message, from, name ?: "unknown", id)
        try {
            handler.handle(from, name, message)
        } catch (e: Exception) {
            log.error("Failed to handle message | id={} | from={} | type={}", id, from, message.type, e)
        }
    }

    private fun logIncoming(message: Message, from: String, name: String, id: String) {
        when (message.type) {
            "text" -> log.info(
                "Incoming message | from={} ({}) | type=text | id={} | text=\"{}\"",
                from, name, id, message.text?.body.orEmpty(),
            )

            "image", "video", "audio", "document", "sticker" -> {
                val media = message.media
                log.info(
                    "Incoming message | from={} ({}) | type={} | id={} | mediaId={} | mimeType={} | filename={} | caption={}",
                    from, name, message.type, id, media?.id, media?.mimeType, media?.filename, media?.caption,
                )
            }

            "reaction" -> log.info(
                "Incoming message | from={} ({}) | type=reaction | id={} | emoji={} | reactedTo={}",
                from, name, id, message.reaction?.emoji, message.reaction?.messageId,
            )

            "location" -> log.info(
                "Incoming message | from={} ({}) | type=location | id={} | lat={} | lng={} | name={}",
                from, name, id, message.location?.latitude, message.location?.longitude, message.location?.name,
            )

            "interactive" -> {
                val reply = message.interactive?.buttonReply ?: message.interactive?.listReply
                log.info(
                    "Incoming message | from={} ({}) | type=interactive/{} | id={} | replyId={} | title=\"{}\"",
                    from, name, message.interactive?.type, id, reply?.id, reply?.title,
                )
            }

            "button" -> log.info(
                "Incoming message | from={} ({}) | type=button | id={} | payload={} | text=\"{}\"",
                from, name, id, message.button?.payload, message.button?.text,
            )

            else -> log.info(
                "Incoming message | from={} ({}) | type={} | id={} | errors={}",
                from, name, message.type, id, message.errors.joinToString { it.describe() },
            )
        }
    }

    private fun logStatus(status: Status) {
        if (status.status == "failed" || status.errors.isNotEmpty()) {
            log.warn(
                "Status update | messageId={} | status={} | recipient={} | timestamp={} | errors=[{}]",
                status.id, status.status, status.recipientId, status.timestamp,
                status.errors.joinToString("; ") { it.describe() },
            )
            recordDeliveryFailure(status)
        } else {
            log.info(
                "Status update | messageId={} | status={} | recipient={} | timestamp={}",
                status.id, status.status, status.recipientId, status.timestamp,
            )
        }
    }

    /**
     * The Graph API accepts a message and reports delivery failures later through this webhook (e.g. 131047: the customer
     * has not written to us in the last 24 hours). Hand it to the listener so it lands on the customer's timeline.
     */
    private fun recordDeliveryFailure(status: Status) {
        if (status.status != "failed") return
        val phone = status.recipientId?.let(::normalizePhone)?.takeIf { it.isNotBlank() } ?: return
        val reason = status.errors.joinToString("; ") { e -> listOfNotNull(e.code?.let { "code $it" }, e.title, e.errorData?.details).distinct().joinToString(": ") }
            .ifBlank { "no error details" }
        try {
            deliveryFailures.onFailed(phone, reason, status.errors.mapNotNull { it.code })
        } catch (e: Exception) {
            log.error("Delivery failure listener failed for recipient ***{}", phone.takeLast(4), e)
        }
    }
}

/** Told about outbound messages WhatsApp could not deliver (status "failed" in the webhook). */
fun interface DeliveryFailureListener {
    fun onFailed(recipientPhone: String, reason: String, errorCodes: List<Int>)
}
