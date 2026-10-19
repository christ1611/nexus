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

/** Result of analysing one image. Serialised as JSON onto the DOCUMENTS row and fed to the review agent. */
data class DocumentAnalysis(
    val requestedType: DocumentType,
    val documentType: DetectedDocumentType,
    val documentTypeConfidence: Double,
    val typeMatchesRequest: Boolean,
    val qualityIssues: List<QualityIssue>,
    /** True when the image is the requested document and has no HIGH severity quality issue. */
    val usable: Boolean,
    val fields: List<ExtractedField>,
    val nik: NikValidation?,
    /** True when a human must look at this document before the application can move on automatically. */
    val needsOfficerReview: Boolean,
    val reviewReasons: List<String>,
    val modelNotes: String?,
    val model: String,
    /** Exactly what the model returned, for audit. */
    val rawJson: String,
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
        val SELFIE_FIELDS = listOf("face_count", "face_clearly_visible", "ktp_held_in_frame", "nik_on_held_ktp")
    }
}
