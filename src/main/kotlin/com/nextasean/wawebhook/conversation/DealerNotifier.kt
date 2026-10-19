package com.nextasean.wawebhook.conversation

import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.application.IssuedLink
import com.nextasean.wawebhook.config.AppProperties
import com.nextasean.wawebhook.dealer.DealerRepository
import com.nextasean.wawebhook.risk.RiskAssessment
import com.nextasean.wawebhook.verification.VerificationOutcome
import com.nextasean.wawebhook.whatsapp.OutboundMessage
import com.nextasean.wawebhook.whatsapp.WhatsAppSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Pushes back-office events (form submitted, resubmission requested, decision) to the dealer on WhatsApp. */
@Service
class DealerNotifier(
    private val dealers: DealerRepository,
    private val sender: WhatsAppSender,
    props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val messages = DealerMessages(props.zoneId)

    fun submitted(app: DealerApplication): Boolean = notify(app, messages.submitted(app))

    fun resubmissionRequested(link: IssuedLink): Boolean = notify(link.application, messages.resubmissionRequested(link))

    fun decision(app: DealerApplication): Boolean = notify(app, messages.decision(app))

    fun documentsSubmitted(outcome: VerificationOutcome, risk: RiskAssessment? = null): Boolean =
        notify(outcome.application, messages.documentsSubmitted(outcome, risk))

    fun documentsForOfficerReview(app: DealerApplication, risk: RiskAssessment? = null): Boolean =
        notify(app, messages.documentsForOfficerReview(app, risk))

    private fun notify(app: DealerApplication, message: OutboundMessage): Boolean {
        val dealer = dealers.findById(app.dealerId).orElse(null)
        if (dealer == null) {
            log.error("Cannot notify: dealer {} not found for application {}", app.dealerId, app.applicationNumber)
            return false
        }
        log.info("Notifying dealer | dealer={} | application={} | kind={}", dealer.dealerCode, app.applicationNumber, message::class.simpleName)
        return sender.send(dealer.phone, message)
    }
}
