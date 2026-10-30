package com.nextasean.wawebhook.review

import com.nextasean.wawebhook.documents.analysis.FieldStatus
import com.nextasean.wawebhook.documents.analysis.QualityIssueType
import com.nextasean.wawebhook.documents.analysis.Severity
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Plain-text one-page summary built from the facts alone; the model writes a better one when it is available. */
object SummaryTemplate {

    private val stamp = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm").withZone(ZoneId.of("Asia/Jakarta"))

    fun render(ctx: ReviewContext, officerReview: Boolean): String = buildString {
        val app = ctx.application
        appendLine("APPLICATION ${app.applicationNumber} - DOCUMENT INTAKE SUMMARY")
        appendLine("Customer: ${app.customerName ?: "unknown"} (+${app.customerPhone ?: "?"}) | Dealer id ${app.dealerId} | Created ${stamp.format(app.createdAt)}")
        appendLine("Decision: ${if (officerReview) "OFFICER REVIEW REQUIRED" else "COMPLETE - no manual review items"} | Document rounds: ${ctx.round + 1}")
        appendLine()
        appendLine("DOCUMENTS")
        for (doc in ctx.documents) {
            val a = doc.analysis
            val line = when {
                !doc.received -> "not received"
                a == null -> "received (no automated analysis)"
                else -> "received | seen as ${a.documentType.label} (${pct(a.documentTypeConfidence)}) | " +
                    (if (a.qualityIssues.isEmpty()) "no quality issues" else a.qualityIssues.joinToString("; ") { "${it.type} ${it.severity}: ${it.detail}" })
            }
            appendLine("- ${doc.type.label}: $line")
        }
        val ktp = ctx.documents.firstOrNull { it.type.name == "KTP" }?.analysis
        if (ktp != null) {
            appendLine()
            appendLine("EXTRACTED DATA (KTP)")
            for (f in ktp.fields) {
                val value = when (f.status) {
                    FieldStatus.EXTRACTED -> f.value
                    FieldStatus.NEEDS_OFFICER_REVIEW -> "${f.value} [NEEDS OFFICER REVIEW]"
                    FieldStatus.NOT_VISIBLE -> "[not visible]"
                }
                appendLine("- ${f.name.replace('_', ' ')}: $value (${pct(f.confidence)})${f.note?.let { " - $it" } ?: ""}")
            }
            ktp.nik?.let { n ->
                appendLine()
                appendLine("NIK VALIDATION: ${if (n.valid) "PASSED" else "FAILED"}")
                appendLine("- Region: ${n.provinceName ?: "unknown province ${n.provinceCode}"} / regency ${n.regencyCode} / district ${n.districtCode}")
                appendLine("- Encoded date of birth: ${n.dobFromNik ?: "invalid"} | gender: ${n.genderFromNik ?: "n/a"} | card: ${n.statedDob ?: "n/a"} / ${n.statedGender ?: "n/a"}")
                n.issues.forEach { appendLine("- Issue: $it") }
            }
        }
        val reasons = ctx.documents.flatMap { d -> d.analysis?.reviewReasons.orEmpty().map { "${d.type.label}: $it" } } +
            ctx.documents.filter { it.record?.needsOfficerReview == true && it.analysis == null }.map { "${it.type.label}: automated analysis was unavailable" }
        appendLine()
        appendLine("ITEMS FOR OFFICER REVIEW")
        if (reasons.isEmpty()) appendLine("- none") else reasons.forEach { appendLine("- $it") }
        appendLine()
        append("NEXT STEP: ${if (officerReview) "officer to verify the flagged items against the images before verification" else "proceed with identity and credit verification"}")
    }

    /** The same one-page summary in Korean, for the back office in Korean mode. */
    fun renderKo(ctx: ReviewContext, officerReview: Boolean): String = buildString {
        val app = ctx.application
        appendLine("신청 ${app.applicationNumber} - 서류 접수 요약")
        appendLine("고객: ${app.customerName ?: "미상"} (+${app.customerPhone ?: "?"}) | 딜러 ID ${app.dealerId} | 생성 ${stamp.format(app.createdAt)}")
        appendLine("판단: ${if (officerReview) "담당자 검토 필요" else "완료 - 수동 검토 항목 없음"} | 서류 라운드: ${ctx.round + 1}")
        appendLine()
        appendLine("서류")
        for (doc in ctx.documents) {
            val a = doc.analysis
            val line = when {
                !doc.received -> "미접수"
                a == null -> "접수됨 (자동 분석 없음)"
                else -> "접수됨 | 인식 결과 ${docKo(a.documentType.label)} (${pct(a.documentTypeConfidence)}) | " +
                    (if (a.qualityIssues.isEmpty()) "품질 문제 없음" else a.qualityIssues.joinToString("; ") { "${issueKo(it.type)} ${sevKo(it.severity)}: ${it.detail}" })
            }
            appendLine("- ${docKo(doc.type.label)}: $line")
        }
        val ktp = ctx.documents.firstOrNull { it.type.name == "KTP" }?.analysis
        if (ktp != null) {
            appendLine()
            appendLine("추출 데이터 (KTP)")
            for (f in ktp.fields) {
                val value = when (f.status) {
                    FieldStatus.EXTRACTED -> f.value
                    FieldStatus.NEEDS_OFFICER_REVIEW -> "${f.value} [담당자 검토 필요]"
                    FieldStatus.NOT_VISIBLE -> "[판독 불가]"
                }
                appendLine("- ${fieldKo(f.name)}: $value (${pct(f.confidence)})${f.note?.let { " - $it" } ?: ""}")
            }
            ktp.nik?.let { n ->
                appendLine()
                appendLine("NIK 검증: ${if (n.valid) "통과" else "실패"}")
                appendLine("- 지역: ${n.provinceName ?: "알 수 없는 주 ${n.provinceCode}"} / 군·시 ${n.regencyCode} / 구 ${n.districtCode}")
                appendLine("- NIK에 담긴 생년월일: ${n.dobFromNik ?: "유효하지 않음"} | 성별: ${n.genderFromNik ?: "해당 없음"} | 카드 표기: ${n.statedDob ?: "해당 없음"} / ${n.statedGender ?: "해당 없음"}")
                n.issues.forEach { appendLine("- 문제: $it") }
            }
        }
        val reasons = ctx.documents.flatMap { d -> d.analysis?.reviewReasons.orEmpty().map { "${docKo(d.type.label)}: $it" } } +
            ctx.documents.filter { it.record?.needsOfficerReview == true && it.analysis == null }.map { "${docKo(it.type.label)}: 자동 분석을 사용할 수 없었음" }
        appendLine()
        appendLine("담당자 검토 항목")
        if (reasons.isEmpty()) appendLine("- 없음") else reasons.forEach { appendLine("- $it") }
        appendLine()
        append("다음 단계: ${if (officerReview) "담당자가 표시된 항목을 이미지와 대조한 뒤 검증 진행" else "신원·신용 검증 진행"}")
    }

    private fun pct(v: Double) = "${(v * 100).toInt()}%"

    private fun docKo(label: String) = when (label) {
        "KTP" -> "KTP(주민증)"
        "Selfie with KTP" -> "KTP를 든 셀피"
        else -> label
    }

    private fun issueKo(type: QualityIssueType) = when (type) {
        QualityIssueType.BLUR -> "흐림"
        QualityIssueType.GLARE -> "빛 반사"
        QualityIssueType.CROPPED -> "잘림"
        QualityIssueType.TOO_DARK -> "너무 어두움"
        QualityIssueType.OBSTRUCTED -> "가려짐"
        QualityIssueType.LOW_RESOLUTION -> "저해상도"
        QualityIssueType.SCREEN_PHOTO -> "화면·복사본 촬영"
        QualityIssueType.OTHER -> "기타"
    }

    private fun sevKo(s: Severity) = when (s) {
        Severity.LOW -> "낮음"
        Severity.MEDIUM -> "중간"
        Severity.HIGH -> "높음"
    }

    private fun fieldKo(name: String) = when (name) {
        "nik" -> "NIK"
        "name" -> "이름"
        "date_of_birth" -> "생년월일"
        "place_of_birth" -> "출생지"
        "gender" -> "성별"
        "address" -> "주소"
        "religion" -> "종교"
        "marital_status" -> "혼인 상태"
        "occupation" -> "직업"
        "nationality" -> "국적"
        "valid_until" -> "유효기간"
        else -> name.replace('_', ' ')
    }
}
