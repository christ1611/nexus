package com.nextasean.wawebhook.documents.analysis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.nextasean.wawebhook.documents.DocumentType
import java.time.LocalDate

/**
 * Turns the model's JSON into a [DocumentAnalysis], applying the rules the model is not trusted with:
 * the confidence threshold, the NIK structure checks and the "needs officer review" decision.
 */
class AnalysisResultMapper(
    private val minFieldConfidence: Double,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RawAnalysis(
        @JsonProperty("document_type") val documentType: String? = null,
        @JsonProperty("document_type_confidence") val documentTypeConfidence: Double? = null,
        @JsonProperty("quality_issues") val qualityIssues: List<RawIssue> = emptyList(),
        @JsonProperty("tamper_signals") val tamperSignals: List<RawIssue> = emptyList(),
        val fields: List<RawField> = emptyList(),
        val notes: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RawIssue(val type: String? = null, val severity: String? = null, val detail: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RawField(
        val name: String? = null,
        val value: String? = null,
        val confidence: Double? = null,
        @JsonProperty("legibility_note") val legibilityNote: String? = null,
    )

    fun map(requested: DocumentType, json: String, model: String, knownDob: LocalDate? = null, today: LocalDate = LocalDate.now()): DocumentAnalysis {
        val raw: RawAnalysis = try {
            objectMapper.readValue(json)
        } catch (e: Exception) {
            return notAnalysed(requested, "Model response was not valid JSON: ${e.message}", model, json)
        }

        val detected = raw.documentType?.let { runCatching { DetectedDocumentType.valueOf(it) }.getOrNull() } ?: DetectedDocumentType.UNREADABLE
        val typeConfidence = raw.documentTypeConfidence.clamp()
        val issues = raw.qualityIssues.map {
            QualityIssue(
                type = it.type?.let { t -> runCatching { QualityIssueType.valueOf(t) }.getOrNull() } ?: QualityIssueType.OTHER,
                severity = it.severity?.let { s -> runCatching { Severity.valueOf(s) }.getOrNull() } ?: Severity.MEDIUM,
                detail = it.detail.orEmpty(),
            )
        }

        val tamper = raw.tamperSignals.map {
            TamperSignal(
                type = it.type?.let { t -> runCatching { TamperSignalType.valueOf(t) }.getOrNull() } ?: TamperSignalType.OTHER,
                severity = it.severity?.let { s -> runCatching { Severity.valueOf(s) }.getOrNull() } ?: Severity.MEDIUM,
                detail = it.detail.orEmpty(),
            )
        }

        val fields = raw.fields.filter { !it.name.isNullOrBlank() }.map { f ->
            val value = f.value?.trim()?.takeIf { it.isNotEmpty() }
            val confidence = f.confidence.clamp()
            val status = when {
                value == null -> FieldStatus.NOT_VISIBLE
                confidence < minFieldConfidence -> FieldStatus.NEEDS_OFFICER_REVIEW
                else -> FieldStatus.EXTRACTED
            }
            ExtractedField(f.name!!.trim(), value, confidence, status, f.legibilityNote?.takeIf { it.isNotBlank() })
        }.toMutableList()

        val typeMatches = detected.satisfies(requested)
        val usable = typeMatches && issues.none { it.severity == Severity.HIGH }
        val reasons = mutableListOf<String>()
        if (!typeMatches) reasons += "Image shows ${detected.label}, but ${requested.label} was requested"
        issues.filter { it.severity == Severity.HIGH }.forEach { reasons += "Quality issue ${it.type} (HIGH): ${it.detail}" }
        // Forgery evidence never sends the customer back for a retake; it sends the case to an officer.
        tamper.filter { it.severity != Severity.LOW }.forEach { reasons += "Possible forgery ${it.type} (${it.severity}): ${it.detail}" }

        var nikValidation: NikValidation? = null
        if (requested == DocumentType.KTP && usable) {
            val nikIndex = fields.indexOfFirst { it.name == "nik" }
            val nikField = fields.getOrNull(nikIndex)
            if (nikField?.value != null) {
                val cardDob = fields.firstOrNull { it.name == "date_of_birth" }?.value
                val gender = fields.firstOrNull { it.name == "gender" }?.value
                var validation = NikValidator.validate(nikField.value, cardDob, gender, today)
                if (knownDob != null && validation.dobFromNik != null) {
                    val known = NikValidator.validate(nikField.value, knownDob.toString(), gender, today)
                    if (known.dobMatches == false) {
                        validation = validation.copy(issues = validation.issues + "Date of birth encoded in NIK does not match the date of birth on file ($knownDob)")
                    }
                }
                nikValidation = validation
                if (!validation.valid && nikField.status == FieldStatus.EXTRACTED) {
                    fields[nikIndex] = nikField.copy(
                        status = FieldStatus.NEEDS_OFFICER_REVIEW,
                        note = listOfNotNull(nikField.note, "NIK validation: " + validation.issues.joinToString("; ")).joinToString(" | "),
                    )
                }
                validation.issues.forEach { reasons += "NIK: $it" }
            }
            for (name in DocumentAnalysis.KTP_REQUIRED_FIELDS) {
                val f = fields.firstOrNull { it.name == name }
                when (f?.status) {
                    FieldStatus.EXTRACTED -> Unit
                    FieldStatus.NEEDS_OFFICER_REVIEW -> if (name != "nik" || nikValidation?.valid != false) {
                        reasons += "Field $name read with confidence ${"%.2f".format(f.confidence)} (below ${"%.2f".format(minFieldConfidence)})"
                    }
                    FieldStatus.NOT_VISIBLE, null -> reasons += "Field $name is not visible"
                }
            }
        }
        if (requested == DocumentType.SELFIE_KTP && usable) {
            val faces = fields.firstOrNull { it.name == "face_count" }?.value?.trim()?.toIntOrNull()
            if (faces != null && faces != 1) reasons += "Selfie shows $faces faces"
            if (fields.firstOrNull { it.name == "face_clearly_visible" }?.value?.equals("yes", ignoreCase = true) == false) {
                reasons += "Face is not clearly visible in the selfie"
            }
            if (fields.firstOrNull { it.name == "face_matches_ktp_photo" }?.value?.equals("no", ignoreCase = true) == true) {
                reasons += "Face in the selfie does not look like the photo on the held KTP"
            }
        }

        return DocumentAnalysis(
            requestedType = requested,
            documentType = detected,
            documentTypeConfidence = typeConfidence,
            typeMatchesRequest = typeMatches,
            qualityIssues = issues,
            usable = usable,
            tamperSignals = tamper,
            fields = fields,
            nik = nikValidation,
            needsOfficerReview = reasons.isNotEmpty(),
            reviewReasons = reasons,
            modelNotes = raw.notes?.takeIf { it.isNotBlank() },
            model = model,
            rawJson = json,
        )
    }

    /** Analysis for an image the model did not or could not look at (unsupported format, refusal, bad JSON). */
    fun notAnalysed(requested: DocumentType, reason: String, model: String, rawJson: String = ""): DocumentAnalysis = DocumentAnalysis(
        requestedType = requested,
        documentType = DetectedDocumentType.UNREADABLE,
        documentTypeConfidence = 0.0,
        typeMatchesRequest = false,
        qualityIssues = listOf(QualityIssue(QualityIssueType.OTHER, Severity.HIGH, reason)),
        usable = false,
        fields = emptyList(),
        nik = null,
        needsOfficerReview = true,
        reviewReasons = listOf(reason),
        modelNotes = null,
        model = model,
        rawJson = rawJson,
    )

    private fun Double?.clamp(): Double = (this ?: 0.0).coerceIn(0.0, 1.0)
}
