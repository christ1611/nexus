package com.nextasean.wawebhook.customer

import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.review.ReviewAuditService
import com.nextasean.wawebhook.review.ReviewEventKind
import com.nextasean.wawebhook.webhook.DeliveryFailureListener
import org.springframework.stereotype.Component

/**
 * Puts a WhatsApp delivery failure on the timeline of the customer's open application(s), so the officer sees why an
 * invitation or a notification never arrived. 131047 ("re-engagement") gets the practical hint: the customer must write first.
 */
@Component
class DeliveryFailureRecorder(private val applications: ApplicationRepository, private val audit: ReviewAuditService) : DeliveryFailureListener {

    override fun onFailed(recipientPhone: String, reason: String, errorCodes: List<Int>) {
        val hint = if (RE_ENGAGEMENT in errorCodes) " Ask the customer to send any message (e.g. \"hi\") to this number first; the journey then starts automatically." else ""
        applications.findByCustomerPhone(recipientPhone)
            .filter { !it.status.isFinal && !it.demoSeed }
            .forEach { app -> audit.record(app, ReviewEventKind.MESSAGE_FAILED, ReviewAuditService.SYSTEM, "$reason.$hint") }
    }

    private companion object {
        /** Meta error: more than 24 hours since the customer last wrote to this number. */
        const val RE_ENGAGEMENT = 131047
    }
}
