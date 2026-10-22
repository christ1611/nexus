package com.nextasean.wawebhook.risk

import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.dealer.Dealer
import com.nextasean.wawebhook.dealer.DealerTier
import com.nextasean.wawebhook.documents.DocumentRecord
import com.nextasean.wawebhook.documents.DocumentType
import com.nextasean.wawebhook.documents.analysis.DetectedDocumentType
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysis
import com.nextasean.wawebhook.documents.analysis.NikValidator
import com.nextasean.wawebhook.documents.analysis.QualityIssueType
import com.nextasean.wawebhook.documents.analysis.Severity
import com.nextasean.wawebhook.verification.ProviderOutcome
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset

/** A stored document together with its parsed analysis (null in stub mode). */
data class AnalysedDocument(val record: DocumentRecord, val analysis: DocumentAnalysis?)

/**
 * Other applications that share this one's ID number or customer phone (never the application itself).
 * Built by [RiskAssessmentService] from the database; [NONE] for unit tests and single-application evaluation.
 */
data class RelatedApplications(val sameIdNumber: List<DealerApplication> = emptyList(), val samePhone: List<DealerApplication> = emptyList()) {
    companion object {
        val NONE = RelatedApplications()
    }
}

/**
 * Deterministic risk rules. The model (when enabled) supplies the evidence - document type, quality issues, field
 * confidences, NIK validation - and these rules turn the evidence plus the provider results into flags, a score,
 * a grade and badges. Keeping the rules in code makes every grade explainable and testable.
 */
object RiskRules {

    const val LOW_SCORE_THRESHOLD = 650
    const val STRONG_SCORE_THRESHOLD = 700
    const val MIN_AGE = 17
    const val MAX_AGE = 80

    fun evaluate(
        app: DealerApplication,
        dealer: Dealer?,
        documents: List<AnalysedDocument>,
        now: Instant = Instant.now(),
        related: RelatedApplications = RelatedApplications.NONE,
    ): RiskAssessment {
        val flags = mutableListOf<RiskFlag>()
        val analysed = documents.any { it.analysis != null }
        val current = documents.filter { !it.record.superseded && it.record.checkPassed == true }
        val latestByType = current.groupBy { it.record.type }.mapValues { (_, d) -> d.maxBy { it.record.id ?: 0L } }

        documentFlags(latestByType, app, flags)
        historyFlags(documents, app, flags)
        crossApplicationFlags(app, related, flags)
        identityFlags(app, flags)
        creditFlags(app, flags)
        behaviourFlags(documents, app, flags, now)

        val score = flags.sumOf { it.severity.points }.coerceAtMost(100)
        val grade = RiskGrade.forScore(score, flags)
        val badges = badges(app, dealer, flags, grade)
        return RiskAssessment(
            score = score,
            grade = grade,
            flags = flags.sortedWith(compareByDescending<RiskFlag> { it.severity }.thenBy { it.code }),
            badges = badges,
            method = if (analysed) RiskMethod.CLAUDE_VISION_RULES else RiskMethod.RULES,
            assessedAt = now,
        )
    }

    private fun documentFlags(latest: Map<DocumentType, AnalysedDocument>, app: DealerApplication, flags: MutableList<RiskFlag>) {
        latest.values.forEach { doc ->
            val a = doc.analysis ?: return@forEach
            val label = doc.record.type.label
            a.qualityIssues.firstOrNull { it.type == QualityIssueType.SCREEN_PHOTO }?.let {
                flags += RiskFlag(
                    RiskCodes.SCREEN_OR_COPY, if (it.severity == Severity.LOW) RiskSeverity.MEDIUM else RiskSeverity.HIGH, RiskSource.DOCUMENT,
                    "Document looks like a photo of a screen or a photocopy", "서류가 화면 재촬영 또는 복사본으로 보임", "$label: ${it.detail}",
                )
            }
            a.qualityIssues.firstOrNull { it.type == QualityIssueType.OBSTRUCTED && it.severity != Severity.LOW }?.let {
                flags += RiskFlag(
                    RiskCodes.TAMPERING_SUSPECTED, RiskSeverity.MEDIUM, RiskSource.DOCUMENT,
                    "Part of the document is covered or altered", "서류 일부가 가려지거나 변조된 흔적", "$label: ${it.detail}",
                )
            }
            a.tamperSignals.takeIf { it.isNotEmpty() }?.let { signals ->
                val worst = signals.maxOf { it.severity }
                flags += RiskFlag(
                    RiskCodes.FORGERY_SIGNS, if (worst == Severity.HIGH) RiskSeverity.HIGH else RiskSeverity.MEDIUM, RiskSource.DOCUMENT,
                    "Visible signs of editing or forgery on the document", "서류에서 편집·위조 흔적 발견",
                    "$label: " + signals.joinToString("; ") { "${it.type.labelKo} (${it.severity}): ${it.detail}" },
                )
            }
            if (!a.typeMatchesRequest || a.documentType in setOf(DetectedDocumentType.OTHER_DOCUMENT, DetectedDocumentType.NOT_A_DOCUMENT)) {
                flags += RiskFlag(
                    RiskCodes.WRONG_DOCUMENT_TYPE, RiskSeverity.MEDIUM, RiskSource.DOCUMENT,
                    "Accepted document is not the requested type", "접수된 서류가 요청한 종류와 다름",
                    "$label: detected ${a.documentType.label} (confidence ${"%.2f".format(a.documentTypeConfidence)})",
                )
            }
            if (doc.record.type == DocumentType.KTP) {
                a.nik?.takeIf { it.formatValid && !it.valid }?.let { nik ->
                    flags += RiskFlag(
                        RiskCodes.INVALID_ID_NUMBER, RiskSeverity.HIGH, RiskSource.DOCUMENT,
                        "ID number fails structural validation", "주민등록번호(NIK) 구조 검증 실패",
                        nik.issues.joinToString("; ").ifBlank { "unknown region or date encoding" },
                    )
                }
                a.nik?.takeIf { !it.formatValid }?.let {
                    flags += RiskFlag(
                        RiskCodes.INVALID_ID_NUMBER, RiskSeverity.HIGH, RiskSource.DOCUMENT,
                        "ID number is not 16 digits", "주민등록번호(NIK) 형식 오류", "read as ***${it.nik.takeLast(4)}",
                    )
                }
                regionMismatch(a)?.let { (encoded, printed) ->
                    flags += RiskFlag(
                        RiskCodes.ID_REGION_MISMATCH, RiskSeverity.HIGH, RiskSource.DOCUMENT,
                        "Province encoded in the NIK differs from the province printed on the card", "NIK 지역 코드와 카드에 인쇄된 발급 지역 불일치",
                        "NIK encodes $encoded, card header reads $printed",
                    )
                }
                val weak = DocumentAnalysis.KTP_REQUIRED_FIELDS.filter { a.field(it)?.usable != true }
                if (weak.isNotEmpty() && a.usable) {
                    flags += RiskFlag(
                        RiskCodes.LOW_CONFIDENCE_FIELDS, RiskSeverity.LOW, RiskSource.DOCUMENT,
                        "Key fields could not be read confidently", "핵심 필드 판독 신뢰도 낮음", weak.joinToString(", ") { it.replace('_', ' ') },
                    )
                }
            }
            if (doc.record.type == DocumentType.SELFIE_KTP) {
                a.field("face_count")?.value?.trim()?.toIntOrNull()?.takeIf { it != 1 }?.let { n ->
                    flags += RiskFlag(
                        RiskCodes.MULTIPLE_FACES, RiskSeverity.MEDIUM, RiskSource.DOCUMENT,
                        "Selfie does not show exactly one face", "셀피에 얼굴이 1명이 아님", "face_count=$n",
                    )
                }
                if (a.field("face_matches_ktp_photo")?.takeIf { it.usable }?.value?.trim()?.equals("no", ignoreCase = true) == true) {
                    flags += RiskFlag(
                        RiskCodes.SELFIE_FACE_MISMATCH, RiskSeverity.HIGH, RiskSource.DOCUMENT,
                        "Face in the selfie does not look like the photo on the held ID", "셀피 얼굴이 신분증 사진과 다름",
                        a.field("face_matches_ktp_photo")?.note ?: "model compared the face with the card photo",
                    )
                }
                val heldNik = a.field("nik_on_held_ktp")?.takeIf { it.usable }?.value?.filter { it.isDigit() }
                val extracted = app.extractedNik?.filter { it.isDigit() }
                if (!heldNik.isNullOrBlank() && !extracted.isNullOrBlank() && heldNik.length >= 6 && !extracted.endsWith(heldNik.takeLast(6))) {
                    flags += RiskFlag(
                        RiskCodes.SELFIE_ID_MISMATCH, RiskSeverity.HIGH, RiskSource.DOCUMENT,
                        "The ID held in the selfie is not the ID that was submitted", "셀피에 든 신분증과 제출된 신분증 번호가 다름",
                        "selfie ***${heldNik.takeLast(4)} vs document ***${extracted.takeLast(4)}",
                    )
                }
            }
        }
    }

    /** A superseded KTP whose confidently-read NIK differs from the final one: the customer switched identity between uploads. */
    private fun historyFlags(documents: List<AnalysedDocument>, app: DealerApplication, flags: MutableList<RiskFlag>) {
        val finalNik = app.extractedNik?.filter { it.isDigit() } ?: return
        val earlier = documents
            .filter { it.record.type == DocumentType.KTP && it.record.superseded }
            .mapNotNull { it.analysis?.usableValue("nik")?.filter { c -> c.isDigit() } }
            .filter { it.length == 16 && it != finalNik }
            .distinct()
        if (earlier.isNotEmpty()) {
            flags += RiskFlag(
                RiskCodes.ID_CHANGED_BETWEEN_UPLOADS, RiskSeverity.HIGH, RiskSource.DOCUMENT,
                "A different ID number was read on an earlier upload", "재업로드 사이에 신분증 번호가 바뀜",
                "earlier ${earlier.joinToString { "***" + it.takeLast(4) }} vs final ***${finalNik.takeLast(4)}",
            )
        }
    }

    /**
     * The same ID number on another application: a different person (name or phone differs) is identity misuse, the
     * same person is a duplicate application. The same phone with a different ID number is the mirror case.
     */
    private fun crossApplicationFlags(app: DealerApplication, related: RelatedApplications, flags: MutableList<RiskFlag>) {
        val nik = app.extractedNik?.filter { it.isDigit() }
        val others = related.sameIdNumber.filter { it.id != app.id && it.applicationNumber != app.applicationNumber }
        if (!nik.isNullOrBlank() && others.isNotEmpty()) {
            val (samePerson, otherPeople) = others.partition { samePerson(app, it) }
            if (otherPeople.isNotEmpty()) {
                flags += RiskFlag(
                    RiskCodes.SHARED_ID_NUMBER, RiskSeverity.HIGH, RiskSource.CROSS_CHECK,
                    "The same ID number was submitted on another application by a different person", "동일 신분증 번호가 다른 사람의 신청에도 제출됨",
                    "NIK ***${nik.takeLast(4)} also on " + otherPeople.joinToString { describe(it) },
                )
            }
            if (samePerson.isNotEmpty()) {
                flags += RiskFlag(
                    RiskCodes.DUPLICATE_APPLICATION, RiskSeverity.MEDIUM, RiskSource.CROSS_CHECK,
                    "The same customer already has another application", "동일 고객의 중복 신청",
                    "NIK ***${nik.takeLast(4)} also on " + samePerson.joinToString { describe(it) },
                )
            }
        }
        val phone = app.customerPhone?.filter { it.isDigit() }
        val otherIds = related.samePhone
            .filter { it.id != app.id && it.applicationNumber != app.applicationNumber }
            .filter { o -> val n = o.extractedNik?.filter { it.isDigit() }; !n.isNullOrBlank() && n != nik }
        if (!phone.isNullOrBlank() && !nik.isNullOrBlank() && otherIds.isNotEmpty()) {
            flags += RiskFlag(
                RiskCodes.PHONE_USED_WITH_OTHER_ID, RiskSeverity.HIGH, RiskSource.CROSS_CHECK,
                "This phone number submitted a different ID number on another application", "같은 전화번호로 다른 신분증 번호가 제출됨",
                "phone ***${phone.takeLast(4)}: " + otherIds.joinToString { "${it.applicationNumber} (NIK ***${it.extractedNik!!.filter { c -> c.isDigit() }.takeLast(4)})" },
            )
        }
    }

    private fun samePerson(a: DealerApplication, b: DealerApplication): Boolean {
        val phoneA = a.customerPhone?.filter { it.isDigit() }
        val phoneB = b.customerPhone?.filter { it.isDigit() }
        val nameA = (a.extractedName ?: a.customerName)?.trim()?.uppercase()
        val nameB = (b.extractedName ?: b.customerName)?.trim()?.uppercase()
        val samePhone = !phoneA.isNullOrBlank() && phoneA == phoneB
        val sameName = nameA.isNullOrBlank() || nameB.isNullOrBlank() || nameA == nameB
        return samePhone && sameName
    }

    private fun describe(o: DealerApplication): String =
        "${o.applicationNumber} (${o.status.name.lowercase().replace('_', ' ')}, ${(o.extractedName ?: o.customerName)?.let { "name ${it.trim().take(20)}" } ?: "no name"}, phone ***${o.customerPhone?.takeLast(4) ?: "?"})"

    /** NIK province (first two digits) against the PROVINSI header the model read; null when either side is missing or they agree. */
    private fun regionMismatch(a: DocumentAnalysis): Pair<String, String>? {
        val encoded = a.nik?.provinceName ?: return null
        val printed = a.field("province")?.takeIf { it.usable }?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val norm = { s: String -> s.uppercase().replace(Regex("^PROVINSI\\s+"), "").replace(Regex("[^A-Z]"), "") }
        val e = norm(encoded); val p = norm(printed)
        if (e.isEmpty() || p.isEmpty() || e == p || e.contains(p) || p.contains(e) || sharesKeyword(e, p)) return null
        return encoded to printed
    }

    /** "SUMATERA UTARA" vs "SUMUT"-style abbreviations are rare on cards; the distinctive word is enough to say the regions agree. */
    private fun sharesKeyword(a: String, b: String): Boolean = NikValidator.PROVINCES.values
        .map { it.uppercase().replace(Regex("[^A-Z ]"), "") }
        .flatMap { it.split(' ') }.filter { it.length >= 5 && it !in setOf("JAWA", "PAPUA", "MALUKU", "SULAWESI", "KALIMANTAN", "SUMATERA", "KEPULAUAN", "NUSA", "TENGGARA", "BARAT", "TIMUR", "UTARA", "SELATAN", "TENGAH") }
        .any { a.contains(it) && b.contains(it) }

    private fun identityFlags(app: DealerApplication, flags: MutableList<RiskFlag>) {
        when (app.idVerificationStatus) {
            ProviderOutcome.MISMATCH -> flags += RiskFlag(
                RiskCodes.IDENTITY_MISMATCH, RiskSeverity.HIGH, RiskSource.IDENTITY,
                "Registry data does not match the document", "신원 조회 결과 불일치", app.idVerificationDetail ?: "mismatch",
            )
            ProviderOutcome.NOT_FOUND -> flags += RiskFlag(
                RiskCodes.IDENTITY_NOT_FOUND, RiskSeverity.HIGH, RiskSource.IDENTITY,
                "ID number is not in the registry", "신원 조회에서 미등록", app.idVerificationDetail ?: "not found",
            )
            ProviderOutcome.TIMEOUT -> flags += RiskFlag(
                RiskCodes.IDENTITY_UNVERIFIED, RiskSeverity.MEDIUM, RiskSource.IDENTITY,
                "Identity could not be verified (registry timeout)", "신원 미확인 (조회 시간 초과)", app.idVerificationDetail ?: "timeout",
            )
            else -> Unit
        }
    }

    private fun creditFlags(app: DealerApplication, flags: MutableList<RiskFlag>) {
        when (app.creditCheckStatus) {
            ProviderOutcome.MISMATCH -> flags += RiskFlag(
                RiskCodes.CREDIT_IDENTITY_MISMATCH, RiskSeverity.MEDIUM, RiskSource.CREDIT,
                "Bureau record does not match the identity", "신용 조회 신원 불일치", app.creditCheckDetail ?: "mismatch",
            )
            ProviderOutcome.NOT_FOUND -> flags += RiskFlag(
                RiskCodes.NO_CREDIT_HISTORY, RiskSeverity.LOW, RiskSource.CREDIT,
                "No credit history", "신용 기록 없음", app.creditCheckDetail ?: "not found",
            )
            ProviderOutcome.TIMEOUT -> flags += RiskFlag(
                RiskCodes.CREDIT_UNVERIFIED, RiskSeverity.LOW, RiskSource.CREDIT,
                "Credit could not be checked (bureau timeout)", "신용 미확인 (조회 시간 초과)", app.creditCheckDetail ?: "timeout",
            )
            ProviderOutcome.MATCH -> app.creditScore?.takeIf { it < LOW_SCORE_THRESHOLD }?.let { score ->
                flags += RiskFlag(
                    RiskCodes.LOW_CREDIT_SCORE, RiskSeverity.MEDIUM, RiskSource.CREDIT,
                    "Credit score below $LOW_SCORE_THRESHOLD", "신용 점수 기준 미달 ($LOW_SCORE_THRESHOLD 미만)", "score=$score",
                )
            }
            null -> Unit
        }
    }

    private fun behaviourFlags(documents: List<AnalysedDocument>, app: DealerApplication, flags: MutableList<RiskFlag>, now: Instant) {
        if (app.reviewRounds >= 2) {
            flags += RiskFlag(
                RiskCodes.REPEATED_RESUBMISSION, RiskSeverity.MEDIUM, RiskSource.BEHAVIOUR,
                "Customer was sent back for documents repeatedly", "서류 재요청 반복", "review_rounds=${app.reviewRounds}",
            )
        }
        val failed = documents.count { it.record.checkPassed == false }
        if (failed >= 3) {
            flags += RiskFlag(
                RiskCodes.MANY_FAILED_UPLOADS, RiskSeverity.LOW, RiskSource.BEHAVIOUR,
                "Several uploads failed the document check", "서류 검사 반복 실패", "failed_uploads=$failed",
            )
        }
        val dob = app.extractedDob?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        val age = Period.between(dob, now.atZone(ZoneOffset.UTC).toLocalDate()).years
        if (age < MIN_AGE || age > MAX_AGE) {
            flags += RiskFlag(
                RiskCodes.AGE_OUT_OF_RANGE, RiskSeverity.MEDIUM, RiskSource.BEHAVIOUR,
                "Applicant age outside $MIN_AGE-$MAX_AGE", "신청자 연령 범위 밖 (${MIN_AGE}-${MAX_AGE}세)", "age=$age, dob=$dob",
            )
        }
    }

    private fun badges(app: DealerApplication, dealer: Dealer?, flags: List<RiskFlag>, grade: RiskGrade): List<CustomerBadge> {
        val badges = mutableListOf<CustomerBadge>()
        val identityOk = app.idVerificationStatus == ProviderOutcome.MATCH
        val noFraud = flags.none { it.fraudSignal }
        if (identityOk && noFraud) badges += CustomerBadge.VERIFIED_IDENTITY
        if (identityOk && noFraud && grade == RiskGrade.A && app.creditCheckStatus == ProviderOutcome.MATCH && (app.creditScore ?: 0) >= STRONG_SCORE_THRESHOLD) {
            badges += CustomerBadge.FINANCIAL_STABILITY
        }
        if (dealer?.tier == DealerTier.EXCELLENT) badges += CustomerBadge.EXCELLENT_DEALER_REFERRAL
        return badges
    }
}
