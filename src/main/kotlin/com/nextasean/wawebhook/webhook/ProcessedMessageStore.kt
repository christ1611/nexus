package com.nextasean.wawebhook.webhook

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Remembers recently seen message IDs so redelivered webhooks are not handled twice.
 * In-memory and bounded (oldest entries evicted first); it does not survive a restart
 * and is not shared between instances.
 */
@Component
class ProcessedMessageStore(@Value("\${webhook.dedupe-cache-size:10000}") maxEntries: Int) {

    private val seen = object : LinkedHashMap<String, Boolean>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > maxEntries
    }

    /** @return true if [messageId] was not seen before (and is now recorded), false if it is a duplicate. */
    @Synchronized
    fun markIfNew(messageId: String): Boolean = seen.put(messageId, true) == null
}
