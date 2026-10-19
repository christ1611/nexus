package com.nextasean.wawebhook.whatsapp

/** Messages this app can send. Length limits from the Cloud API are enforced when serialising. */
sealed interface OutboundMessage {

    data class Text(val body: String, val previewUrl: Boolean = false) : OutboundMessage

    /** Interactive message with up to three reply buttons. */
    data class Buttons(
        val body: String,
        val buttons: List<Button>,
        val header: String? = null,
        val footer: String? = null,
    ) : OutboundMessage {
        init {
            require(buttons.size in 1..3) { "WhatsApp allows 1 to 3 reply buttons, got ${buttons.size}" }
        }
    }

    /** Interactive list message with up to ten rows across sections. */
    data class ListMenu(
        val body: String,
        val buttonText: String,
        val sections: List<Section>,
        val header: String? = null,
        val footer: String? = null,
    ) : OutboundMessage {
        init {
            val rows = sections.sumOf { it.rows.size }
            require(rows in 1..10) { "WhatsApp allows 1 to 10 list rows, got $rows" }
        }
    }

    data class Button(val id: String, val title: String)

    data class Section(val title: String, val rows: List<Row>)

    data class Row(val id: String, val title: String, val description: String? = null)
}
