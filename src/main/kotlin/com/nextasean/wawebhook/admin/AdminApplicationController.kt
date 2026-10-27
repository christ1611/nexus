package com.nextasean.wawebhook.admin

import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.application.ApplicationService
import com.nextasean.wawebhook.application.StageProgress
import com.nextasean.wawebhook.application.StageProgressService
import com.nextasean.wawebhook.review.ReviewCopilotService
import com.anthropic.errors.AnthropicException
import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.application.Decision
import com.nextasean.wawebhook.conversation.DealerNotifier
import com.nextasean.wawebhook.customer.CustomerConversationService
import com.nextasean.wawebhook.customer.CustomerConversationService.OfficerRequestResult
import com.nextasean.wawebhook.customer.CustomerNotifier
import com.nextasean.wawebhook.dealer.Dealer
import com.nextasean.wawebhook.dealer.DealerRepository
import com.nextasean.wawebhook.dealer.normalizePhone
import com.nextasean.wawebhook.whatsapp.BusinessPhoneHolder
import com.nextasean.wawebhook.documents.DocumentRecord
import com.nextasean.wawebhook.documents.DocumentRepository
import com.nextasean.wawebhook.documents.DocumentType
import com.nextasean.wawebhook.review.DocumentRequest
import com.nextasean.wawebhook.review.DecisionReportService
import com.nextasean.wawebhook.review.ReviewAuditService
import com.nextasean.wawebhook.review.ReviewEvent
import com.nextasean.wawebhook.review.ReviewEventKind
import com.nextasean.wawebhook.risk.RiskAssessment
import com.nextasean.wawebhook.risk.RiskAssessmentService
import com.nextasean.wawebhook.risk.RiskGrade
import com.nextasean.wawebhook.trace.AgentStep
import com.nextasean.wawebhook.trace.AgentTraceService
import com.nextasean.wawebhook.trace.TraceKind
import com.nextasean.wawebhook.trace.TraceStatus
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Back-office API (protected by [AdminKeyFilter]) that drives the application lifecycle and triggers the corresponding
 * WhatsApp notifications to the dealer and (Step 4) the customer. The review page (`/review`) is built on it.
 */
@RestController
@RequestMapping("/admin")
class AdminApplicationController(
    private val applications: ApplicationRepository,
    private val dealers: DealerRepository,
    private val service: ApplicationService,
    private val notifier: DealerNotifier,
    private val customerNotifier: CustomerNotifier,
    private val customerFlow: CustomerConversationService,
    private val documents: DocumentRepository,
    private val risk: RiskAssessmentService,
    private val audit: ReviewAuditService,
    private val trace: AgentTraceService,
    private val stages: StageProgressService,
    private val copilot: ReviewCopilotService,
    private val businessPhone: BusinessPhoneHolder,
    private val reports: DecisionReportService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    /** Back-office creation: no dealer phone needed. With a customer phone the customer is invited over WhatsApp. */
    data class CreateRequest(val dealerCode: String? = null, val customerPhone: String? = null, val customerName: String? = null, val invite: Boolean = true, val reviewer: String? = null)

    data class CreatedView(
        val application: ApplicationView,
        /** One-time dealer form link, in case the dealer form is part of the demo. */
        val formLink: String,
        val formLinkExpiresAt: Instant,
        /** wa.me link that pre-fills "START <number>"; null until the business number is known. */
        val startLink: String?,
        val startText: String,
        val customerInvited: Boolean,
        /** Null when no invitation message was requested; false when WhatsApp rejected it (e.g. outside the 24 h window). */
        val inviteSent: Boolean?,
    )

    @PostMapping("/applications")
    fun create(@RequestBody(required = false) body: CreateRequest?): ResponseEntity<Any> {
        val req = body ?: CreateRequest()
        val code = req.dealerCode?.trim()?.takeIf { it.isNotBlank() }
        val dealer = if (code != null) {
            dealers.findByDealerCode(code) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "Unknown dealer $code"))
        } else {
            dealers.findAll().filter { it.active }.minByOrNull { it.dealerCode }
                ?: return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "No active dealer configured; set DEMO_DEALER_1_PHONE in wa.env"))
        }
        val rawPhone = req.customerPhone?.trim().orEmpty()
        val phone = normalizePhone(rawPhone).takeIf { it.isNotBlank() }
        if (rawPhone.isNotBlank() && (phone == null || phone.length < 8 || phone.length > 15)) {
            return ResponseEntity.badRequest().body(mapOf("error" to "customerPhone must be 8-15 digits including the country code, e.g. 628123456789"))
        }
        val issued = service.create(dealer)
        var app = issued.application
        val actor = req.reviewer?.trim()?.takeIf { it.isNotBlank() } ?: "back office"
        audit.record(app, ReviewEventKind.CREATED, actor, "Dealer ${dealer.dealerCode}" + (phone?.let { ", customer ***${it.takeLast(4)}" } ?: ", no customer phone yet"))
        var inviteSent: Boolean? = null
        if (phone != null) {
            val result = customerFlow.invite(app, phone, req.customerName, sendMessage = req.invite)
            app = result.application
            inviteSent = result.sent
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(
            CreatedView(detail(app), issued.url, issued.expiresAt, businessPhone.startLink(app.applicationNumber), "START ${app.applicationNumber}", phone != null, inviteSent),
        )
    }

    data class ResubmissionRequest(val reason: String, val reviewer: String? = null)

    data class DecisionRequest(val decision: Decision, val note: String? = null, val reviewer: String? = null)

    data class DocumentRequestItem(val type: DocumentType, val reason: String)

    /** Officer asks the customer for documents again: which ones, why (customer-facing), and an optional note. */
    data class DocumentsRequest(val items: List<DocumentRequestItem>, val note: String? = null, val reviewer: String? = null)

    data class NoteRequest(val note: String, val reviewer: String? = null)

    /** A reviewer's question to the copilot, with the earlier turns of this conversation (oldest first). */
    data class CopilotRequest(val question: String = "", val history: List<ReviewCopilotService.Turn> = emptyList(), val reviewer: String? = null)

    data class DealerView(val code: String, val name: String, val phone: String, val active: Boolean, val country: String, val tier: String)

    data class ApplicationView(
        val applicationNumber: String,
        val status: ApplicationStatus,
        val statusLabel: String,
        val dealerCode: String?,
        val dealerName: String?,
        val dealerPhone: String?,
        val dealerTier: String?,
        val resubmissionReason: String?,
        val decisionNote: String?,
        val decidedBy: String?,
        val decidedAt: Instant?,
        val linkOutstanding: Boolean,
        val customerPhone: String?,
        val customerName: String?,
        val extracted: Map<String, String?>?,
        val idVerification: Map<String, Any?>?,
        val creditCheck: Map<String, Any?>?,
        val verifiedAt: Instant?,
        val review: ReviewView?,
        val country: String,
        /** Risk flags, AI risk grade and badges (Step 5); null until the documents have been checked. */
        val risk: RiskView?,
        /** Accepted, non-superseded documents on the list view; the full history on the detail view. */
        val documentCount: Int,
        val documents: List<DocumentView>? = null,
        val events: List<EventView>? = null,
        /** Step 4b: what the pipeline and the review agent did, in order (the "AI agent timeline"). */
        val agentTrace: List<StepView>? = null,
        /** Where the application is in the onboarding pipeline (stepper on the review page, mini bar in the queue). */
        val progress: StageProgress? = null,
        /** Decision evidence report (PDF): whether one is stored for this application and where to fetch it. */
        val decisionReport: DecisionReportService.Info? = null,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    data class ReviewView(val decision: String?, val officerReviewRequired: Boolean?, val rounds: Int, val reviewedAt: Instant?, val summary: String?)

    data class DocumentView(
        val id: Long,
        val type: String,
        val typeLabel: String,
        val detectedType: String?,
        val checkPassed: Boolean?,
        val checkReason: String?,
        val needsOfficerReview: Boolean?,
        val superseded: Boolean,
        val mimeType: String?,
        val caption: String?,
        val fileAvailable: Boolean,
        /** Relative URL of the image, served with the admin key (`X-Admin-Key`). */
        val fileUrl: String,
        val createdAt: Instant,
        /** Full analysis as stored (fields with confidence and status, quality issues, NIK validation). */
        val analysis: String?,
    )

    data class EventView(val kind: String, val label: String, val labelKo: String, val actor: String?, val detail: String?, val createdAt: Instant) {
        companion object {
            fun of(e: ReviewEvent) = EventView(e.kind.name, e.kind.label, e.kind.labelKo, e.actor, e.detail, e.createdAt)
        }
    }

    data class StepView(
        val id: Long,
        val kind: String,
        val phase: String,
        val phaseKo: String,
        val label: String,
        val labelKo: String,
        val status: String,
        val actor: String,
        val title: String,
        val detail: String?,
        val data: String?,
        val durationMs: Long?,
        val round: Int,
        val createdAt: Instant,
    ) {
        companion object {
            fun of(s: AgentStep) = StepView(
                s.id!!, s.kind.name, s.kind.phase.name, s.kind.phase.labelKo, s.kind.label, s.kind.labelKo, s.status.name, s.actor, s.title, s.detail,
                s.dataJson, s.durationMs, s.round, s.createdAt,
            )
        }
    }

    /** Lightweight payload for live polling while the customer is still sending documents. */
    data class TraceView(val applicationNumber: String, val status: ApplicationStatus, val updatedAt: Instant, val live: Boolean, val steps: List<StepView>, val progress: StageProgress)

    data class RiskView(
        val grade: String,
        val gradeLabel: String,
        val gradeLabelKo: String,
        val score: Int,
        val highRisk: Boolean,
        val method: String,
        val methodLabel: String,
        val fraudWarnings: Int,
        val flags: List<Map<String, Any?>>,
        val badges: List<Map<String, String>>,
        val assessedAt: Instant,
    ) {
        companion object {
            fun of(a: RiskAssessment) = RiskView(
                grade = a.grade.name, gradeLabel = a.grade.label, gradeLabelKo = a.grade.labelKo, score = a.score, highRisk = a.grade.highRisk,
                method = a.method.name, methodLabel = a.method.label, fraudWarnings = a.fraudFlags.size,
                flags = a.flags.map {
                    mapOf("code" to it.code, "severity" to it.severity.name, "severityKo" to it.severity.labelKo, "source" to it.source.name,
                        "sourceKo" to it.source.labelKo, "fraud" to it.fraudSignal, "title" to it.title, "titleKo" to it.titleKo, "detail" to it.detail)
                },
                badges = a.badges.map { mapOf("code" to it.name, "label" to it.label, "labelKo" to it.labelKo, "description" to it.description) },
                assessedAt = a.assessedAt,
            )
        }
    }

    data class ActionResult(val application: ApplicationView, val dealerNotified: Boolean, val customerNotified: Boolean? = null)

    @GetMapping("/dealers")
    fun dealers(): List<DealerView> = dealers.findAll().map { DealerView(it.dealerCode, it.name, it.phone, it.active, it.country, it.tier.name) }

    /**
     * All applications, newest first. Optional filters: `status` (comma-separated), `country`, `grade` (A-D, comma-separated),
     * `q` (application number, customer name or dealer code, case-insensitive) and `queue=true` (open applications only,
     * ready-for-review first, then by risk score and age).
     */
    @GetMapping("/applications")
    fun applications(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) country: String?,
        @RequestParam(required = false) grade: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false, defaultValue = "false") queue: Boolean,
    ): List<ApplicationView> {
        val byId = dealers.findAll().associateBy { it.id }
        val statuses = status?.split(',')?.mapNotNull { s -> ApplicationStatus.entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) } }?.toSet().orEmpty()
        val grades = grade?.split(',')?.mapNotNull { RiskGrade.parse(it.trim().uppercase()) }?.toSet().orEmpty()
        val needle = q?.trim()?.lowercase()?.ifBlank { null }
        val docCounts = documents.findAll().filter { !it.superseded && it.checkPassed == true }.groupingBy { it.applicationId }.eachCount()
        var rows = applications.findAllByOrderByCreatedAtDesc().asSequence()
            .filter { statuses.isEmpty() || it.status in statuses }
            .filter { country.isNullOrBlank() || it.country.equals(country.trim(), ignoreCase = true) }
            .filter { grades.isEmpty() || RiskGrade.parse(it.riskGrade) in grades }
            .filter { app ->
                needle == null || app.applicationNumber.lowercase().contains(needle) ||
                    (app.extractedName ?: app.customerName)?.lowercase()?.contains(needle) == true ||
                    byId[app.dealerId]?.dealerCode?.lowercase()?.contains(needle) == true ||
                    byId[app.dealerId]?.name?.lowercase()?.contains(needle) == true
            }
            .toList()
        if (queue) {
            rows = rows.filter { !it.status.isFinal }.sortedWith(
                compareBy<DealerApplication> { queueRank(it.status) }.thenByDescending { it.riskScore ?: -1 }.thenBy { it.createdAt },
            )
        }
        val progress = stages.forAll(rows)
        return rows.map { it.toView(byId[it.dealerId], docCounts[it.id] ?: 0).copy(progress = progress[it.id]) }
    }

    @GetMapping("/applications/{number}")
    fun application(@PathVariable number: String): ResponseEntity<Any> {
        val app = service.findByNumber(number) ?: return notFound(number)
        return ResponseEntity.ok(detail(app))
    }

    /** Step 4b: the agent timeline alone, polled by the review page every few seconds while the application is active. */
    @GetMapping("/applications/{number}/agent-trace")
    fun agentTrace(@PathVariable number: String): ResponseEntity<Any> {
        val app = service.findByNumber(number) ?: return notFound(number)
        return ResponseEntity.ok(TraceView(app.applicationNumber, app.status, app.updatedAt, live = !app.status.isFinal, steps = trace.steps(app.id).map(StepView::of), progress = stages.forApplication(app)))
    }

    /** The stored image of one document, for the review page. Only reachable with the admin key. */
    @GetMapping("/applications/{number}/documents/{documentId}/file")
    fun documentFile(@PathVariable number: String, @PathVariable documentId: Long): ResponseEntity<Any> {
        val app = service.findByNumber(number) ?: return notFound(number)
        val doc = documents.findById(documentId).orElse(null)?.takeIf { it.applicationId == app.id }
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "document $documentId not found on ${app.applicationNumber}"))
        val path = Path.of(doc.filePath)
        if (!Files.isRegularFile(path)) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "file for document $documentId is no longer on disk"))
        val mediaType = doc.mimeType?.substringBefore(';')?.trim()?.let { runCatching { MediaType.parseMediaType(it) }.getOrNull() } ?: MediaType.APPLICATION_OCTET_STREAM
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(mediaType).body(Files.readAllBytes(path))
    }

    /** Flags the application, issues a fresh one-time link and sends the resubmission request to the dealer. */
    @PostMapping("/applications/{number}/request-resubmission")
    fun requestResubmission(@PathVariable number: String, @RequestBody body: ResubmissionRequest): ResponseEntity<Any> {
        val app = service.findByNumber(number) ?: return notFound(number)
        if (body.reason.isBlank()) return ResponseEntity.badRequest().body(mapOf("error" to "reason is required"))
        val link = service.requestResubmission(app, body.reason) ?: return conflict(app)
        audit.record(link.application, ReviewEventKind.RESUBMISSION_REQUESTED, body.reviewer, body.reason)
        val notified = notifier.resubmissionRequested(link)
        return ResponseEntity.ok(ActionResult(detail(link.application), notified))
    }

    /** Step 4: sends the customer back for one or more documents (their own WhatsApp), and tells the dealer. */
    @PostMapping("/applications/{number}/request-documents")
    fun requestDocuments(@PathVariable number: String, @RequestBody body: DocumentsRequest): ResponseEntity<Any> {
        val app = service.findByNumber(number) ?: return notFound(number)
        val items = body.items.filter { it.reason.isNotBlank() }.map { DocumentRequest(it.type, it.reason.trim().take(200)) }.distinctBy { it.type }
        if (items.isEmpty()) return ResponseEntity.badRequest().body(mapOf("error" to "items with a type (KTP, SELFIE_KTP) and a reason are required"))
        return when (val result = customerFlow.requestDocuments(app, items, body.note?.trim()?.take(300), body.reviewer)) {
            is OfficerRequestResult.NotReviewable -> conflict(result.application)
            is OfficerRequestResult.NoCustomerPhone -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("error" to "application ${app.applicationNumber} has no customer phone yet; ask the dealer to forward the START link"))
            is OfficerRequestResult.Sent -> {
                val dealerNotified = notifier.documentsRequestedFromCustomer(result.application, items.map { "${it.type.label}: ${it.reason}" })
                ResponseEntity.ok(ActionResult(detail(result.application), dealerNotified, result.customerNotified))
            }
        }
    }

    /** Records APPROVED / REJECTED and sends the decision notification to the dealer and the customer. */
    @PostMapping("/applications/{number}/decision")
    fun decide(@PathVariable number: String, @RequestBody body: DecisionRequest): ResponseEntity<Any> {
        val app = service.findByNumber(number) ?: return notFound(number)
        val decided = service.decide(app, body.decision, body.note, body.reviewer) ?: return conflict(app)
        customerFlow.closeSessions(decided)
        audit.record(decided, if (body.decision == Decision.APPROVED) ReviewEventKind.APPROVED else ReviewEventKind.REJECTED, body.reviewer, decided.decisionNote)
        trace.record(
            decided.id, TraceKind.OFFICER_DECISION, if (body.decision == Decision.APPROVED) TraceStatus.OK else TraceStatus.FAIL, AgentTraceService.officer(body.reviewer),
            "Officer ${if (body.decision == Decision.APPROVED) "approved" else "rejected"} the application", decided.decisionNote, round = decided.reviewRounds,
        )
        // The evidence report is written the moment the decision is recorded, from the data as it stands now.
        reports.generateAndStore(decided)?.let { audit.record(decided, ReviewEventKind.REPORT_GENERATED, ReviewAuditService.SYSTEM, it.fileName) }
        val dealerNotified = notifier.decision(decided)
        val customerNotified = customerNotifier.decision(decided)
        return ResponseEntity.ok(ActionResult(detail(decided), dealerNotified, customerNotified))
    }

    /** The decision evidence report (PDF): the stored one for a decided application, a draft rendered now otherwise. */
    @GetMapping("/applications/{number}/decision-report.pdf")
    fun decisionReport(@PathVariable number: String): ResponseEntity<Any> {
        val app = service.findByNumber(number) ?: return notFound(number)
        val stored = reports.stored(app)?.takeIf { app.status.isFinal }
        val bytes = stored?.let { runCatching { Files.readAllBytes(it.path) }.getOrNull() } ?: reports.render(app, draft = !app.status.isFinal)
        val name = stored?.fileName ?: "decision-report-${app.applicationNumber}${if (app.status.isFinal) "" else "-draft"}.pdf"
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"$name\"").body(bytes)
    }

    /** Adds an officer note to the timeline without changing the application. */
    @PostMapping("/applications/{number}/note")
    fun note(@PathVariable number: String, @RequestBody body: NoteRequest): ResponseEntity<Any> {
        val app = service.findByNumber(number) ?: return notFound(number)
        if (body.note.isBlank()) return ResponseEntity.badRequest().body(mapOf("error" to "note is required"))
        audit.record(app, ReviewEventKind.NOTE_ADDED, body.reviewer, body.note)
        return ResponseEntity.ok(detail(app))
    }

    /**
     * The reviewer's copilot: a question about this application answered from its case file (Claude when configured,
     * rule-based explanation of the stored assessment otherwise). Nothing is written; the conversation lives in the browser.
     */
    @PostMapping("/applications/{number}/copilot")
    fun copilot(@PathVariable number: String, @RequestBody body: CopilotRequest): ResponseEntity<Any> {
        val app = service.findByNumber(number) ?: return notFound(number)
        val question = body.question.trim()
        if (question.isEmpty()) return ResponseEntity.badRequest().body(mapOf("error" to "question is required"))
        if (question.length > MAX_QUESTION) return ResponseEntity.badRequest().body(mapOf("error" to "question is too long (max $MAX_QUESTION characters)"))
        return try {
            ResponseEntity.ok(copilot.ask(app, question, body.history, body.reviewer))
        } catch (e: AnthropicException) {
            log.warn("Review copilot failed | application={} | {}", number, e.toString())
            ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(mapOf("error" to "Claude API error: ${e.message}"))
        }
    }

    /** Re-runs the risk rules on one application (e.g. after the officer corrected data). */
    @PostMapping("/applications/{number}/assess-risk")
    fun assessRisk(@PathVariable number: String, @RequestParam(required = false) reviewer: String?): ResponseEntity<Any> {
        val app = service.findByNumber(number) ?: return notFound(number)
        if (app.riskAssessedAt == null && app.verifiedAt == null && documents.findByApplicationIdOrderByCreatedAtAsc(app.id!!).isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "application ${app.applicationNumber} has no documents or checks to assess yet"))
        }
        val (saved, assessment) = risk.assessAndStore(app)
        audit.record(saved, ReviewEventKind.RISK_REASSESSED, reviewer, assessment.headline)
        return ResponseEntity.ok(detail(saved))
    }

    /** Re-runs the risk rules on every assessed application, after a rule change. */
    @PostMapping("/risk/reassess-all")
    fun reassessAll(): Map<String, Int> = mapOf("reassessed" to risk.reassessAll())

    private fun detail(app: DealerApplication): ApplicationView {
        val docs = documents.findByApplicationIdOrderByCreatedAtAsc(app.id!!)
        return app.toView(dealers.findById(app.dealerId).orElse(null), docs.count { !it.superseded && it.checkPassed == true })
            .copy(documents = docs.map { it.toView(app) }, events = audit.timeline(app).map(EventView::of), agentTrace = trace.steps(app.id).map(StepView::of), progress = stages.forApplication(app), decisionReport = reports.info(app))
    }

    private fun queueRank(status: ApplicationStatus) = when (status) {
        ApplicationStatus.READY_FOR_REVIEW -> 0
        ApplicationStatus.SUBMITTED -> 1
        ApplicationStatus.DOCUMENTS_REQUESTED, ApplicationStatus.RESUBMISSION_REQUESTED -> 2
        ApplicationStatus.DRAFT -> 3
        ApplicationStatus.APPROVED, ApplicationStatus.REJECTED -> 4
    }

    private fun notFound(number: String): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "application $number not found"))

    private fun conflict(app: DealerApplication): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(mapOf("error" to "application ${app.applicationNumber} is already ${app.status} and cannot be changed"))

    private fun DocumentRecord.toView(app: DealerApplication) = DocumentView(
        id = id!!,
        type = type.name,
        typeLabel = type.label,
        detectedType = detectedType,
        checkPassed = checkPassed,
        checkReason = checkReason,
        needsOfficerReview = needsOfficerReview,
        superseded = superseded,
        mimeType = mimeType,
        caption = caption,
        fileAvailable = runCatching { Files.isRegularFile(Path.of(filePath)) }.getOrDefault(false),
        fileUrl = "/admin/applications/${app.applicationNumber}/documents/$id/file",
        createdAt = createdAt,
        analysis = analysisJson,
    )

    private fun DealerApplication.toView(dealer: Dealer?, documentCount: Int) = ApplicationView(
        applicationNumber = applicationNumber,
        status = status,
        statusLabel = status.label,
        dealerCode = dealer?.dealerCode,
        dealerName = dealer?.name,
        dealerPhone = dealer?.phone,
        dealerTier = dealer?.tier?.name,
        resubmissionReason = resubmissionReason,
        decisionNote = decisionNote,
        decidedBy = decidedBy,
        decidedAt = decidedAt,
        linkOutstanding = linkTokenHash != null,
        customerPhone = customerPhone,
        customerName = customerName,
        extracted = extractedNik?.let { mapOf("name" to extractedName, "nik" to it, "dob" to extractedDob, "address" to extractedAddress) },
        idVerification = idVerificationStatus?.let { mapOf("status" to it, "label" to it.label, "detail" to idVerificationDetail) },
        creditCheck = creditCheckStatus?.let { mapOf("status" to it, "label" to it.label, "detail" to creditCheckDetail, "score" to creditScore) },
        verifiedAt = verifiedAt,
        review = reviewDecision?.let { ReviewView(it, officerReviewRequired, reviewRounds, reviewedAt, reviewSummary) },
        country = country,
        risk = risk.stored(this)?.let(RiskView::of),
        documentCount = documentCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
    private companion object {
        const val MAX_QUESTION = 2_000
    }
}
