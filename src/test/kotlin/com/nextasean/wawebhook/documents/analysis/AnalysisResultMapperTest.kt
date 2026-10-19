package com.nextasean.wawebhook.documents.analysis

import com.nextasean.wawebhook.documents.DocumentType
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisResultMapperTest {

    private val mapper = AnalysisResultMapper(minFieldConfidence = 0.85)
    private val today = LocalDate.of(2026, 10, 17)

    private fun ktpJson(
        nik: String = "3171051705900001",
        nikConfidence: Double = 0.98,
        dob: String = "1990-05-17",
        gender: String = "LAKI-LAKI",
        issues: String = "[]",
        type: String = "KTP",
        extraFields: String = "",
    ) = """
        {"document_type":"$type","document_type_confidence":0.97,"quality_issues":$issues,
         "fields":[
           {"name":"nik","value":"$nik","confidence":$nikConfidence,"legibility_note":""},
           {"name":"name","value":"BUDI SANTOSO","confidence":0.99,"legibility_note":""},
           {"name":"place_of_birth","value":"JAKARTA","confidence":0.99,"legibility_note":""},
           {"name":"date_of_birth","value":"$dob","confidence":0.97,"legibility_note":""},
           {"name":"gender","value":"$gender","confidence":0.99,"legibility_note":""},
           {"name":"address","value":"JL. MERDEKA NO. 10","confidence":0.93,"legibility_note":""},
           {"name":"rt_rw","value":"003/005","confidence":0.9,"legibility_note":""},
           {"name":"blood_type","value":"","confidence":0,"legibility_note":"covered by thumb"}$extraFields
         ],
         "notes":""}
    """.trimIndent()

    @Test
    fun `clean KTP is usable, fields extracted and NIK validated`() {
        val a = mapper.map(DocumentType.KTP, ktpJson(), "test-model", today = today)
        assertTrue(a.usable)
        assertTrue(a.typeMatchesRequest)
        assertFalse(a.needsOfficerReview, a.reviewReasons.toString())
        assertEquals("3171051705900001", a.usableValue("nik"))
        assertEquals(FieldStatus.NOT_VISIBLE, a.field("blood_type")!!.status)
        assertNull(a.field("blood_type")!!.value)
        assertEquals(true, a.nik?.valid)
        assertEquals("DKI Jakarta", a.nik?.provinceName)
    }

    @Test
    fun `low-confidence NIK is kept as a candidate but marked for officer review, never used`() {
        val a = mapper.map(DocumentType.KTP, ktpJson(nikConfidence = 0.6), "m", today = today)
        assertTrue(a.usable)
        val nik = a.field("nik")!!
        assertEquals(FieldStatus.NEEDS_OFFICER_REVIEW, nik.status)
        assertEquals("3171051705900001", nik.value)
        assertNull(a.usableValue("nik"))
        assertTrue(a.needsOfficerReview)
        assertTrue(a.reviewReasons.any { it.startsWith("Field nik read with confidence 0.60") }, a.reviewReasons.toString())
    }

    @Test
    fun `NIK that contradicts the card is downgraded to officer review even at high confidence`() {
        val a = mapper.map(DocumentType.KTP, ktpJson(nik = "3171055705900001", gender = "LAKI-LAKI"), "m", today = today)
        assertTrue(a.usable)
        assertEquals(FieldStatus.NEEDS_OFFICER_REVIEW, a.field("nik")!!.status)
        assertEquals(false, a.nik?.valid)
        assertTrue(a.reviewReasons.any { it.contains("+40 rule") }, a.reviewReasons.toString())
        assertTrue(a.field("nik")!!.note!!.contains("NIK validation"))

        val dobMismatch = mapper.map(DocumentType.KTP, ktpJson(dob = "1990-06-17"), "m", today = today)
        assertTrue(dobMismatch.reviewReasons.any { it.contains("does not match the stated date of birth") })

        val knownDob = mapper.map(DocumentType.KTP, ktpJson(), "m", knownDob = LocalDate.of(1991, 5, 17), today = today)
        assertTrue(knownDob.reviewReasons.any { it.contains("on file") }, knownDob.reviewReasons.toString())
    }

    @Test
    fun `HIGH quality issue makes the image unusable with a customer-facing reason`() {
        val issues = """[{"type":"GLARE","severity":"HIGH","detail":"flash reflection over the NIK line"},{"type":"BLUR","severity":"LOW","detail":"slight"}]"""
        val a = mapper.map(DocumentType.KTP, ktpJson(issues = issues), "m", today = today)
        assertFalse(a.usable)
        assertEquals("There is glare or reflection on the card", a.rejectionReason)
        assertNull(a.nik, "NIK is not validated on an unusable image")
        assertTrue(a.needsOfficerReview)

        val medium = """[{"type":"BLUR","severity":"MEDIUM","detail":"address slightly soft"}]"""
        assertTrue(mapper.map(DocumentType.KTP, ktpJson(issues = medium), "m", today = today).usable)
    }

    @Test
    fun `wrong document type is rejected with the right wording`() {
        val passport = mapper.map(DocumentType.KTP, ktpJson(type = "PASSPORT"), "m", today = today)
        assertFalse(passport.usable)
        assertEquals("This does not look like a KTP", passport.rejectionReason)

        val selfieOnly = """{"document_type":"SELFIE_ONLY","document_type_confidence":0.9,"quality_issues":[],
            "fields":[{"name":"face_count","value":"1","confidence":0.99,"legibility_note":""},{"name":"ktp_held_in_frame","value":"no","confidence":0.95,"legibility_note":""}],"notes":""}"""
        val s = mapper.map(DocumentType.SELFIE_KTP, selfieOnly, "m", today = today)
        assertFalse(s.usable)
        assertEquals("We could not see you holding your KTP next to your face", s.rejectionReason)

        val good = selfieOnly.replace("SELFIE_ONLY", "SELFIE_WITH_KTP").replace("\"no\"", "\"yes\"")
        val g = mapper.map(DocumentType.SELFIE_KTP, good, "m", today = today)
        assertTrue(g.usable)
        assertFalse(g.needsOfficerReview)
    }

    @Test
    fun `unknown enum values and broken JSON degrade safely`() {
        val odd = ktpJson(issues = """[{"type":"RAINBOW","severity":"EXTREME","detail":"?"}]""")
        val a = mapper.map(DocumentType.KTP, odd, "m", today = today)
        assertEquals(QualityIssueType.OTHER, a.qualityIssues.single().type)
        assertEquals(Severity.MEDIUM, a.qualityIssues.single().severity)

        val broken = mapper.map(DocumentType.KTP, "{not json", "m", today = today)
        assertFalse(broken.usable)
        assertEquals(DetectedDocumentType.UNREADABLE, broken.documentType)
        assertTrue(broken.needsOfficerReview)
        assertNotNull(broken.rejectionReason)
    }
}
