package com.nextasean.wawebhook.documents.analysis

import com.nextasean.wawebhook.documents.DocumentType

/** What the model saw in the image, independent of what was asked for. */
enum class DetectedDocumentType(val label: String) {
    KTP("KTP (Indonesian ID card)"),
    SELFIE_WITH_KTP("Selfie holding a KTP"),
    SELFIE_ONLY("Selfie without a KTP"),
    PASSPORT("Passport"),
    DRIVING_LICENCE("Driving licence (SIM)"),
    OTHER_DOCUMENT("Other document"),
    NOT_A_DOCUMENT("Not a document"),
    UNREADABLE("Unreadable image");

    /** The [DocumentType] this detection satisfies, if any. */
    fun satisfies(requested: DocumentType): Boolean = when (requested) {
        DocumentType.KTP -> this == KTP
        DocumentType.SELFIE_KTP -> this == SELFIE_WITH_KTP
    }
}

enum class QualityIssueType(val customerWording: String) {
    BLUR("the photo is blurry"),
    GLARE("there is glare or reflection on the card"),
    CROPPED("part of the document is cut off"),
    TOO_DARK("the photo is too dark"),
    OBSTRUCTED("something covers part of the document"),
    LOW_RESOLUTION("the photo resolution is too low to read"),
    SCREEN_PHOTO("the image looks like a photo of a screen or a photocopy"),
    OTHER("the photo could not be read properly"),
}

enum class Severity { LOW, MEDIUM, HIGH }

data class QualityIssue(val type: QualityIssueType, val severity: Severity, val detail: String)

/** Visible evidence that the document was edited or is not a genuine card. The model describes; the rules decide. */
enum class TamperSignalType(val label: String, val labelKo: String) {
    FONT_MISMATCH("Font or print style differs between fields", "필드 간 글꼴·인쇄 상태 불일치"),
    TEXT_MISALIGNED("Text is misaligned with the card layout", "텍스트가 카드 레이아웃과 어긋남"),
    DIGITAL_EDITING("Digital editing artefacts (cloning, blur patches, cut edges)", "디지털 편집 흔적"),
    PHOTO_REPLACED("ID photo looks pasted or replaced", "증명사진 교체·덧붙임 흔적"),
    LAYOUT_MISMATCH("Layout, colours or logos differ from a genuine card", "정품 카드와 레이아웃·색상·로고 다름"),
    SECURITY_FEATURE_MISSING("Expected security features are missing or fake", "보안 요소 누락·위조"),
    FIELD_INCONSISTENT("Fields contradict each other on the card", "카드 내 항목 간 모순"),
    OTHER("Other forgery indication", "기타 위조 의심 요소"),
}

data class TamperSignal(val type: TamperSignalType, val severity: Severity, val detail: String)

enum class FieldStatus {
    /** Clearly legible with confidence at or above the threshold; safe to use downstream. */
    EXTRACTED,
    /** Present but not confidently legible, or failed validation. The value is a candidate for a human, never used automatically. */
    NEEDS_OFFICER_REVIEW,
    /** The field is not visible in the image at all. */
    NOT_VISIBLE,
}

data class ExtractedField(
    val name: String,
    /** Candidate value as read; null when not visible. Only trust it when [status] is [FieldStatus.EXTRACTED]. */
    val value: String?,
    val confidence: Double,
    val status: FieldStatus,
    val note: String? = null,
) {
    val usable: Boolean get() = status == FieldStatus.EXTRACTED && !value.isNullOrBlank()
}

/** Token usage and timing of the model call that produced an analysis (null for results built without a call). */
data class ModelUsage(
    val inputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val outputTokens: Long? = null,
    val durationMs: Long? = null,
    val stopReason: String? = null,
)

/** Result of analysing one image. Serialised as JSON onto the DOCUMENTS row and fed to the review agent. */
data class DocumentAnalysis(
    val requestedType: DocumentType,
    val documentType: DetectedDocumentType,
    val documentTypeConfidence: Double,
    val typeMatchesRequest: Boolean,
    val qualityIssues: List<QualityIssue>,
    /** True when the image is the requested document and has no HIGH severity quality issue. */
    val usable: Boolean,
    /** Forgery / editing evidence the model saw (empty when the card looks genuine). Never makes the image unusable: a suspect card goes to an officer, not back to the customer. */
    val tamperSignals: List<TamperSignal> = emptyList(),
    val fields: List<ExtractedField>,
    val nik: NikValidation?,
    /** True when a human must look at this document before the application can move on automatically. */
    val needsOfficerReview: Boolean,
    val reviewReasons: List<String>,
    val modelNotes: String?,
    val model: String,
    /** Exactly what the model returned, for audit. */
    val rawJson: String,
    /** Tokens, latency and stop reason of the call (Step 4b agent trace). */
    val usage: ModelUsage? = null,
) {
    fun field(name: String): ExtractedField? = fields.firstOrNull { it.name == name }

    /** The value of [name] only when it was confidently extracted and (for the NIK) validated. */
    fun usableValue(name: String): String? = field(name)?.takeIf { it.usable }?.value

    /** Customer-facing reason the photo must be retaken, or null when the image is usable. */
    val rejectionReason: String?
        get() {
            if (usable) return null
            val worst = qualityIssues.filter { it.severity == Severity.HIGH }.firstOrNull()
            return when {
                worst != null -> worst.type.customerWording.replaceFirstChar { it.uppercase() }
                documentType == DetectedDocumentType.UNREADABLE -> "We could not read the image"
                !typeMatchesRequest -> when (requestedType) {
                    DocumentType.KTP -> "This does not look like a KTP"
                    DocumentType.SELFIE_KTP -> "We could not see you holding your KTP next to your face"
                }
                else -> "The photo could not be accepted"
            }
        }

    companion object {
        /** Field names the model is asked to return for a KTP. */
        val KTP_FIELDS = listOf(
            "nik", "name", "place_of_birth", "date_of_birth", "gender", "blood_type", "address", "rt_rw",
            "village", "district", "religion", "marital_status", "occupation", "nationality", "valid_until", "province", "city",
        )

        /** Fields that must be confidently read before verification can run without a human. */
        val KTP_REQUIRED_FIELDS = listOf("nik", "name", "date_of_birth", "gender", "address")

        /** Observations the model is asked to return for the selfie. */
        val SELFIE_FIELDS = listOf("face_count", "face_clearly_visible", "ktp_held_in_frame", "nik_on_held_ktp", "face_matches_ktp_photo")
    }
}
