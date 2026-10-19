package com.nextasean.wawebhook.review

import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.config.ClaudeProperties
import com.nextasean.wawebhook.documents.DocumentRecord
import com.nextasean.wawebhook.documents.DocumentRepository
import com.nextasean.wawebhook.documents.DocumentType
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysis
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysisCodec
import com.nextasean.wawebhook.documents.analysis.FieldStatus
import org.springframework.stereotype.Component

/** One document the customer must send (again), with a short customer-facing reason. */
data class DocumentRequest(val type: DocumentType, val reason: String)

sealed interface ReviewDecision {
    /** Send the customer back to the listed document steps. */
    data class RequestDocuments(val items: List<DocumentRequest>, val note: String? = null) : ReviewDecision

    /** Documents are as good as they will get; the one-page summary goes to the back office. */
    data class SummaryWritten(val summary: String, val officerReviewRequired: Boolean) : ReviewDecision
}

/** Latest non-superseded record for a required document, with its analysis when a vision model produced one. */
data class ReviewedDocument(val type: DocumentType, val record: DocumentRecord?, val analysis: DocumentAnalysis?) {
    val received: Boolean get() = record != null && record.checkPassed == true
}

data class ReviewContext(
    val application: DealerApplication,
    val documents: List<ReviewedDocument>,
    /** Number of times the customer has already been sent back for documents. */
    val round: Int,
    val maxRounds: Int,
) {
    val missing: List<ReviewedDocument> get() = documents.filter { !it.received }
    val canRequestMore: Boolean get() = round < maxRounds
}

/** The step after all documents are in: ask for better documents, or write the summary for the officer. */
interface ReviewAgent {
    fun decide(context: ReviewContext): ReviewDecision
}

/** Assembles the [ReviewContext] from the stored documents and answers "which document is still missing". */
@Component
class ReviewContextBuilder(
    private val documents: DocumentRepository,
    private val codec: DocumentAnalysisCodec,
    private val props: ClaudeProperties,
) {
    fun build(app: DealerApplication): ReviewContext {
        val latest = latestByType(app.id!!)
        return ReviewContext(
            application = app,
            documents = DocumentType.steps.map { type -> ReviewedDocument(type, latest[type], latest[type]?.let { codec.fromJson(it.analysisJson) }) },
            round = app.reviewRounds,
            maxRounds = props.maxReviewRounds,
        )
    }

    /** First document step without an accepted, non-superseded record; null when the set is complete. */
    fun nextMissing(applicationId: Long): DocumentType? {
        val latest = latestByType(applicationId)
        return DocumentType.steps.firstOrNull { latest[it]?.checkPassed != true }
    }

    private fun latestByType(applicationId: Long): Map<DocumentType, DocumentRecord> =
        documents.findByApplicationIdOrderByCreatedAtAsc(applicationId)
            .filter { !it.superseded && it.checkPassed == true }
            .groupBy { it.type }
            .mapValues { (_, records) -> records.maxBy { it.id ?: 0L } }
}

/**
 * Deterministic decision used when no model is available, when the model fails, and as the floor under the model's
 * decision: a missing or unusable document is always requested while rounds remain.
 */
class RuleBasedReviewAgent : ReviewAgent {

    override fun decide(context: ReviewContext): ReviewDecision {
        val requests = requestsFor(context)
        if (requests.isNotEmpty() && context.canRequestMore) return ReviewDecision.RequestDocuments(requests)
        val officerReview = requests.isNotEmpty() || context.documents.any { it.analysis?.needsOfficerReview == true || it.record?.needsOfficerReview == true }
        return ReviewDecision.SummaryWritten(SummaryTemplate.render(context, officerReview), officerReview)
    }

    /** Documents a retake would plausibly fix: missing, unusable, or with a required field that was simply not legible. */
    fun requestsFor(context: ReviewContext): List<DocumentRequest> = context.documents.mapNotNull { doc ->
        val a = doc.analysis
        when {
            !doc.received -> DocumentRequest(doc.type, "we have not received an accepted photo yet")
            a != null && !a.usable -> DocumentRequest(doc.type, a.rejectionReason?.replaceFirstChar { it.lowercase() } ?: "the photo could not be read")
            doc.type == DocumentType.KTP && a != null -> {
                val illegible = DocumentAnalysis.KTP_REQUIRED_FIELDS.filter { name ->
                    val f = a.field(name)
                    f == null || f.status == FieldStatus.NOT_VISIBLE || (f.status == FieldStatus.NEEDS_OFFICER_REVIEW && a.nik?.let { n -> name == "nik" && !n.valid && n.formatValid } != true)
                }
                if (illegible.isEmpty()) null else DocumentRequest(doc.type, "${illegible.joinToString(", ") { it.replace('_', ' ') }} could not be read clearly; please retake in good light without glare")
            }
            else -> null
        }
    }
}
