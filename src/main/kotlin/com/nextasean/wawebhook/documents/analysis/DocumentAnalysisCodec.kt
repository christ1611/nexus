package com.nextasean.wawebhook.documents.analysis

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** (De)serialises [DocumentAnalysis] for the DOCUMENTS.analysis_json column using the application's Jackson mapper. */
@Component
class DocumentAnalysisCodec(private val objectMapper: ObjectMapper) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun toJson(analysis: DocumentAnalysis): String {
        val json = objectMapper.writeValueAsString(analysis)
        if (json.length <= MAX_LENGTH) return json
        // Keep the structured part; the verbatim model output is the only field that can grow without bound.
        return objectMapper.writeValueAsString(analysis.copy(rawJson = "<omitted, ${analysis.rawJson.length} chars>"))
    }

    /** Serialises any value with the application mapper (used by the demo seeder for agent-step data). */
    fun toJsonAny(value: Any): String = objectMapper.writeValueAsString(value)

    fun fromJson(json: String?): DocumentAnalysis? {
        if (json.isNullOrBlank()) return null
        return try {
            objectMapper.readValue(json, DocumentAnalysis::class.java)
        } catch (e: Exception) {
            log.warn("Stored analysis could not be parsed: {}", e.message)
            null
        }
    }

    companion object {
        const val MAX_LENGTH = 60_000
    }
}
