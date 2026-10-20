package com.nextasean.wawebhook.application

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import com.nextasean.wawebhook.verification.ProviderOutcome
import java.time.Instant

enum class ApplicationStatus(val label: String) {
    DRAFT("Draft - form not submitted yet"),
    SUBMITTED("Submitted - under review"),
    RESUBMISSION_REQUESTED("Resubmission requested"),
    READY_FOR_REVIEW("Documents submitted - ready for review"),
    /** Step 4: the reviewing officer asked the customer to send one or more documents again. */
    DOCUMENTS_REQUESTED("Documents requested from customer"),
    APPROVED("Approved"),
    REJECTED("Rejected");

    val isFinal: Boolean get() = this == APPROVED || this == REJECTED
    /** The dealer form (one-time link) can still be submitted. */
    val acceptsSubmission: Boolean get() = this == DRAFT || this == RESUBMISSION_REQUESTED
    /** The customer may START / resume the WhatsApp document journey. */
    val acceptsCustomerDocuments: Boolean get() = acceptsSubmission || this == DOCUMENTS_REQUESTED
    /** An officer can approve, reject or send the application back. */
    val reviewable: Boolean get() = !isFinal
}

@Table("APPLICATIONS")
data class DealerApplication(
    @Id val id: Long? = null,
    val applicationNumber: String,
    val dealerId: Long,
    val status: ApplicationStatus = ApplicationStatus.DRAFT,
    /** SHA-256 of the current one-time link token; null once used up or never issued. */
    val linkTokenHash: String? = null,
    val linkExpiresAt: Instant? = null,
    val linkOpenedAt: Instant? = null,
    val resubmissionReason: String? = null,
    val decisionNote: String? = null,
    /** Customer linked through "START <number>" (WhatsApp ID, digits only). */
    val customerPhone: String? = null,
    val customerName: String? = null,
    // Fields "extracted" from the KTP by the document check.
    val extractedName: String? = null,
    val extractedNik: String? = null,
    val extractedDob: String? = null,
    val extractedAddress: String? = null,
    // Verification provider results.
    val idVerificationStatus: ProviderOutcome? = null,
    val idVerificationDetail: String? = null,
    val creditCheckStatus: ProviderOutcome? = null,
    val creditCheckDetail: String? = null,
    val creditScore: Int? = null,
    val verifiedAt: Instant? = null,
    // Review agent step (after all documents are collected).
    /** REQUEST_DOCUMENTS or SUMMARY_WRITTEN, from the latest agent decision. */
    val reviewDecision: String? = null,
    /** One-page summary for the back office, written when the documents are as good as they will get. */
    val reviewSummary: String? = null,
    val officerReviewRequired: Boolean? = null,
    /** How many times the customer was sent back for documents. */
    val reviewRounds: Int = 0,
    val reviewedAt: Instant? = null,
    // Step 5: market, risk flags / AI risk grade and customer badges (see risk/RiskAssessmentService).
    /** Market code copied from the dealer at creation (ID / MM / KH) so statistics stay stable if the dealer moves. */
    val country: String = "ID",
    /** A (low) .. D (critical); null until the documents have been checked. */
    val riskGrade: String? = null,
    val riskScore: Int? = null,
    /** Serialised list of [com.nextasean.wawebhook.risk.RiskFlag]. */
    val riskFlagsJson: String? = null,
    /** How the grade was produced: RULES (stub mode) or CLAUDE_VISION_RULES (Claude analysis + rules). */
    val riskMethod: String? = null,
    /** Comma-separated [com.nextasean.wawebhook.risk.CustomerBadge] names. */
    val customerBadges: String? = null,
    val riskAssessedAt: Instant? = null,
    // Step 4: back-office decision metadata.
    /** Name the officer typed in the review UI (or API) when deciding; null for decisions made before Step 4. */
    val decidedBy: String? = null,
    val decidedAt: Instant? = null,
    /** True for rows created by the demo seeder. */
    val demoSeed: Boolean = false,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

interface ApplicationRepository : CrudRepository<DealerApplication, Long> {
    fun findByApplicationNumber(applicationNumber: String): DealerApplication?
    fun findByLinkTokenHash(linkTokenHash: String): DealerApplication?
    fun findByDealerIdOrderByCreatedAtDesc(dealerId: Long): List<DealerApplication>
    fun findAllByOrderByCreatedAtDesc(): List<DealerApplication>
    fun findByCountryOrderByCreatedAtDesc(country: String): List<DealerApplication>
    fun findByStatusOrderByCreatedAtDesc(status: ApplicationStatus): List<DealerApplication>
    fun findByDemoSeedTrue(): List<DealerApplication>
}
