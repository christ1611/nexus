package com.nextasean.wawebhook.conversation

import com.nextasean.wawebhook.webhook.Message

/** Receives each de-duplicated incoming message after it has been logged. */
fun interface IncomingMessageHandler {
    fun handle(from: String, contactName: String?, message: Message)
}
