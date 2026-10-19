package com.nextasean.wawebhook.documents

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.time.Instant

/** Documents collected from the customer, in the order they are requested. */
enum class DocumentType(val label: String, val request: String) {
    KTP("KTP", "a photo of your KTP"),
    SELFIE_KTP("Selfie with KTP", "a selfie of yourself holding your KTP next to your face");

    val step: Int get() = ordinal + 1

    companion object {
        val steps: List<DocumentType> = entries
        val total: Int get() = entries.size
    }
}

@Table("DOCUMENTS")
data class DocumentRecord(
    @Id val id: Long? = null,
    val applicationId: Long,
    val type: DocumentType,
    val mediaId: String,
    val filePath: String,
    val mimeType: String? = null,
    val caption: String? = null,
    val checkPassed: Boolean? = null,
    val checkReason: String? = null,
    /** What the vision model saw (null for the stub). */
    val detectedType: String? = null,
    val needsOfficerReview: Boolean? = null,
    /** Serialised [com.nextasean.wawebhook.documents.analysis.DocumentAnalysis]. */
    val analysisJson: String? = null,
    /** Set when the review agent asked the customer to send this document again. */
    val superseded: Boolean = false,
    val createdAt: Instant = Instant.now(),
)

interface DocumentRepository : CrudRepository<DocumentRecord, Long> {
    fun findByApplicationIdOrderByCreatedAtAsc(applicationId: Long): List<DocumentRecord>
}
