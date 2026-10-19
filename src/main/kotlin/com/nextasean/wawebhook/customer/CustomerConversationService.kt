package com.nextasean.wawebhook.customer

import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.conversation.DealerNotifier
import com.nextasean.wawebhook.conversation.Input
import com.nextasean.wawebhook.documents.DocumentCheckService
import com.nextasean.wawebhook.documents.DocumentRecord
import com.nextasean.wawebhook.documents.DocumentRepository
import com.nextasean.wawebhook.documents.DocumentStorage
import com.nextasean.wawebhook.documents.DocumentType
import com.nextasean.wawebhook.documents.MediaDownloader
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysisCodec
import com.nextasean.wawebhook.review.ReviewAgent
import com.nextasean.wawebhook.review.ReviewContextBuilder
import com.nextasean.wawebhook.review.ReviewDecision
import com.nextasean.wawebhook.risk.RiskAssessmentService
import com.nextasean.wawebhook.verification.VerificationService
import com.nextasean.wawebhook.webhook.Message
import com.nextasean.wawebhook.whatsapp.OutboundMessage
import com.nextasean.wawebhook.whatsapp.WhatsAppSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Customer journey: START -> consent -> KTP -> selfie -> (review agent) -> verification -> submitted.
 * State lives in CUSTOMER_SESSIONS; each step is persisted before the next prompt is sent.
 *
 * When a [ReviewAgent] bean exists (Claude mode), it runs once every document has been accepted and either sends the
 * customer back for specific documents or writes the one-page summary; without one the flow goes straight to verification.
 */
@Service
class CustomerConversationService(
    private val sessions: CustomerSessionRepository,
    private val applications: ApplicationRepository,
    private val documents: DocumentRepository,
    private val mediaDownloader: MediaDownloader,
    private val storage: DocumentStorage,
    private val documentCheck: DocumentCheckService,
    private val verification: VerificationService,
    private val notifier: DealerNotifier,
    private val sender: WhatsAppSender,
    private val reviewContext: ReviewContextBuilder,
    private val analysisCodec: DocumentAnalysisCodec,
    private val risk: RiskAssessmentService,
    private val reviewAgent: ReviewAgent?,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Handles "START <application number>" from any phone. */
    fun start(phone: String, contactName: String?, applicationNumber: String?) {
        val app = applicationNumber?.let { applications.findByApplicationNumber(it.uppercase()) }
        if (app == null) {
            send(phone, CustomerMessages.unknownApplication(applicationNumber))
            return
        }
        if (app.customerPhone != null && app.customerPhone != phone) {
            send(phone, CustomerMessages.linkedToOtherPhone(app))
            return
        }
        if (!app.status.acceptsSubmission) {
            send(phone, CustomerMessages.notAcceptingDocuments(app))
            return
        }

        sessions.findActiveByPhone(phone)?.let { existing ->
            if (existing.applicationId == app.id) {
                log.info("Customer resumed | phone={} | application={} | state={}", phone, app.applicationNumber, existing.state)
                prompt(existing, app)
                return
            }
            sessions.save(existing.copy(state = CustomerSessionState.CANCELLED, updatedAt = Instant.now()))
        }

        val linked = if (app.customerPhone == null) {
            applications.save(app.copy(customerPhone = phone, customerName = contactName, updatedAt = Instant.now()))
        } else {
            app
        }
        val session = sessions.save(CustomerSession(phone = phone, customerName = contactName, applicationId = linked.id!!))
        log.info("Customer session started | phone={} | application={}", phone, linked.applicationNumber)
        send(phone, CustomerMessages.greetingAndConsent(linked, contactName))
        check(session.state == CustomerSessionState.AWAITING_CONSENT)
    }

    fun cancel(session: CustomerSession) {
        val app = applications.findById(session.applicationId).orElse(null)
        sessions.save(session.copy(state = CustomerSessionState.CANCELLED, updatedAt = Instant.now()))
        log.info("Customer session cancelled | phone={} | application={}", session.phone, app?.applicationNumber)
        app?.let { send(session.phone, CustomerMessages.cancelled(it)) }
    }

    /** Handles any message from a phone with an active session. */
    fun handle(session: CustomerSession, message: Message) {
        val app = applications.findById(session.applicationId).orElse(null)
        if (app == null) {
            log.error("Session {} points to missing application {}", session.id, session.applicationId)
            sessions.save(session.copy(state = CustomerSessionState.CANCELLED, updatedAt = Instant.now()))
            return
        }
        val input = Input.of(message)
        when (session.state) {
            CustomerSessionState.AWAITING_CONSENT -> onConsent(session, app, input)
            CustomerSessionState.AWAITING_KTP -> onDocumentStep(session, app, DocumentType.KTP, input)
            CustomerSessionState.AWAITING_SELFIE -> onDocumentStep(session, app, DocumentType.SELFIE_KTP, input)
            CustomerSessionState.VERIFYING -> send(session.phone, CustomerMessages.stillChecking())
            else -> log.warn("Message for inactive session {} ignored", session.id)
        }
    }

    private fun onConsent(session: CustomerSession, app: DealerApplication, input: Input) {
        val answer = when (input) {
            is Input.Selection -> input.id
            is Input.Text -> when (input.text.lowercase()) {
                in AGREE_WORDS -> CustomerReplyIds.CONSENT_AGREE
                in DECLINE_WORDS -> CustomerReplyIds.CONSENT_DECLINE
                else -> null
            }
            else -> null
        }
        when (answer) {
            CustomerReplyIds.CONSENT_AGREE -> {
                val now = Instant.now()
                sessions.save(session.copy(state = CustomerSessionState.AWAITING_KTP, consentAt = now, updatedAt = now))
                log.info("Consent given | phone={} | application={} | at={}", session.phone, app.applicationNumber, now)
                send(session.phone, CustomerMessages.requestDocument(DocumentType.KTP, "Thank you."))
            }
            CustomerReplyIds.CONSENT_DECLINE -> {
                val now = Instant.now()
                sessions.save(session.copy(state = CustomerSessionState.DECLINED, declinedAt = now, updatedAt = now))
                log.info("Consent declined | phone={} | application={} | at={}", session.phone, app.applicationNumber, now)
                send(session.phone, CustomerMessages.declined())
            }
            else -> send(session.phone, CustomerMessages.greetingAndConsent(app, session.customerName))
        }
    }

    private fun onDocumentStep(session: CustomerSession, app: DealerApplication, type: DocumentType, input: Input) {
        val image = input as? Input.Image
        val mediaId = image?.mediaId
        if (image == null || mediaId == null) {
            // Text (or anything that is not an image) while an image is expected: repeat the request.
            send(session.phone, CustomerMessages.requestDocument(type))
            return
        }

        val media = try {
            mediaDownloader.download(mediaId)
        } catch (e: Exception) {
            log.error("Media download failed | mediaId={} | application={}", mediaId, app.applicationNumber, e)
            send(session.phone, CustomerMessages.downloadFailed(type))
            return
        }
        val file = storage.save(app.applicationNumber, type, media)
        var record = documents.save(
            DocumentRecord(
                applicationId = app.id!!,
                type = type,
                mediaId = mediaId,
                filePath = file.toString(),
                mimeType = media.mimeType,
                caption = image.caption,
            ),
        )
        send(session.phone, CustomerMessages.received(type))

        val result = documentCheck.check(type, file, media.mimeType, image.caption)
        record = documents.save(
            record.copy(
                checkPassed = result.passed,
                checkReason = result.reason,
                detectedType = result.analysis?.documentType?.name,
                needsOfficerReview = result.analysis?.needsOfficerReview ?: if (result.analysisFailed) true else null,
                analysisJson = result.analysis?.let(analysisCodec::toJson),
            ),
        )
        if (!result.passed) {
            log.info("Document check failed | application={} | type={} | reason={}", app.applicationNumber, type, result.reason)
            send(session.phone, CustomerMessages.checkFailed(type, result.reason ?: "quality check failed"))
            return
        }

        var current = app
        result.fields?.let { f ->
            current = applications.save(
                app.copy(extractedName = f.name, extractedNik = f.nik, extractedDob = f.dob, extractedAddress = f.address, updatedAt = Instant.now()),
            )
        }

        val next = reviewContext.nextMissing(app.id)
        if (next != null) {
            sessions.save(session.copy(state = stateFor(next), updatedAt = Instant.now()))
            send(session.phone, CustomerMessages.requestDocument(next))
            return
        }
        completeAndVerify(session, current)
    }

    private fun completeAndVerify(session: CustomerSession, app: DealerApplication) {
        sessions.save(session.copy(state = CustomerSessionState.VERIFYING, updatedAt = Instant.now()))
        send(session.phone, CustomerMessages.checking())
        val reviewed = if (reviewAgent != null) review(session, app) ?: return else app
        if (reviewAgent != null && reviewed.extractedNik == null) {
            // Nothing confident enough to send to the providers: the officer takes it from here.
            val saved = applications.save(
                reviewed.copy(status = ApplicationStatus.READY_FOR_REVIEW, linkTokenHash = null, linkExpiresAt = null, updatedAt = Instant.now()),
            )
            sessions.save(session.copy(state = CustomerSessionState.COMPLETED, updatedAt = Instant.now()))
            log.info("Application handed to officer without provider checks | number={}", saved.applicationNumber)
            val (assessed, assessment) = risk.assessAndStore(saved)
            send(session.phone, CustomerMessages.submitted(assessed))
            notifier.documentsForOfficerReview(assessed, assessment)
            return
        }
        val outcome = try {
            verification.verifyAndSubmit(reviewed)
        } catch (e: Exception) {
            log.error("Verification failed | application={}", app.applicationNumber, e)
            sessions.save(session.copy(state = CustomerSessionState.AWAITING_SELFIE, updatedAt = Instant.now()))
            send(session.phone, CustomerMessages.requestDocument(DocumentType.SELFIE_KTP, "Sorry, something went wrong while checking your data."))
            return
        }
        sessions.save(session.copy(state = CustomerSessionState.COMPLETED, updatedAt = Instant.now()))
        val (assessed, assessment) = risk.assessAndStore(outcome.application)
        send(session.phone, CustomerMessages.submitted(assessed))
        notifier.documentsSubmitted(outcome.copy(application = assessed), assessment)
    }

    /**
     * Runs the review agent. Returns the application to continue with, or null when the customer was sent back for
     * documents (the session state has then been reset to the first requested step).
     */
    private fun review(session: CustomerSession, app: DealerApplication): DealerApplication? {
        val context = reviewContext.build(app)
        val decision = reviewAgent!!.decide(context)
        val now = Instant.now()
        return when (decision) {
            is ReviewDecision.RequestDocuments -> {
                val types = decision.items.map { it.type }.toSet()
                documents.findByApplicationIdOrderByCreatedAtAsc(app.id!!)
                    .filter { it.type in types && !it.superseded }
                    .forEach { documents.save(it.copy(superseded = true)) }
                val cleared = if (DocumentType.KTP in types) {
                    app.copy(extractedName = null, extractedNik = null, extractedDob = null, extractedAddress = null)
                } else {
                    app
                }
                applications.save(cleared.copy(reviewDecision = "REQUEST_DOCUMENTS", reviewRounds = app.reviewRounds + 1, reviewedAt = now, updatedAt = now))
                val next = decision.items.minBy { it.type.step }.type
                sessions.save(session.copy(state = stateFor(next), updatedAt = now))
                log.info("Review agent requested documents | number={} | round={} | items={}", app.applicationNumber, app.reviewRounds + 1, decision.items)
                send(session.phone, CustomerMessages.documentsRequested(decision.items, next, decision.note))
                null
            }
            is ReviewDecision.SummaryWritten -> {
                log.info(
                    "Review agent wrote summary | number={} | officerReview={} | chars={}\n{}",
                    app.applicationNumber, decision.officerReviewRequired, decision.summary.length, decision.summary,
                )
                applications.save(
                    app.copy(
                        reviewDecision = "SUMMARY_WRITTEN",
                        reviewSummary = decision.summary.take(16_000),
                        officerReviewRequired = decision.officerReviewRequired,
                        reviewedAt = now,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    private fun prompt(session: CustomerSession, app: DealerApplication) = when (session.state) {
        CustomerSessionState.AWAITING_CONSENT -> send(session.phone, CustomerMessages.greetingAndConsent(app, session.customerName))
        CustomerSessionState.AWAITING_KTP -> send(session.phone, CustomerMessages.requestDocument(DocumentType.KTP))
        CustomerSessionState.AWAITING_SELFIE -> send(session.phone, CustomerMessages.requestDocument(DocumentType.SELFIE_KTP))
        CustomerSessionState.VERIFYING -> send(session.phone, CustomerMessages.stillChecking())
        else -> Unit
    }

    private fun stateFor(type: DocumentType) = when (type) {
        DocumentType.KTP -> CustomerSessionState.AWAITING_KTP
        DocumentType.SELFIE_KTP -> CustomerSessionState.AWAITING_SELFIE
    }

    private fun send(phone: String, message: OutboundMessage) {
        sender.send(phone, message)
    }

    private companion object {
        val AGREE_WORDS = setOf("agree", "i agree", "yes", "ya", "setuju", "ok", "oke")
        val DECLINE_WORDS = setOf("decline", "no", "tidak", "cancel")
    }
}
