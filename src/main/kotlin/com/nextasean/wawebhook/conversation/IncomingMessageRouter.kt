package com.nextasean.wawebhook.conversation

import com.nextasean.wawebhook.customer.CustomerConversationService
import com.nextasean.wawebhook.customer.CustomerSessionRepository
import com.nextasean.wawebhook.customer.findActiveByPhone
import com.nextasean.wawebhook.dealer.DealerRepository
import com.nextasean.wawebhook.webhook.Message
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Decides which flow a message belongs to:
 * 1. `START <number>` always enters the customer flow (also for dealers testing with their own phone);
 * 2. a phone with an active customer session stays in the customer flow (`CANCEL` leaves it);
 * 3. everything else goes to the dealer flow, which rejects unregistered numbers.
 * Messages from the same phone are handled one at a time.
 */
@Component
class IncomingMessageRouter(
    private val dealers: DealerRepository,
    private val dealerFlow: DealerConversationService,
    private val customerFlow: CustomerConversationService,
    private val customerSessions: CustomerSessionRepository,
) : IncomingMessageHandler {

    private val log = LoggerFactory.getLogger(javaClass)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    override fun handle(from: String, contactName: String?, message: Message) {
        locks.computeIfAbsent(from) { ReentrantLock() }.withLock { route(from, contactName, message) }
    }

    private fun route(from: String, contactName: String?, message: Message) {
        val text = message.text?.body?.trim().takeIf { message.type == "text" }

        START_PATTERN.matchEntire(text.orEmpty())?.let { match ->
            log.info("Routing | from={} | customer START | application={}", from, match.groupValues[1].ifBlank { "none" })
            customerFlow.start(from, contactName, match.groupValues[1].uppercase().ifBlank { null })
            return
        }

        val session = customerSessions.findActiveByPhone(from)
        if (session != null) {
            if (text != null && text.lowercase() in CANCEL_WORDS) {
                customerFlow.cancel(session)
                if (dealers.findByPhone(from) != null) dealerFlow.handle(from, contactName, message)
                return
            }
            log.info("Routing | from={} | customer session {} ({})", from, session.id, session.state)
            customerFlow.handle(session, message)
            return
        }

        if (text != null && text.lowercase().startsWith("start") && dealers.findByPhone(from) == null) {
            customerFlow.start(from, contactName, null)
            return
        }
        dealerFlow.handle(from, contactName, message)
    }

    private companion object {
        val START_PATTERN = Regex("(?i)^start\\s+(APP-\\d{4}-\\d{6})\\s*$")
        val CANCEL_WORDS = setOf("cancel", "batal")
    }
}
