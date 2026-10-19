package com.nextasean.wawebhook.documents.analysis

import com.nextasean.wawebhook.documents.DocumentType

/** System prompt, per-document instructions and the JSON schema the model must fill. Kept stable so the prefix caches. */
object AnalysisPrompt {

    val SYSTEM: String = """
        You are the document intake analyst for an Indonesian consumer-finance onboarding flow. You receive one photo a
        customer sent over WhatsApp and you describe it for the back-office system as structured JSON.

        Your output is used to decide whether the customer must retake the photo and which values can be trusted.
        A wrong value is far more costly than a missing one, so:
        - Read only what is actually legible in the image. Never infer, complete, guess or normalise a value from context,
          from typical patterns, or from other fields. If a character is unclear, the whole field is unclear.
        - For every field give a calibrated confidence between 0 and 1 that the value is exactly right. Use 0.95 or more
          only when every character is crisp and unambiguous. If you are unsure between two readings, report the most
          likely one with a confidence below 0.7 and say in legibility_note what is unclear.
        - Fields that are not visible, cut off, or covered: value "" and confidence 0.
        - Transcribe values exactly as printed (uppercase, punctuation, spelling), except dates, which you return as
          YYYY-MM-DD, and the NIK, which you return as digits only.
        - Report quality issues honestly. Severity HIGH means the issue prevents reliable reading of important fields or the
          document identity; MEDIUM means some fields are affected; LOW is cosmetic.
        - Report the document type you actually see, even when it differs from what was requested.
        - Do not perform any identity check yourself and do not speculate about fraud; just describe what is visible.
          Mention anything odd about the document (editing artefacts, mismatched fonts, a photo of a screen) in notes.
    """.trimIndent()

    fun userInstruction(type: DocumentType): String = when (type) {
        DocumentType.KTP -> """
            The customer was asked for a photo of their KTP (Indonesian national ID card).
            Return a fields entry for each of these names, in this order: ${DocumentAnalysis.KTP_FIELDS.joinToString()}.
            Mapping from the card: nik = NIK; name = Nama; place_of_birth and date_of_birth = Tempat/Tgl Lahir split in two;
            gender = Jenis Kelamin; blood_type = Gol. Darah; address = Alamat; rt_rw = RT/RW; village = Kel/Desa;
            district = Kecamatan; religion = Agama; marital_status = Status Perkawinan; occupation = Pekerjaan;
            nationality = Kewarganegaraan; valid_until = Berlaku Hingga (text, e.g. SEUMUR HIDUP, or YYYY-MM-DD);
            province = PROVINSI header; city = KABUPATEN/KOTA header.
        """.trimIndent()
        DocumentType.SELFIE_KTP -> """
            The customer was asked for a selfie holding their KTP next to their face.
            Return a fields entry for each of these names: ${DocumentAnalysis.SELFIE_FIELDS.joinToString()}.
            face_count = number of faces visible (digits); face_clearly_visible = yes/no (unobstructed, in focus, not a photo of a photo);
            ktp_held_in_frame = yes/no (a physical KTP card is held by the person and is visible);
            nik_on_held_ktp = the NIK printed on the held card, digits only, only if clearly legible, otherwise "".
        """.trimIndent()
    }

    /** JSON schema for the structured output. Only features supported by structured outputs are used. */
    val SCHEMA: Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "required" to listOf("document_type", "document_type_confidence", "quality_issues", "fields", "notes"),
        "properties" to mapOf(
            "document_type" to mapOf(
                "type" to "string",
                "enum" to DetectedDocumentType.entries.map { it.name },
                "description" to "What the image actually shows.",
            ),
            "document_type_confidence" to mapOf("type" to "number", "description" to "0 to 1."),
            "quality_issues" to mapOf(
                "type" to "array",
                "description" to "Empty when the photo is clean.",
                "items" to mapOf(
                    "type" to "object",
                    "additionalProperties" to false,
                    "required" to listOf("type", "severity", "detail"),
                    "properties" to mapOf(
                        "type" to mapOf("type" to "string", "enum" to QualityIssueType.entries.map { it.name }),
                        "severity" to mapOf("type" to "string", "enum" to Severity.entries.map { it.name }),
                        "detail" to mapOf("type" to "string", "description" to "Where in the image and what it affects."),
                    ),
                ),
            ),
            "fields" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "additionalProperties" to false,
                    "required" to listOf("name", "value", "confidence", "legibility_note"),
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string"),
                        "value" to mapOf("type" to "string", "description" to "Exactly as printed; empty string when not visible."),
                        "confidence" to mapOf("type" to "number", "description" to "0 to 1 that the value is exactly right."),
                        "legibility_note" to mapOf("type" to "string", "description" to "What is unclear, or empty."),
                    ),
                ),
            ),
            "notes" to mapOf("type" to "string", "description" to "Anything else the reviewer should know; empty if nothing."),
        ),
    )
}
