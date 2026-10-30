package com.nextasean.wawebhook.review

import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.documents.DocumentRecord
import com.nextasean.wawebhook.documents.DocumentType
import com.nextasean.wawebhook.documents.analysis.AnalysisResultMapper
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysis
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuleBasedReviewAgentTest {

    private val agent = RuleBasedReviewAgent()
    private val mapper = AnalysisResultMapper(0.85)
    private val app = DealerApplication(id = 1, applicationNumber = "APP-2026-001001", dealerId = 1, customerName = "Budi", customerPhone = "628111")

    private fun record(type: DocumentType) = DocumentRecord(id = type.ordinal + 1L, applicationId = 1, type = type, mediaId = "m", filePath = "/f", checkPassed = true)

    private fun ktp(nikConfidence: Double = 0.99, nik: String = "3171051705900001") = mapper.map(
        DocumentType.KTP,
        """{"document_type":"KTP","document_type_confidence":0.99,"quality_issues":[],"fields":[
           {"name":"nik","value":"$nik","confidence":$nikConfidence,"legibility_note":"glare"},
           {"name":"name","value":"BUDI","confidence":0.99,"legibility_note":""},
           {"name":"date_of_birth","value":"1990-05-17","confidence":0.97,"legibility_note":""},
           {"name":"gender","value":"LAKI-LAKI","confidence":0.99,"legibility_note":""},
           {"name":"address","value":"JL. X","confidence":0.95,"legibility_note":""}],"notes":""}""",
        "m", today = LocalDate.of(2026, 10, 17),
    )

    private val selfie: DocumentAnalysis = mapper.map(
        DocumentType.SELFIE_KTP,
        """{"document_type":"SELFIE_WITH_KTP","document_type_confidence":0.95,"quality_issues":[],"fields":[
           {"name":"face_count","value":"1","confidence":0.99,"legibility_note":""},{"name":"face_clearly_visible","value":"yes","confidence":0.95,"legibility_note":""}],"notes":""}""",
        "m",
    )

    private fun ctx(ktp: DocumentAnalysis?, round: Int = 0, selfieReceived: Boolean = true) = ReviewContext(
        app,
        listOf(
            ReviewedDocument(DocumentType.KTP, ktp?.let { record(DocumentType.KTP) }, ktp),
            ReviewedDocument(DocumentType.SELFIE_KTP, if (selfieReceived) record(DocumentType.SELFIE_KTP) else null, if (selfieReceived) selfie else null),
        ),
        round, maxRounds = 2,
    )

    @Test
    fun `clean documents produce a summary without officer review`() {
        val d = assertIs<ReviewDecision.SummaryWritten>(agent.decide(ctx(ktp())))
        assertEquals(false, d.officerReviewRequired)
        assertTrue(d.summary.contains("APP-2026-001001") && d.summary.contains("NIK VALIDATION: PASSED"), d.summary)
        assertTrue(d.summary.lines().size < 60, "one page")
        assertTrue(d.summaryKo!!.contains("APP-2026-001001") && d.summaryKo!!.contains("NIK 검증: 통과"), "the same facts in Korean: " + d.summaryKo)
    }

    @Test
    fun `illegible NIK asks for a retake while rounds remain, then goes to the officer`() {
        val retake = assertIs<ReviewDecision.RequestDocuments>(agent.decide(ctx(ktp(nikConfidence = 0.4))))
        assertEquals(DocumentType.KTP, retake.items.single().type)
        assertTrue(retake.items.single().reason.startsWith("nik could not be read clearly"), retake.items.single().reason)

        val exhausted = assertIs<ReviewDecision.SummaryWritten>(agent.decide(ctx(ktp(nikConfidence = 0.4), round = 2)))
        assertEquals(true, exhausted.officerReviewRequired)
        assertTrue(exhausted.summary.contains("[NEEDS OFFICER REVIEW]"), exhausted.summary)
    }

    @Test
    fun `structurally invalid NIK is an officer matter, not a retake`() {
        val d = assertIs<ReviewDecision.SummaryWritten>(agent.decide(ctx(ktp(nik = "3171055705900001"))))
        assertEquals(true, d.officerReviewRequired)
        assertTrue(d.summary.contains("+40 rule"), d.summary)
    }

    @Test
    fun `missing document is requested`() {
        val d = assertIs<ReviewDecision.RequestDocuments>(agent.decide(ctx(ktp(), selfieReceived = false)))
        assertEquals(DocumentType.SELFIE_KTP, d.items.single().type)
    }
}
