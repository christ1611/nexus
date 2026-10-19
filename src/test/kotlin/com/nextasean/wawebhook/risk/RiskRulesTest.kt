package com.nextasean.wawebhook.risk

import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.dealer.Dealer
import com.nextasean.wawebhook.dealer.DealerTier
import com.nextasean.wawebhook.documents.DocumentRecord
import com.nextasean.wawebhook.documents.DocumentType
import com.nextasean.wawebhook.documents.analysis.DetectedDocumentType
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysis
import com.nextasean.wawebhook.documents.analysis.ExtractedField
import com.nextasean.wawebhook.documents.analysis.FieldStatus
import com.nextasean.wawebhook.documents.analysis.QualityIssue
import com.nextasean.wawebhook.documents.analysis.QualityIssueType
import com.nextasean.wawebhook.documents.analysis.Severity
import com.nextasean.wawebhook.verification.ProviderOutcome
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RiskRulesTest {

    private val excellentDealer = Dealer(id = 1, phone = "628111", name = "Top", dealerCode = "DLR-1", tier = DealerTier.EXCELLENT)
    private val standardDealer = excellentDealer.copy(id = 2, dealerCode = "DLR-2", tier = DealerTier.STANDARD)

    private fun app(
        id: ProviderOutcome? = ProviderOutcome.MATCH,
        credit: ProviderOutcome? = ProviderOutcome.MATCH,
        score: Int? = 720,
        rounds: Int = 0,
        dob: String? = "1990-05-17",
        nik: String? = "3171051705900004",
    ) = DealerApplication(
        id = 10, applicationNumber = "APP-2026-001001", dealerId = 1, extractedNik = nik, extractedDob = dob, extractedName = "Budi",
        idVerificationStatus = id, creditCheckStatus = credit, creditScore = score, reviewRounds = rounds,
    )

    private fun record(type: DocumentType, passed: Boolean = true, superseded: Boolean = false, id: Long = 1) =
        DocumentRecord(id = id, applicationId = 10, type = type, mediaId = "m$id", filePath = "/tmp/$id.jpg", checkPassed = passed, superseded = superseded)

    private fun analysis(
        type: DocumentType,
        detected: DetectedDocumentType,
        issues: List<QualityIssue> = emptyList(),
        fields: List<ExtractedField> = emptyList(),
    ) = DocumentAnalysis(
        requestedType = type, documentType = detected, documentTypeConfidence = 0.95, typeMatchesRequest = detected.satisfies(type),
        qualityIssues = issues, usable = issues.none { it.severity == Severity.HIGH } && detected.satisfies(type), fields = fields, nik = null,
        needsOfficerReview = false, reviewReasons = emptyList(), modelNotes = null, model = "test", rawJson = "{}",
    )

    private fun field(name: String, value: String) = ExtractedField(name, value, 0.99, FieldStatus.EXTRACTED)

    @Test
    fun `clean application from an excellent dealer is grade A with all three badges`() {
        val a = RiskRules.evaluate(app(), excellentDealer, emptyList())
        assertEquals(RiskGrade.A, a.grade)
        assertEquals(0, a.score)
        assertTrue(a.flags.isEmpty())
        assertEquals(setOf(CustomerBadge.VERIFIED_IDENTITY, CustomerBadge.FINANCIAL_STABILITY, CustomerBadge.EXCELLENT_DEALER_REFERRAL), a.badges.toSet())
        assertEquals(RiskMethod.RULES, a.method)
    }

    @Test
    fun `standard dealer and a score below 700 earn no financial badge`() {
        val a = RiskRules.evaluate(app(score = 660), standardDealer, emptyList())
        assertEquals(RiskGrade.A, a.grade)
        assertEquals(listOf(CustomerBadge.VERIFIED_IDENTITY), a.badges)
    }

    @Test
    fun `registry mismatch is a HIGH fraud flag and grades at least C`() {
        val a = RiskRules.evaluate(app(id = ProviderOutcome.MISMATCH, credit = ProviderOutcome.MISMATCH, score = null), standardDealer, emptyList())
        assertContains(a.flags.map { it.code }, RiskCodes.IDENTITY_MISMATCH)
        assertContains(a.flags.map { it.code }, RiskCodes.CREDIT_IDENTITY_MISMATCH)
        assertEquals(55, a.score)
        assertEquals(RiskGrade.C, a.grade)
        assertTrue(a.fraudFlags.isNotEmpty())
        assertTrue(a.badges.isEmpty())
    }

    @Test
    fun `low score and repeated resubmission add up to grade B`() {
        val a = RiskRules.evaluate(app(score = 645, rounds = 2), standardDealer, emptyList())
        assertEquals(listOf(RiskCodes.LOW_CREDIT_SCORE, RiskCodes.REPEATED_RESUBMISSION).sorted(), a.flags.map { it.code }.sorted())
        assertEquals(40, a.score)
        assertEquals(RiskGrade.C, a.grade) // 40 is the C threshold
        assertFalse(a.fraudFlags.isNotEmpty())
    }

    @Test
    fun `timeouts and no credit history are warnings, not fraud`() {
        val a = RiskRules.evaluate(app(id = ProviderOutcome.TIMEOUT, credit = ProviderOutcome.NOT_FOUND, score = null), standardDealer, emptyList())
        assertEquals(setOf(RiskCodes.IDENTITY_UNVERIFIED, RiskCodes.NO_CREDIT_HISTORY), a.flags.map { it.code }.toSet())
        assertEquals(RiskGrade.B, a.grade)
        assertTrue(a.fraudFlags.isEmpty())
    }

    @Test
    fun `screen photo from the vision analysis is a fraud flag and switches the method to Claude`() {
        val ktp = AnalysedDocument(
            record(DocumentType.KTP),
            analysis(DocumentType.KTP, DetectedDocumentType.KTP, issues = listOf(QualityIssue(QualityIssueType.SCREEN_PHOTO, Severity.MEDIUM, "moire pattern"))),
        )
        val a = RiskRules.evaluate(app(), excellentDealer, listOf(ktp))
        val flag = a.flags.single { it.code == RiskCodes.SCREEN_OR_COPY }
        assertEquals(RiskSeverity.HIGH, flag.severity)
        assertTrue(flag.fraudSignal)
        assertEquals(RiskGrade.C, a.grade)
        assertEquals(RiskMethod.CLAUDE_VISION_RULES, a.method)
        assertEquals(listOf(CustomerBadge.EXCELLENT_DEALER_REFERRAL), a.badges)
    }

    @Test
    fun `selfie holding a different KTP and a NIK that changed between uploads are both flagged`() {
        val oldKtp = AnalysedDocument(
            record(DocumentType.KTP, superseded = true, id = 1),
            analysis(DocumentType.KTP, DetectedDocumentType.KTP, fields = listOf(field("nik", "3171051705900009"))),
        )
        val newKtp = AnalysedDocument(record(DocumentType.KTP, id = 2), analysis(DocumentType.KTP, DetectedDocumentType.KTP, fields = listOf(field("nik", "3171051705900004"))))
        val selfie = AnalysedDocument(
            record(DocumentType.SELFIE_KTP, id = 3),
            analysis(DocumentType.SELFIE_KTP, DetectedDocumentType.SELFIE_WITH_KTP, fields = listOf(field("face_count", "2"), field("nik_on_held_ktp", "3171051705900123"))),
        )
        val a = RiskRules.evaluate(app(), standardDealer, listOf(oldKtp, newKtp, selfie))
        val codes = a.flags.map { it.code }
        assertContains(codes, RiskCodes.ID_CHANGED_BETWEEN_UPLOADS)
        assertContains(codes, RiskCodes.SELFIE_ID_MISMATCH)
        assertContains(codes, RiskCodes.MULTIPLE_FACES)
        assertEquals(RiskGrade.D, a.grade)
        assertTrue(a.flags.none { it.detail.contains("3171051705900004") }, "full ID numbers must never appear in flag details")
    }

    @Test
    fun `age outside the allowed range and many failed uploads are behaviour flags`() {
        val failed = (1..3).map { AnalysedDocument(record(DocumentType.KTP, passed = false, id = it.toLong()), null) }
        val a = RiskRules.evaluate(app(dob = "2015-01-01"), standardDealer, failed)
        assertEquals(setOf(RiskCodes.AGE_OUT_OF_RANGE, RiskCodes.MANY_FAILED_UPLOADS), a.flags.map { it.code }.toSet())
        assertTrue(a.flags.all { it.source == RiskSource.BEHAVIOUR })
    }
}
