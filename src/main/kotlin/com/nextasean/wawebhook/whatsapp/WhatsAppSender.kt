package com.nextasean.wawebhook.whatsapp

/** Outbound side of the integration; implemented against the Graph API, faked in tests. */
interface WhatsAppSender {
    /** @return true when the Cloud API accepted the message. */
    fun send(to: String, message: OutboundMessage): Boolean

    fun sendText(to: String, body: String): Boolean = send(to, OutboundMessage.Text(body))
}
