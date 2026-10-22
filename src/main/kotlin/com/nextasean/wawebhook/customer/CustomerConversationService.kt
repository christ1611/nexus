package com.nextasean.wawebhook.customer

import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.conversation.DealerNotifier
import com.nextasean.wawebhook.conversation.Input
import com.nextasean.wawebhook.documents.DocumentCheckResult
import com.nextasean.wawebhook.documents.CheckContext
import com.nextasean.wawebhook.documents.DocumentCheckService
import com.nextasean.wawebhook.documents.DocumentRecord
import com.nextasean.wawebhook.documents.DocumentRepository
import com.nextasean.wawebhook.documents.DocumentStorage
import com.nextasean.wawebhook.documents.DocumentType
import com.nextasean.wawebhook.documents.MediaDownloader
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysisCodec
import com.nextasean.wawebhook.documents.analysis.FieldStatus
import com.nextasean.wawebhook.documents.analysis.Severity
import com.nextasean.wawebhook.review.DocumentRequest
import com.nextasean.wawebhook.review.ReviewAgent
import com.nextasean.wawebhook.review.ReviewAuditService
import com.nextasean.wawebhook.review.ReviewContextBuilder
import com.nextasean.wawebhook.review.ReviewDecision
import com.nextasean.wawebhook.review.ReviewEventKind
import com.nextasean.wawebhook.review.TracingReviewAgent
import com.nextasean.wawebhook.verification.ProviderOutcome
import com.nextasean.wawebhook.risk.RiskAssessmentService
import com.nextasean.wawebhook.trace.AgentTraceService
import com.nextasean.wawebhook.trace.TraceKind
import com.nextasean.wawebhook.trace.TraceStatus
import com.nextasean.wawebhook.verification.VerificationService
import com.nextasean.wawebhook.webhook.Message
import com.nextasean.wawebhook.whatsapp.BusinessPhoneHolder
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
    private val audit: ReviewAuditService,
    private val trace: AgentTraceService,
    private val reviewAgent: ReviewAgent?,
    private val businessPhone: BusinessPhoneHolder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Outcome of the back office inviting a customer: [sent] is null when no message was requested. */
    data class InviteResult(val application: DealerApplication, val sent: Boolean?)

    /**
     * Links a customer phone to a freshly created application and (optionally) sends the WhatsApp invitation. From then on
     * any message from that phone starts the journey, so the demo does not need a dealer phone to forward a link.
     */
    fun invite(app: DealerApplication, phone: String, customerName: String?, sendMessage: Boolean): InviteResult {
        val linked = applications.save(
            app.copy(customerPhone = phone, customerName = customerName?.trim()?.take(80)?.ifBlank { null } ?: app.customerName, updatedAt = Instant.now()),
        )
        val sent = if (sendMessage) send(linked, phone, CustomerMessages.invitation(linked, linked.customerName, businessPhone.startLink(linked.applicationNumber))) else null
        log.info("Customer invited | number={} | phone=***{} | messageSent={}", linked.applicationNumber, phone.takeLast(4), sent ?: "not requested")
        return InviteResult(linked, sent)
    }

    /** The newest application prepared for this phone that is still waiting for the customer to begin (no active session). */
    fun pendingInvitation(phone: String): DealerApplication? {
        if (sessions.findActiveByPhone(phone) != null) return null
        return applications.findByCustomerPhone(phone)
            .filter { it.status.acceptsCustomerDocuments && !it.demoSeed }
            .maxByOrNull { it.createdAt }
    }

    /** Outcome of an officer asking the customer for documents again (Step 4). */
    sealed interface OfficerRequestResult {
        data class Sent(val application: DealerApplication, val customerNotified: Boolean) : OfficerRequestResult
        data class NotReviewable(val application: DealerApplication) : OfficerRequestResult
        data class NoCustomerPhone(val application: DealerApplication) : OfficerRequestResult
    }

    /**
     * Step 4: the reviewing officer sends the customer back for specific documents. The listed records are superseded,
     * the application moves to DOCUMENTS_REQUESTED, a customer session is (re)opened at the first requested step and the
     * customer is told on WhatsApp which photos to resend and why. When the new photos are in, the normal checks run again.
     */
    fun requestDocuments(app: DealerApplication, items: List<DocumentRequest>, note: String?, actor: String?): OfficerRequestResult {
        require(items.isNotEmpty()) { "at least one document must be requested" }
        // Final applications and applications waiting for the dealer's form cannot be sent to the customer.
        if (!app.status.reviewable || app.status == ApplicationStatus.RESUBMISSION_REQUESTED) return OfficerRequestResult.NotReviewable(app)
        val phone = app.customerPhone ?: return OfficerRequestResult.NoCustomerPhone(app)
        val now = Instant.now()
        val types = items.map { it.type }.toSet()
        documents.findByApplicationIdOrderByCreatedAtAsc(app.id!!)
            .filter { it.type in types && !it.superseded }
            .forEach { documents.save(it.copy(superseded = true)) }
        val cleared = if (DocumentType.KTP in types) {
            app.copy(extractedName = null, extractedNik = null, extractedDob = null, extractedAddress = null)
        } else {
            app
        }
        val reason = items.joinToString("; ") { "${it.type.label}: ${it.reason.trim().trimEnd('.')}" }
        val saved = applications.save(
            cleared.copy(
                status = ApplicationStatus.DOCUMENTS_REQUESTED,
                resubmissionReason = reason.take(500),
                reviewDecision = "OFFICER_REQUEST_DOCUMENTS",
                // The old summary described the superseded document; the agent (or the officer) writes a new one.
                reviewSummary = null,
                officerReviewRequired = null,
                reviewRounds = app.reviewRounds + 1,
                reviewedAt = now,
                updatedAt = now,
            ),
        )
        val previous = sessions.findByApplicationIdOrderByCreatedAtDesc(saved.id!!).firstOrNull()
        sessions.findActiveByPhone(phone)?.let { sessions.save(it.copy(state = CustomerSessionState.CANCELLED, updatedAt = now)) }
        val next = items.minBy { it.type.step }.type
        val consented = previous?.consentAt != null
        sessions.save(
            CustomerSession(
                phone = phone,
                customerName = previous?.customerName ?: saved.customerName,
                applicationId = saved.id,
                state = if (consented) stateFor(next) else CustomerSessionState.AWAITING_CONSENT,
                consentAt = previous?.consentAt,
            ),
        )
        log.info("Officer requested documents | number={} | by={} | round={} | items={}", saved.applicationNumber, actor ?: "n/a", saved.reviewRounds, items)
        audit.record(saved, ReviewEventKind.DOCUMENTS_REQUESTED, actor, reason + (note?.takeIf { it.isNotBlank() }?.let { " | note: $it" } ?: ""))
        trace.record(saved.id, TraceKind.OFFICER_REQUEST, TraceStatus.DECISION, AgentTraceService.officer(actor), "Officer sent the customer back for ${items.joinToString(" + ") { it.type.label }}", reason, round = app.reviewRounds)
        val notified = send(saved, phone, CustomerMessages.documentsRequestedByOfficer(saved, items, next, note))
        if (!consented) send(saved, phone, CustomerMessages.greetingAndConsent(saved, previous?.customerName ?: saved.customerName))
        return OfficerRequestResult.Sent(saved, notified)
    }

    /** Handles "START <application number>" from any phone; a bare START (or any first message) picks up a pending invitation. */
    fun start(phone: String, contactName: String?, applicationNumber: String?) {
        val app = applicationNumber?.let { applications.findByApplicationNumber(it.uppercase()) } ?: if (applicationNumber == null) pendingInvitation(phone) else null
        if (app == null) {
            send(phone, CustomerMessages.unknownApplication(applicationNumber))
            return
        }
        if (app.customerPhone != null && app.customerPhone != phone) {
            send(phone, CustomerMessages.linkedToOtherPhone(app))
            return
        }
        if (!app.status.acceptsCustomerDocuments) {
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
        if (!app.status.acceptsCustomerDocuments) {
            // The officer decided (or the dealer form is pending) while this session was still open: close it instead of letting a photo re-open the case.
            sessions.save(session.copy(state = CustomerSessionState.CANCELLED, updatedAt = Instant.now()))
            log.info("Customer session closed: application {} is {} | phone={}", app.applicationNumber, app.status, session.phone)
            send(session.phone, CustomerMessages.notAcceptingDocuments(app))
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
        trace.record(
            app.id, TraceKind.DOCUMENT_RECEIVED, TraceStatus.INFO, AgentTraceService.CUSTOMER, "${type.label} photo received",
            "${media.bytes.size / 1024} KB, ${media.mimeType ?: "unknown type"}${image.caption?.takeIf { it.isNotBlank() }?.let { ", caption \"$it\"" } ?: ""}",
            mapOf("mediaId" to mediaId, "mimeType" to media.mimeType, "bytes" to media.bytes.size), round = app.reviewRounds,
        )
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

        val checkStarted = System.currentTimeMillis()
        val result = documentCheck.check(type, file, media.mimeType, image.caption, CheckContext(app.applicationNumber, session.phone))
        traceCheck(app, type, result, System.currentTimeMillis() - checkStarted)
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
            trace.record(app.id, TraceKind.CUSTOMER_MESSAGED, TraceStatus.DECISION, AgentTraceService.SYSTEM, "Asked the customer to retake the ${type.label}", result.reason, round = app.reviewRounds)
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
        try {
            completeAndVerify(session, current)
        } catch (e: Exception) {
            // Whatever failed, the session must not stay in VERIFYING forever: back to the last step with an apology.
            log.error("Completion failed | application={}", app.applicationNumber, e)
            sessions.save(session.copy(state = stateFor(type), updatedAt = Instant.now()))
            send(session.phone, CustomerMessages.requestDocument(type, "Sorry, something went wrong while checking your data."))
        }
    }

    /** Closes any open session of the application's customer (after a final decision). */
    fun closeSessions(app: DealerApplication) {
        val id = app.id ?: return
        sessions.findByApplicationIdOrderByCreatedAtDesc(id).filter { it.state.active }
            .forEach { sessions.save(it.copy(state = CustomerSessionState.CANCELLED, updatedAt = Instant.now())) }
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
            trace.record(saved.id, TraceKind.HANDOFF, TraceStatus.WARN, AgentTraceService.RULES, "No confidently read ID number: provider lookups skipped, officer must verify", round = saved.reviewRounds)
            val (assessed, assessment) = risk.assessAndStore(saved)
            traceRisk(assessed, assessment)
            audit.record(assessed, ReviewEventKind.DOCUMENTS_COMPLETE, ReviewAuditService.SYSTEM, "Handed to officer without provider checks (ID number not read confidently)")
            audit.record(assessed, ReviewEventKind.RISK_ASSESSED, ReviewAuditService.SYSTEM, assessment.headline)
            send(session.phone, CustomerMessages.submitted(assessed))
            notifier.documentsForOfficerReview(assessed, assessment)
            return
        }
        val verifyStarted = System.currentTimeMillis()
        val outcome = try {
            verification.verifyAndSubmit(reviewed)
        } catch (e: Exception) {
            log.error("Verification failed | application={}", app.applicationNumber, e)
            trace.record(app.id, TraceKind.ID_VERIFICATION, TraceStatus.FAIL, AgentTraceService.SYSTEM, "Verification call failed", e.message, durationMs = System.currentTimeMillis() - verifyStarted, round = app.reviewRounds)
            sessions.save(session.copy(state = CustomerSessionState.AWAITING_SELFIE, updatedAt = Instant.now()))
            send(session.phone, CustomerMessages.requestDocument(DocumentType.SELFIE_KTP, "Sorry, something went wrong while checking your data."))
            return
        }
        sessions.save(session.copy(state = CustomerSessionState.COMPLETED, updatedAt = Instant.now()))
        trace.record(
            app.id, TraceKind.ID_VERIFICATION, if (outcome.id.outcome == ProviderOutcome.MATCH) TraceStatus.OK else TraceStatus.WARN, AgentTraceService.provider(outcome.id.provider),
            "${outcome.id.provider}: ${outcome.id.outcome.label}", outcome.id.detail, mapOf("outcome" to outcome.id.outcome.name), durationMs = outcome.id.latencyMs, round = app.reviewRounds,
        )
        trace.record(
            app.id, TraceKind.CREDIT_CHECK, if (outcome.credit.outcome == ProviderOutcome.MATCH) TraceStatus.OK else TraceStatus.WARN, AgentTraceService.provider(outcome.credit.provider),
            "${outcome.credit.provider}: ${outcome.credit.outcome.label}${outcome.credit.score?.let { ", score $it" } ?: ""}", outcome.credit.detail,
            mapOf("outcome" to outcome.credit.outcome.name, "score" to outcome.credit.score), durationMs = outcome.credit.latencyMs, round = app.reviewRounds,
        )
        val (assessed, assessment) = risk.assessAndStore(outcome.application)
        traceRisk(assessed, assessment)
        audit.record(
            assessed, ReviewEventKind.DOCUMENTS_COMPLETE, ReviewAuditService.SYSTEM,
            "ID verification: ${outcome.id.outcome.label}; credit bureau: ${outcome.credit.outcome.label}${outcome.credit.score?.let { ", score $it" } ?: ""}" +
                (if (assessed.reviewRounds > 0) " (document round ${assessed.reviewRounds + 1})" else ""),
        )
        audit.record(assessed, ReviewEventKind.RISK_ASSESSED, ReviewAuditService.SYSTEM, assessment.headline)
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
                trace.record(
                    app.id, TraceKind.AGENT_DECISION, TraceStatus.DECISION, agentActor(), "Decision: request ${decision.items.joinToString(" + ") { it.type.label }} again (round ${app.reviewRounds + 1} of ${context.maxRounds})",
                    decision.items.joinToString("; ") { "${it.type.label}: ${it.reason}" } + (decision.note?.let { " | note: $it" } ?: ""),
                    mapOf("tool" to "request_documents", "documents" to decision.items.map { mapOf("type" to it.type.name, "reason" to it.reason) }, "note" to decision.note), round = app.reviewRounds,
                )
                send(session.phone, CustomerMessages.documentsRequested(decision.items, next, decision.note))
                trace.record(app.id, TraceKind.CUSTOMER_MESSAGED, TraceStatus.INFO, AgentTraceService.SYSTEM, "Customer asked on WhatsApp to resend: ${decision.items.joinToString(", ") { it.type.label }}", round = app.reviewRounds)
                null
            }
            is ReviewDecision.SummaryWritten -> {
                log.info(
                    "Review agent wrote summary | number={} | officerReview={} | chars={}\n{}",
                    app.applicationNumber, decision.officerReviewRequired, decision.summary.length, decision.summary,
                )
                trace.record(
                    app.id, TraceKind.AGENT_DECISION, if (decision.officerReviewRequired) TraceStatus.WARN else TraceStatus.OK, agentActor(),
                    "Decision: write the officer summary" + (if (decision.officerReviewRequired) " - officer review required" else " - no manual items"),
                    decision.summary.lineSequence().take(3).joinToString(" / ").take(300),
                    mapOf("tool" to "write_summary", "officerReviewRequired" to decision.officerReviewRequired, "chars" to decision.summary.length), round = app.reviewRounds,
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

    /** Records what the document check did, in enough detail for the agent timeline (stub or Claude vision). */
    private fun traceCheck(app: DealerApplication, type: DocumentType, result: DocumentCheckResult, ms: Long) {
        val a = result.analysis
        val round = app.reviewRounds
        if (a == null) {
            if (result.analysisFailed) {
                trace.record(app.id, TraceKind.VISION_ANALYSIS, TraceStatus.FAIL, AgentTraceService.SYSTEM, "Vision analysis unavailable; document kept for an officer", result.reason, durationMs = ms, round = round)
            } else {
                trace.record(
                    app.id, TraceKind.QUALITY_CHECK, if (result.passed) TraceStatus.OK else TraceStatus.FAIL, AgentTraceService.STUB,
                    "${type.label}: ${if (result.passed) "accepted" else "rejected"}", result.reason ?: "Demo check (stub mode): no vision model configured", durationMs = ms, round = round,
                )
            }
        } else {
            val high = a.qualityIssues.filter { it.severity == Severity.HIGH }
            trace.record(
                app.id, TraceKind.VISION_ANALYSIS, if (a.usable) TraceStatus.OK else TraceStatus.FAIL, AgentTraceService.claude(a.model),
                "Seen as ${a.documentType.label} (${(a.documentTypeConfidence * 100).toInt()}%)" + (if (a.usable) ", usable" else ", not usable"),
                (if (a.qualityIssues.isEmpty()) "No quality issues" else a.qualityIssues.joinToString("; ") { "${it.type} ${it.severity}: ${it.detail}" }) +
                    (a.modelNotes?.let { " | notes: $it" } ?: ""),
                mapOf(
                    "documentType" to a.documentType.name, "confidence" to a.documentTypeConfidence, "typeMatchesRequest" to a.typeMatchesRequest,
                    "qualityIssues" to a.qualityIssues, "highSeverityIssues" to high.size, "usage" to a.usage,
                    "fields" to a.fields.map { mapOf("name" to it.name, "status" to it.status.name, "confidence" to it.confidence) },
                ),
                durationMs = a.usage?.durationMs ?: ms, round = round,
            )
            a.nik?.let { n ->
                trace.record(
                    app.id, TraceKind.NIK_VALIDATION, if (n.valid) TraceStatus.OK else TraceStatus.FAIL, AgentTraceService.RULES,
                    if (n.valid) "NIK structure valid (${n.provinceName ?: n.provinceCode})" else "NIK validation failed: ${n.issues.firstOrNull() ?: "see issues"}",
                    "Encoded DOB ${n.dobFromNik ?: "invalid"} (${n.genderFromNik ?: "?"}) vs card ${n.statedDob ?: "?"} (${n.statedGender ?: "?"})" + (if (n.issues.isEmpty()) "" else " | " + n.issues.joinToString("; ")),
                    mapOf("formatValid" to n.formatValid, "regionValid" to n.regionValid, "dobMatches" to n.dobMatches, "genderMatches" to n.genderMatches, "issues" to n.issues), round = round,
                )
            }
            if (type == DocumentType.KTP && a.usable) {
                val f = result.fields
                val review = a.fields.filter { it.status == FieldStatus.NEEDS_OFFICER_REVIEW }.map { it.name }
                val hidden = a.fields.filter { it.status == FieldStatus.NOT_VISIBLE }.map { it.name }
                trace.record(
                    app.id, TraceKind.FIELD_EXTRACTION, if (f != null) TraceStatus.OK else TraceStatus.WARN, AgentTraceService.RULES,
                    if (f != null) "Name, NIK, date of birth and address read with confidence >= ${"%.2f".format(minFieldConfidence())}" else "Required fields not confident enough: no automatic verification",
                    listOfNotNull(review.takeIf { it.isNotEmpty() }?.let { "needs officer review: ${it.joinToString()}" }, hidden.takeIf { it.isNotEmpty() }?.let { "not visible: ${it.joinToString()}" }).joinToString(" | ").ifBlank { null },
                    mapOf("promoted" to (f != null), "needsOfficerReview" to review, "notVisible" to hidden), round = round,
                )
            }
        }
        if (a == null && result.passed && result.fields != null) {
            trace.record(app.id, TraceKind.FIELD_EXTRACTION, TraceStatus.OK, AgentTraceService.STUB, "Sample fields used (stub mode): NIK ***${result.fields.nik.takeLast(4)}", round = round)
        }
    }

    private fun traceRisk(app: DealerApplication, a: com.nextasean.wawebhook.risk.RiskAssessment) {
        trace.record(
            app.id, TraceKind.RISK_ASSESSMENT, when { a.grade.highRisk -> TraceStatus.WARN; a.flags.isEmpty() -> TraceStatus.OK; else -> TraceStatus.INFO }, AgentTraceService.RULES,
            a.headline, if (a.flags.isEmpty()) "No risk flags" else a.flags.joinToString("; ") { "${it.code} (${it.severity.name.lowercase()})" },
            mapOf("grade" to a.grade.name, "score" to a.score, "method" to a.method.name, "flags" to a.flags.map { it.code }, "badges" to a.badges.map { it.name }), round = app.reviewRounds,
        )
        trace.record(app.id, TraceKind.SUBMITTED, TraceStatus.OK, AgentTraceService.SYSTEM, "Application is ready for review", "Customer and dealer notified", round = app.reviewRounds)
    }

    private fun agentActor(): String = (reviewAgent as? TracingReviewAgent)?.actor ?: AgentTraceService.RULES

    private fun minFieldConfidence(): Double = (reviewAgent as? TracingReviewAgent)?.minFieldConfidence ?: 0.85

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

    private fun send(phone: String, message: OutboundMessage): Boolean = sender.send(phone, message)

    /** Demo rows must never reach a real phone, whatever an officer does with them. */
    private fun send(app: DealerApplication, phone: String, message: OutboundMessage): Boolean =
        if (app.demoSeed) { log.info("Demo application {}: customer message suppressed", app.applicationNumber); false } else send(phone, message)

    private companion object {
        val AGREE_WORDS = setOf("agree", "i agree", "yes", "ya", "setuju", "ok", "oke")
        val DECLINE_WORDS = setOf("decline", "no", "tidak", "cancel")
    }
}
