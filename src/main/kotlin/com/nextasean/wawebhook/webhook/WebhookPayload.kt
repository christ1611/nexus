package com.nextasean.wawebhook.webhook

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

// Minimal model of the WhatsApp Cloud API webhook payload. Unknown fields are ignored
// (Spring Boot's ObjectMapper disables FAIL_ON_UNKNOWN_PROPERTIES).

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class WebhookEvent(
    @JsonProperty("object") val objectType: String? = null,
    val entry: List<Entry> = emptyList(),
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Entry(
    val id: String? = null,
    val changes: List<Change> = emptyList(),
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Change(
    val field: String? = null,
    val value: ChangeValue = ChangeValue(),
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ChangeValue(
    val messagingProduct: String? = null,
    val metadata: Metadata? = null,
    val contacts: List<Contact> = emptyList(),
    val messages: List<Message> = emptyList(),
    val statuses: List<Status> = emptyList(),
    val errors: List<WaError> = emptyList(),
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Metadata(
    val displayPhoneNumber: String? = null,
    val phoneNumberId: String? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Contact(
    val waId: String? = null,
    val profile: Profile? = null,
)

data class Profile(val name: String? = null)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Message(
    val id: String? = null,
    val from: String? = null,
    val timestamp: String? = null,
    val type: String = "unknown",
    val text: Text? = null,
    val image: Media? = null,
    val video: Media? = null,
    val audio: Media? = null,
    val document: Media? = null,
    val sticker: Media? = null,
    val reaction: Reaction? = null,
    val location: Location? = null,
    val interactive: Interactive? = null,
    val button: Button? = null,
    val errors: List<WaError> = emptyList(),
) {
    /** The media attachment for whichever media type this message is, if any. */
    val media: Media?
        get() = when (type) {
            "image" -> image
            "video" -> video
            "audio" -> audio
            "document" -> document
            "sticker" -> sticker
            else -> null
        }
}

data class Text(val body: String? = null)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Media(
    val id: String? = null,
    val mimeType: String? = null,
    val sha256: String? = null,
    val caption: String? = null,
    val filename: String? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Reaction(
    val messageId: String? = null,
    val emoji: String? = null,
)

data class Location(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val name: String? = null,
    val address: String? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Interactive(
    val type: String? = null,
    val buttonReply: Reply? = null,
    val listReply: Reply? = null,
)

data class Reply(
    val id: String? = null,
    val title: String? = null,
)

data class Button(
    val payload: String? = null,
    val text: String? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class Status(
    val id: String? = null,
    val status: String? = null,
    val timestamp: String? = null,
    val recipientId: String? = null,
    val errors: List<WaError> = emptyList(),
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class WaError(
    val code: Int? = null,
    val title: String? = null,
    val message: String? = null,
    val errorData: ErrorData? = null,
) {
    fun describe(): String =
        buildString {
            append("code=").append(code)
            title?.let { append(" title=\"").append(it).append('"') }
            errorData?.details?.let { append(" details=\"").append(it).append('"') }
        }
}

data class ErrorData(val details: String? = null)
