package com.nextasean.wawebhook.customer

import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.whatsapp.OutboundMessage
import com.nextasean.wawebhook.whatsapp.WhatsAppSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Step 4: pushes back-office outcomes to the customer's own WhatsApp number (the dealer is notified separately).
 * Returns null when the application has no linked customer phone, otherwise whether the Cloud API accepted the message.
 * Note: outside the 24 h customer-service window Meta rejects free-form messages; a pre-approved template is still to do.
 */
@Service
class CustomerNotifier(private val sender: WhatsAppSender) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun decision(app: DealerApplication): Boolean? = notify(app, CustomerMessages.decision(app))

    private fun notify(app: DealerApplication, message: OutboundMessage): Boolean? {
        if (app.demoSeed) {
            log.info("Demo application {}: customer notification suppressed ({})", app.applicationNumber, message::class.simpleName)
            return false
        }
        val phone = app.customerPhone
        if (phone.isNullOrBlank()) {
            log.info("No customer phone on {}; customer not notified ({})", app.applicationNumber, message::class.simpleName)
            return null
        }
        log.info("Notifying customer | application={} | kind={}", app.applicationNumber, message::class.simpleName)
        return sender.send(phone, message)
    }
}
