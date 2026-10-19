package com.nextasean.wawebhook.whatsapp

import com.nextasean.wawebhook.config.WhatsAppProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

/** Sends messages through `POST /{version}/{phoneNumberId}/messages` on the Graph API. */
@Service
class GraphWhatsAppClient(
    private val graphRestClient: RestClient,
    private val props: WhatsAppProperties,
) : WhatsAppSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(to: String, message: OutboundMessage): Boolean {
        val kind = message::class.simpleName
        return try {
            val response = graphRestClient.post()
                .uri("/{version}/{phoneNumberId}/messages", props.graphApiVersion, props.phoneNumberId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(toPayload(to, message))
                .retrieve()
                .body(SendMessageResponse::class.java)
            log.info("Outgoing message | to={} | kind={} | messageId={}", to, kind, response?.messages?.firstOrNull()?.id)
            true
        } catch (e: RestClientResponseException) {
            log.error("Graph API rejected message | to={} | kind={} | http={} | body={}", to, kind, e.statusCode.value(), e.responseBodyAsString)
            false
        } catch (e: RestClientException) {
            log.error("Graph API call failed | to={} | kind={} | reason={}", to, kind, e.message)
            false
        }
    }

    internal fun toPayload(to: String, message: OutboundMessage): Map<String, Any> {
        val base = mutableMapOf<String, Any>(
            "messaging_product" to "whatsapp",
            "recipient_type" to "individual",
            "to" to to,
        )
        when (message) {
            is OutboundMessage.Text -> {
                base["type"] = "text"
                base["text"] = mapOf("preview_url" to message.previewUrl, "body" to message.body.take(MAX_TEXT))
            }

            is OutboundMessage.Buttons -> {
                base["type"] = "interactive"
                base["interactive"] = buildMap {
                    put("type", "button")
                    message.header?.let { put("header", mapOf("type" to "text", "text" to it.take(MAX_HEADER))) }
                    put("body", mapOf("text" to message.body.take(MAX_BODY)))
                    message.footer?.let { put("footer", mapOf("text" to it.take(MAX_FOOTER))) }
                    put(
                        "action",
                        mapOf(
                            "buttons" to message.buttons.map {
                                mapOf("type" to "reply", "reply" to mapOf("id" to it.id.take(MAX_ID), "title" to it.title.take(MAX_BUTTON_TITLE)))
                            },
                        ),
                    )
                }
            }

            is OutboundMessage.ListMenu -> {
                base["type"] = "interactive"
                base["interactive"] = buildMap {
                    put("type", "list")
                    message.header?.let { put("header", mapOf("type" to "text", "text" to it.take(MAX_HEADER))) }
                    put("body", mapOf("text" to message.body.take(MAX_BODY)))
                    message.footer?.let { put("footer", mapOf("text" to it.take(MAX_FOOTER))) }
                    put(
                        "action",
                        mapOf(
                            "button" to message.buttonText.take(MAX_BUTTON_TITLE),
                            "sections" to message.sections.map { section ->
                                mapOf(
                                    "title" to section.title.take(MAX_ROW_TITLE),
                                    "rows" to section.rows.map { row ->
                                        buildMap {
                                            put("id", row.id.take(MAX_ID))
                                            put("title", row.title.take(MAX_ROW_TITLE))
                                            row.description?.let { put("description", it.take(MAX_ROW_DESCRIPTION)) }
                                        }
                                    },
                                )
                            },
                        ),
                    )
                }
            }
        }
        return base
    }

    data class SendMessageResponse(val messages: List<SentMessage> = emptyList())

    data class SentMessage(val id: String? = null)

    private companion object {
        const val MAX_TEXT = 4096
        const val MAX_BODY = 1024
        const val MAX_HEADER = 60
        const val MAX_FOOTER = 60
        const val MAX_BUTTON_TITLE = 20
        const val MAX_ROW_TITLE = 24
        const val MAX_ROW_DESCRIPTION = 72
        const val MAX_ID = 200
    }
}
