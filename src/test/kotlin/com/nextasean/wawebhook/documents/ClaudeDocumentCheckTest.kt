package com.nextasean.wawebhook.documents

import com.nextasean.wawebhook.documents.analysis.AnalysisRequest
import com.nextasean.wawebhook.documents.analysis.AnalysisResultMapper
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysis
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysisException
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysisService
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeDocumentCheckTest {

    private val mapper = AnalysisResultMapper(0.85)
    private val today = LocalDate.of(2026, 10, 17)

    private class Scripted(val analysis: (AnalysisRequest) -> DocumentAnalysis) : DocumentAnalysisService {
        override fun analyze(request: AnalysisRequest) = analysis(request)
    }

    private fun ktp(nik: String, nikConfidence: Double = 0.99, issues: String = "[]") = mapper.map(
        DocumentType.KTP,
        """{"document_type":"KTP","document_type_confidence":0.99,"quality_issues":$issues,"fields":[
           {"name":"nik","value":"$nik","confidence":$nikConfidence,"legibility_note":""},
           {"name":"name","value":"BUDI SANTOSO","confidence":0.99,"legibility_note":""},
           {"name":"date_of_birth","value":"1990-05-17","confidence":0.97,"legibility_note":""},
           {"name":"gender","value":"LAKI-LAKI","confidence":0.99,"legibility_note":""},
           {"name":"address","value":"JL. MERDEKA NO. 10","confidence":0.95,"legibility_note":""},
           {"name":"rt_rw","value":"003/005","confidence":0.9,"legibility_note":""},
           {"name":"village","value":"GAMBIR","confidence":0.5,"legibility_note":"smudged"}],"notes":""}""",
        "m", today = today,
    )

    @Test
    fun `usable KTP with validated NIK passes and promotes only confident fields`() {
        val check = ClaudeDocumentCheck(Scripted { ktp("3171051705900001") })
        val result = check.check(DocumentType.KTP, Path.of("x.jpg"), "image/jpeg", null)
        assertTrue(result.passed)
        val f = result.fields!!
        assertEquals("3171051705900001", f.nik)
        assertEquals("BUDI SANTOSO", f.name)
        assertEquals("1990-05-17", f.dob)
        assertEquals("JL. MERDEKA NO. 10, RT/RW 003/005", f.address, "low-confidence village is left out")
        assertEquals(false, result.analysis?.needsOfficerReview)
    }

    @Test
    fun `usable KTP with unreliable NIK passes without fields so the agent can decide`() {
        val lowConfidence = ClaudeDocumentCheck(Scripted { ktp("3171051705900001", nikConfidence = 0.5) })
            .check(DocumentType.KTP, Path.of("x.jpg"), "image/jpeg", null)
        assertTrue(lowConfidence.passed)
        assertNull(lowConfidence.fields)
        assertEquals(true, lowConfidence.analysis?.needsOfficerReview)

        val invalid = ClaudeDocumentCheck(Scripted { ktp("9900001705900001") })
            .check(DocumentType.KTP, Path.of("x.jpg"), "image/jpeg", null)
        assertTrue(invalid.passed)
        assertNull(invalid.fields)
    }

    @Test
    fun `unusable image fails with the customer wording`() {
        val blurry = ClaudeDocumentCheck(Scripted { ktp("3171051705900001", issues = """[{"type":"BLUR","severity":"HIGH","detail":"all text soft"}]""") })
            .check(DocumentType.KTP, Path.of("x.jpg"), "image/jpeg", "")
        assertFalse(blurry.passed)
        assertEquals("The photo is blurry", blurry.reason)
    }

    @Test
    fun `analysis outage lets the document through flagged for an officer`() {
        val result = ClaudeDocumentCheck(Scripted { throw DocumentAnalysisException("Claude rate limit reached") })
            .check(DocumentType.KTP, Path.of("x.jpg"), "image/jpeg", null)
        assertTrue(result.passed)
        assertTrue(result.analysisFailed)
        assertNull(result.analysis)
        assertNull(result.fields)
    }
}
