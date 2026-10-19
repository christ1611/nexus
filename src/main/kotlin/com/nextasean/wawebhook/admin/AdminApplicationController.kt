package com.nextasean.wawebhook.admin

import com.nextasean.wawebhook.application.ApplicationRepository
import com.nextasean.wawebhook.application.ApplicationService
import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.application.Decision
import com.nextasean.wawebhook.conversation.DealerNotifier
import com.nextasean.wawebhook.dealer.Dealer
import com.nextasean.wawebhook.dealer.DealerRepository
import com.nextasean.wawebhook.documents.DocumentRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Back-office API (protected by [AdminKeyFilter]) that drives the application lifecycle
 * and triggers the corresponding WhatsApp notifications to the dealer.
 */
@RestController
@RequestMapping("/admin")
class AdminApplicationController(
    private val applications: ApplicationRepository,
    private val dealers: DealerRepository,
    private val service: ApplicationService,
    private val notifier: DealerNotifier,
    private val documents: DocumentRepository,
) {

    data class ResubmissionRequest(val reason: String)

    data class DecisionRequest(val decision: Decision, val note: String? = null)

    data class DealerView(val code: String, val name: String, val phone: String, val active: Boolean)

    data class ApplicationView(
        val applicationNumber: String,
        val status: ApplicationStatus,
        val dealerCode: String?,
        val dealerName: String?,
        val dealerPhone: String?,
        val resubmissionReason: String?,
        val decisionNote: String?,
        val linkOutstanding: Boolean,
        val customerPhone: String?,
        val customerName: String?,
        val extracted: Map<String, String?>?,
        val idVerification: Map<String, Any?>?,
        val creditCheck: Map<String, Any?>?,
        val verifiedAt: Instant?,
        val review: ReviewView?,
        val documents: List<DocumentView>? = null,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    data class ReviewView(val decision: String?, val officerReviewRequired: Boolean?, val rounds: Int, val reviewedAt: Instant?, val summary: String?)

    data class DocumentView(
        val type: String,
        val detectedType: String?,
        val checkPassed: Boolean?,
        val checkReason: String?,
        val needsOfficerReview: Boolean?,
        val superseded: Boolean,
        val createdAt: Instant,
        /** Full analysis as stored (fields with confidence and status, quality issues, NIK validation). */
        val analysis: String?,
    )

    data class ActionResult(val application: ApplicationView, val dealerNotified: Boolean)

    @GetMapping("/dealers")
    fun dealers(): List<DealerView> = dealers.findAll().map { DealerView(it.dealerCode, it.name, it.phone, it.active) }

    @GetMapping("/applications")
    fun applications(): List<ApplicationView> {
        val byId = dealers.findAll().associateBy { it.id }
        return applications.findAllByOrderByCreatedAtDesc().map { it.toView(byId[it.dealerId]) }
    }

    @GetMapping("/applications/{number}")
    fun application(@PathVariable number: String): ResponseEntity<Any> {
        val app = service.findByNumber(number) ?: return notFound(number)
        val docs = documents.findByApplicationIdOrderByCreatedAtAsc(app.id!!).map {
            DocumentView(it.type.name, it.detectedType, it.checkPassed, it.checkReason, it.needsOfficerReview, it.superseded, it.createdAt, it.analysisJson)
        }
        return ResponseEntity.ok(app.toView(dealers.findById(app.dealerId).orElse(null)).copy(documents = docs))
    }

    /** Flags the application, issues a fresh one-time link and sends the resubmission request to the dealer. */
    @PostMapping("/applications/{number}/request-resubmission")
    fun requestResubmission(@PathVariable number: String, @RequestBody body: ResubmissionRequest): ResponseEntity<Any> {
        val app = service.findByNumber(number) ?: return notFound(number)
        if (body.reason.isBlank()) return ResponseEntity.badRequest().body(mapOf("error" to "reason is required"))
        val link = service.requestResubmission(app, body.reason) ?: return conflict(app)
        val notified = notifier.resubmissionRequested(link)
        return ResponseEntity.ok(ActionResult(link.application.toView(dealers.findById(app.dealerId).orElse(null)), notified))
    }

    /** Records APPROVED / REJECTED and sends the decision notification to the dealer. */
    @PostMapping("/applications/{number}/decision")
    fun decide(@PathVariable number: String, @RequestBody body: DecisionRequest): ResponseEntity<Any> {
        val app = service.findByNumber(number) ?: return notFound(number)
        val decided = service.decide(app, body.decision, body.note) ?: return conflict(app)
        val notified = notifier.decision(decided)
        return ResponseEntity.ok(ActionResult(decided.toView(dealers.findById(app.dealerId).orElse(null)), notified))
    }

    private fun notFound(number: String): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "application $number not found"))

    private fun conflict(app: DealerApplication): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(mapOf("error" to "application ${app.applicationNumber} is already ${app.status} and cannot be changed"))

    private fun DealerApplication.toView(dealer: Dealer?) = ApplicationView(
        applicationNumber = applicationNumber,
        status = status,
        dealerCode = dealer?.dealerCode,
        dealerName = dealer?.name,
        dealerPhone = dealer?.phone,
        resubmissionReason = resubmissionReason,
        decisionNote = decisionNote,
        linkOutstanding = linkTokenHash != null,
        customerPhone = customerPhone,
        customerName = customerName,
        extracted = extractedNik?.let { mapOf("name" to extractedName, "nik" to it, "dob" to extractedDob, "address" to extractedAddress) },
        idVerification = idVerificationStatus?.let { mapOf("status" to it, "detail" to idVerificationDetail) },
        creditCheck = creditCheckStatus?.let { mapOf("status" to it, "detail" to creditCheckDetail, "score" to creditScore) },
        verifiedAt = verifiedAt,
        review = reviewDecision?.let { ReviewView(it, officerReviewRequired, reviewRounds, reviewedAt, reviewSummary) },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
