package com.nextasean.wawebhook.documents

import com.nextasean.wawebhook.application.ApplicationRepository
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

/** Which application the document belongs to; lets the stub produce a distinct sample NIK per application. */
data class CheckContext(val applicationNumber: String? = null, val customerPhone: String? = null)

/** Quality / OCR check of a collected document. Real implementations would call a KYC vendor. */
interface DocumentCheckService {
    fun check(type: DocumentType, file: Path, mimeType: String?, caption: String?, context: CheckContext = CheckContext()): DocumentCheckResult
}

/**
 * Demo implementation: waits [DemoDataProperties.checkDelay], then passes with the sample fields from demo-data.yml.
 * The sample NIK gets its serial digits from the application number so two customers never share an ID by accident.
 * Caption tricks: "blur" fails with "Photo is blurry"; "nik7" (any digit) sets the last digit of the NIK so every
 * provider outcome can be shown live; "dup" submits the NIK of the most recent other application, which triggers the
 * shared-ID fraud flag.
 */
@Service
@ConditionalOnProperty(prefix = "app", name = ["document-check"], havingValue = "stub", matchIfMissing = true)
class StubDocumentCheck(private val demo: DemoDataProperties, private val applications: ApplicationRepository) : DocumentCheckService {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun check(type: DocumentType, file: Path, mimeType: String?, caption: String?, context: CheckContext): DocumentCheckResult {
        val delay = demo.checkDelay.toMillis()
        if (delay > 0) Thread.sleep(delay)
        val text = caption.orEmpty().lowercase()
        if (text.contains("blur")) {
            log.info("Stub check | type={} | FAIL (caption trigger)", type)
            return DocumentCheckResult.fail("Photo is blurry")
        }
        val fields = if (type == DocumentType.KTP) {
            val nik = if (DUPLICATE_TRIGGER.containsMatchIn(text)) previousNik(context) ?: sampleNik(text, context) else sampleNik(text, context)
            ExtractedFields(demo.extracted.name, nik, demo.extracted.dob, demo.extracted.address)
        } else {
            null
        }
        log.info("Stub check | type={} | PASS | nik=***{}", type, fields?.nik?.takeLast(4))
        return DocumentCheckResult.pass(fields)
    }

    /** Demo NIK with serial digits 13-15 taken from the application number and the last digit from the caption (or the demo default). */
    private fun sampleNik(caption: String, context: CheckContext): String {
        val base = demo.extracted.nik
        val lastDigit = NIK_OVERRIDE.find(caption)?.groupValues?.get(1) ?: base.takeLast(1)
        val serial = context.applicationNumber?.filter { it.isDigit() }?.takeLast(3)?.padStart(3, '0') ?: base.substring(12, 15)
        return base.substring(0, 12) + serial + lastDigit
    }

    /** NIK of the most recent other application that has one (the "dup" caption demonstrates a shared ID number). */
    private fun previousNik(context: CheckContext): String? = applications.findAllByOrderByCreatedAtDesc()
        .firstOrNull { it.applicationNumber != context.applicationNumber && !it.extractedNik.isNullOrBlank() }
        ?.extractedNik
        ?.also { log.info("Stub check | caption 'dup': reusing NIK ***{} from another application", it.takeLast(4)) }

    private companion object {
        val NIK_OVERRIDE = Regex("nik\\s*(\\d)")
        val DUPLICATE_TRIGGER = Regex("\\bdup(licate)?\\b")
    }
}
