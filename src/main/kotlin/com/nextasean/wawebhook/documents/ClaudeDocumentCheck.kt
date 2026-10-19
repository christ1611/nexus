package com.nextasean.wawebhook.documents

import com.anthropic.client.AnthropicClient
import com.nextasean.wawebhook.config.ClaudeProperties
import com.nextasean.wawebhook.documents.analysis.AnalysisRequest
import com.nextasean.wawebhook.documents.analysis.AnalysisResultMapper
import com.nextasean.wawebhook.documents.analysis.ClaudeDocumentAnalysisService
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysis
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysisException
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysisService
import com.nextasean.wawebhook.verification.ExtractedFields
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import java.nio.file.Path

/** Wires the Claude analysis service and the adapter when `app.document-check=claude`. */
@Configuration
@ConditionalOnProperty(prefix = "app", name = ["document-check"], havingValue = "claude")
class ClaudeDocumentCheckConfig {
    @Bean fun analysisResultMapper(props: ClaudeProperties) = AnalysisResultMapper(props.minFieldConfidence)

    @Bean
    fun documentAnalysisService(client: AnthropicClient, props: ClaudeProperties, mapper: AnalysisResultMapper): DocumentAnalysisService =
        ClaudeDocumentAnalysisService(client, props, mapper)
}

/**
 * [DocumentCheckService] backed by [DocumentAnalysisService]. An unusable image fails the check so the customer is asked
 * again straight away; a usable image passes and carries the full [DocumentAnalysis] for the review agent. Only fields
 * that were confidently read (and a NIK that validated) are promoted to [ExtractedFields].
 */
@Service
@ConditionalOnProperty(prefix = "app", name = ["document-check"], havingValue = "claude")
class ClaudeDocumentCheck(private val analysis: DocumentAnalysisService) : DocumentCheckService {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun check(type: DocumentType, file: Path, mimeType: String?, caption: String?): DocumentCheckResult {
        val result = try {
            analysis.analyze(AnalysisRequest(type, file, mimeType))
        } catch (e: DocumentAnalysisException) {
            log.error("Document analysis unavailable | type={} | file={}", type, file, e)
            return DocumentCheckResult(passed = true, reason = "Analysis unavailable: ${e.message}", fields = null, analysis = null, analysisFailed = true)
        }
        if (!result.usable) return DocumentCheckResult.fail(result.rejectionReason ?: "The photo could not be accepted", result)
        return DocumentCheckResult.pass(toExtractedFields(result), result)
    }

    private fun toExtractedFields(a: DocumentAnalysis): ExtractedFields? {
        if (a.requestedType != DocumentType.KTP) return null
        val nik = a.usableValue("nik")?.takeIf { a.nik?.valid == true } ?: return null
        val name = a.usableValue("name") ?: return null
        val dob = a.usableValue("date_of_birth") ?: return null
        val address = listOfNotNull(a.usableValue("address"), a.usableValue("rt_rw")?.let { "RT/RW $it" }, a.usableValue("village"), a.usableValue("district"), a.usableValue("city"))
            .joinToString(", ").takeIf { it.isNotBlank() } ?: return null
        return ExtractedFields(name, nik, dob, address)
    }
}
