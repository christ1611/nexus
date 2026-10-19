package com.nextasean.wawebhook.documents

import com.nextasean.wawebhook.config.DemoDataProperties
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysis
import com.nextasean.wawebhook.verification.ExtractedFields
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * @property passed false means the customer is asked for the same document again right away.
 * @property fields fields safe to store on the application (confidently read and validated); null otherwise.
 * @property analysis full analysis when a vision model looked at the image (null for the stub).
 * @property analysisFailed the analysis service was unavailable; the document is kept and left to an officer.
 */
data class DocumentCheckResult(
    val passed: Boolean,
    val reason: String? = null,
    val fields: ExtractedFields? = null,
    val analysis: DocumentAnalysis? = null,
    val analysisFailed: Boolean = false,
) {
    companion object {
        fun pass(fields: ExtractedFields? = null, analysis: DocumentAnalysis? = null) = DocumentCheckResult(true, null, fields, analysis)
        fun fail(reason: String, analysis: DocumentAnalysis? = null) = DocumentCheckResult(false, reason, null, analysis)
    }
}

/** Quality / OCR check of a collected document. Real implementations would call a KYC vendor. */
interface DocumentCheckService {
    fun check(type: DocumentType, file: Path, mimeType: String?, caption: String?): DocumentCheckResult
}

/**
 * Demo implementation: waits [DemoDataProperties.checkDelay], then passes with the sample fields from
 * demo-data.yml. Caption tricks: "blur" fails with "Photo is blurry"; "nik7" (any digit) overrides the
 * last digit of the demo NIK so every provider outcome can be shown live.
 */
@Service
@ConditionalOnProperty(prefix = "app", name = ["document-check"], havingValue = "stub", matchIfMissing = true)
class StubDocumentCheck(private val demo: DemoDataProperties) : DocumentCheckService {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun check(type: DocumentType, file: Path, mimeType: String?, caption: String?): DocumentCheckResult {
        val delay = demo.checkDelay.toMillis()
        if (delay > 0) Thread.sleep(delay)
        val text = caption.orEmpty().lowercase()
        if (text.contains("blur")) {
            log.info("Stub check | type={} | FAIL (caption trigger)", type)
            return DocumentCheckResult.fail("Photo is blurry")
        }
        val fields = if (type == DocumentType.KTP) {
            val nik = NIK_OVERRIDE.find(text)?.groupValues?.get(1)
                ?.let { demo.extracted.nik.dropLast(1) + it } ?: demo.extracted.nik
            ExtractedFields(demo.extracted.name, nik, demo.extracted.dob, demo.extracted.address)
        } else {
            null
        }
        log.info("Stub check | type={} | PASS | nik=***{}", type, fields?.nik?.takeLast(4))
        return DocumentCheckResult.pass(fields)
    }

    private companion object {
        val NIK_OVERRIDE = Regex("nik\\s*(\\d)")
    }
}
