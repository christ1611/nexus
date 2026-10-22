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
import com.nextasean.wawebhook.documents.analysis.NikValidator
import com.nextasean.wawebhook.documents.analysis.TamperSignal
import com.nextasean.wawebhook.documents.analysis.TamperSignalType
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

    @Test
    fun `the same ID number on another person's application is a HIGH fraud flag, on the same person's a duplicate warning`() {
        val mine = app().copy(customerPhone = "6281000000001", extractedName = "BUDI SANTOSO")
        val otherPerson = mine.copy(id = 11, applicationNumber = "APP-2026-001002", customerPhone = "6281000000002", extractedName = "SITI RAHAYU")
        val fraud = RiskRules.evaluate(mine, standardDealer, emptyList(), related = RelatedApplications(sameIdNumber = listOf(otherPerson)))
        val flag = fraud.flags.single { it.code == RiskCodes.SHARED_ID_NUMBER }
        assertEquals(RiskSeverity.HIGH, flag.severity)
        assertTrue(flag.fraudSignal)
        assertTrue(flag.detail.contains("APP-2026-001002") && flag.detail.contains("SITI") && !flag.detail.contains("3171051705900004"), flag.detail)
        assertTrue(fraud.grade.highRisk)

        val samePerson = mine.copy(id = 12, applicationNumber = "APP-2026-001003")
        val duplicate = RiskRules.evaluate(mine, standardDealer, emptyList(), related = RelatedApplications(sameIdNumber = listOf(samePerson)))
        assertEquals(listOf(RiskCodes.DUPLICATE_APPLICATION), duplicate.flags.map { it.code })
        assertFalse(duplicate.flags.single().fraudSignal)
        assertEquals(RiskGrade.B, duplicate.grade)

        // The application itself is never its own duplicate.
        assertTrue(RiskRules.evaluate(mine, standardDealer, emptyList(), related = RelatedApplications(sameIdNumber = listOf(mine))).flags.isEmpty())
    }

    @Test
    fun `the same phone number with a different ID number on another application is a HIGH fraud flag`() {
        val mine = app().copy(customerPhone = "6281000000001")
        val other = mine.copy(id = 13, applicationNumber = "APP-2026-001009", extractedNik = "3171051705900007")
        val a = RiskRules.evaluate(mine, standardDealer, emptyList(), related = RelatedApplications(samePhone = listOf(other)))
        val flag = a.flags.single { it.code == RiskCodes.PHONE_USED_WITH_OTHER_ID }
        assertEquals(RiskSeverity.HIGH, flag.severity)
        assertTrue(flag.fraudSignal)
        assertTrue(flag.detail.contains("***0007") && !flag.detail.contains("3171051705900007"), flag.detail)
        // Same phone, same ID number: a re-application, not this flag.
        assertTrue(RiskRules.evaluate(mine, standardDealer, emptyList(), related = RelatedApplications(samePhone = listOf(other.copy(extractedNik = mine.extractedNik)))).flags.none { it.code == RiskCodes.PHONE_USED_WITH_OTHER_ID })
    }

    @Test
    fun `forgery evidence from the vision analysis is a fraud flag whose severity follows the strongest signal`() {
        val weak = analysis(DocumentType.KTP, DetectedDocumentType.KTP).copy(tamperSignals = listOf(TamperSignal(TamperSignalType.FONT_MISMATCH, Severity.MEDIUM, "NIK digits are in a thinner font than the name")))
        val a = RiskRules.evaluate(app(), standardDealer, listOf(AnalysedDocument(record(DocumentType.KTP), weak)))
        val medium = a.flags.single { it.code == RiskCodes.FORGERY_SIGNS }
        assertEquals(RiskSeverity.MEDIUM, medium.severity)
        assertTrue(medium.fraudSignal)
        assertTrue(medium.detail.contains("글꼴"), medium.detail)
        assertEquals(RiskMethod.CLAUDE_VISION_RULES, a.method)

        val strong = weak.copy(tamperSignals = weak.tamperSignals + TamperSignal(TamperSignalType.PHOTO_REPLACED, Severity.HIGH, "photo edges are cut and the background colour differs"))
        val b = RiskRules.evaluate(app(), standardDealer, listOf(AnalysedDocument(record(DocumentType.KTP), strong)))
        assertEquals(RiskSeverity.HIGH, b.flags.single { it.code == RiskCodes.FORGERY_SIGNS }.severity)
        assertTrue(b.grade.highRisk)
    }

    @Test
    fun `NIK province that differs from the printed province is a fraud flag, matching spellings are not`() {
        val jakartaNik = NikValidator.validate("3171051705900004", "1990-05-17", "LAKI-LAKI")
        assertEquals("DKI Jakarta", jakartaNik.provinceName)
        val mismatch = analysis(DocumentType.KTP, DetectedDocumentType.KTP, fields = listOf(field("province", "PROVINSI JAWA BARAT"))).copy(nik = jakartaNik)
        val a = RiskRules.evaluate(app(), standardDealer, listOf(AnalysedDocument(record(DocumentType.KTP), mismatch)))
        val flag = a.flags.single { it.code == RiskCodes.ID_REGION_MISMATCH }
        assertEquals(RiskSeverity.HIGH, flag.severity)
        assertTrue(flag.detail.contains("DKI Jakarta") && flag.detail.contains("JAWA BARAT"), flag.detail)

        listOf("PROVINSI DKI JAKARTA", "DKI JAKARTA", "JAKARTA").forEach { printed ->
            val ok = mismatch.copy(fields = listOf(field("province", printed)))
            assertTrue(RiskRules.evaluate(app(), standardDealer, listOf(AnalysedDocument(record(DocumentType.KTP), ok))).flags.none { it.code == RiskCodes.ID_REGION_MISMATCH }, printed)
        }
        // A province the model could not read confidently is not compared.
        val unsure = mismatch.copy(fields = listOf(ExtractedField("province", "JAWA BARAT", 0.4, FieldStatus.NEEDS_OFFICER_REVIEW)))
        assertTrue(RiskRules.evaluate(app(), standardDealer, listOf(AnalysedDocument(record(DocumentType.KTP), unsure))).flags.none { it.code == RiskCodes.ID_REGION_MISMATCH })
    }

    @Test
    fun `selfie face that does not match the card photo is a HIGH fraud flag`() {
        val selfie = analysis(DocumentType.SELFIE_KTP, DetectedDocumentType.SELFIE_WITH_KTP, fields = listOf(field("face_count", "1"), field("nik_on_held_ktp", "3171051705900004"), field("face_matches_ktp_photo", "no")))
        val a = RiskRules.evaluate(app(), standardDealer, listOf(AnalysedDocument(record(DocumentType.SELFIE_KTP, id = 2), selfie)))
        val flag = a.flags.single { it.code == RiskCodes.SELFIE_FACE_MISMATCH }
        assertEquals(RiskSeverity.HIGH, flag.severity)
        assertTrue(flag.fraudSignal)
        assertTrue(a.grade.highRisk)
        val unclear = selfie.copy(fields = selfie.fields.map { if (it.name == "face_matches_ktp_photo") it.copy(value = "unclear") else it })
        assertTrue(RiskRules.evaluate(app(), standardDealer, listOf(AnalysedDocument(record(DocumentType.SELFIE_KTP, id = 2), unclear))).flags.none { it.code == RiskCodes.SELFIE_FACE_MISMATCH })
    }
}
