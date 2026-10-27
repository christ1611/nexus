package com.nextasean.wawebhook.review

import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Font
import com.lowagie.text.PageSize
import com.lowagie.text.Paragraph
import com.lowagie.text.Phrase
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.BaseFont
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.config.AppProperties
import com.nextasean.wawebhook.dealer.DealerRepository
import com.nextasean.wawebhook.documents.DocumentRecord
import com.nextasean.wawebhook.documents.DocumentRepository
import com.nextasean.wawebhook.documents.analysis.DocumentAnalysisCodec
import com.nextasean.wawebhook.risk.FraudNetworkService
import com.nextasean.wawebhook.risk.RiskAssessment
import com.nextasean.wawebhook.risk.RiskAssessmentService
import com.nextasean.wawebhook.risk.RiskSeverity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The decision evidence report: one PDF per decision that puts the decision, the customer, the AI risk assessment
 * with every flag and its evidence, the provider lookups, the documents, the review agent's summary, the fraud-network
 * ties and the officer timeline on a page or two, for the local regulator or an audit. Generated and stored the moment
 * an officer approves or rejects (`<reports-dir>/<application number>/decision-report-<time>.pdf`); before a decision
 * the same layout can be rendered on demand as a draft. Labels are Korean with English next to them.
 */
@Service
class DecisionReportService(
    private val documents: DocumentRepository,
    private val dealers: DealerRepository,
    private val risk: RiskAssessmentService,
    private val audit: ReviewAuditService,
    private val codec: DocumentAnalysisCodec,
    private val network: FraudNetworkService,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Stored(val path: Path, val generatedAt: Instant) {
        val fileName: String get() = path.name
    }

    /** What the review page shows next to the decision: whether a stored report exists and when it was made. */
    data class Info(val available: Boolean, val generatedAt: Instant?, val fileName: String?, val url: String)

    private val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(props.zoneId)
    private val fileStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(props.zoneId)

    /** The first configured font that exists; null means the built-in Helvetica (Latin only) is used and other scripts are replaced. */
    private val baseFont: BaseFont? by lazy {
        props.reportFonts.firstNotNullOfOrNull { spec ->
            if (!Files.isRegularFile(Path.of(spec.substringBefore(',')))) null
            else runCatching { BaseFont.createFont(spec, BaseFont.IDENTITY_H, BaseFont.EMBEDDED) }
                .onFailure { log.warn("Report font {} could not be loaded: {}", spec, it.message) }
                .onSuccess { log.info("Decision reports use font {}", spec) }
                .getOrNull()
        } ?: run { log.warn("No Korean-capable font found for decision reports (tried {}); non-Latin text is replaced", props.reportFonts); null }
    }

    fun info(app: DealerApplication): Info {
        val s = stored(app)?.takeIf { app.status.isFinal }   // a leftover file for an undecided application is not a decision record
        return Info(s != null, s?.generatedAt, s?.fileName, "/admin/applications/${app.applicationNumber}/decision-report.pdf")
    }

    /** The latest stored report of this application, if any. */
    fun stored(app: DealerApplication): Stored? {
        val dir = Path.of(props.reportsDir).resolve(app.applicationNumber)
        if (!Files.isDirectory(dir)) return null
        val latest = runCatching { dir.listDirectoryEntries("decision-report-*.pdf").maxByOrNull { it.name } }.getOrNull() ?: return null
        val at = runCatching { Files.getLastModifiedTime(latest).toInstant() }.getOrDefault(Instant.now())
        return Stored(latest, at)
    }

    /** Renders and stores the report for a decided application. Failures are logged, never propagated: the decision stands without it. */
    fun generateAndStore(app: DealerApplication): Stored? = try {
        val dir = Files.createDirectories(Path.of(props.reportsDir).resolve(app.applicationNumber))
        val path = dir.resolve("decision-report-${fileStamp.format(app.decidedAt ?: Instant.now())}.pdf")
        Files.write(path, render(app, draft = false))
        log.info("Decision report stored | number={} | file={}", app.applicationNumber, path)
        Stored(path, Instant.now())
    } catch (e: Exception) {
        log.error("Decision report for {} could not be generated", app.applicationNumber, e)
        null
    }

    fun render(app: DealerApplication, draft: Boolean): ByteArray {
        val dealer = dealers.findById(app.dealerId).orElse(null)
        val assessment = risk.stored(app)
        val docs = app.id?.let(documents::findByApplicationIdOrderByCreatedAtAsc).orEmpty()
        val events = audit.timeline(app)
        val net = runCatching { network.network() }.getOrNull()
        val cluster = net?.clusterOf(app.applicationNumber)
        val ties = net?.tiesOf(app.applicationNumber).orEmpty()
        val generatedAt = Instant.now()
        val fingerprint = fingerprint(app, assessment, docs)
        sectionNo = 0

        val out = ByteArrayOutputStream()
        val doc = Document(PageSize.A4, 40f, 40f, 40f, 44f)
        PdfWriter.getInstance(doc, out)
        doc.addTitle("Decision evidence report ${app.applicationNumber}")
        doc.addAuthor("NEXT ASEAN onboarding back office")
        doc.open()

        // Header: brand, title, application number and report identity.
        val head = PdfPTable(floatArrayOf(3f, 2f)).also { it.widthPercentage = 100f }
        head.addCell(plain(Phrase().apply {
            add(chunk("JB금융그룹 NEXT ASEAN · WhatsApp 온보딩 백오피스\n", font(8f, color = GREY)))
            add(chunk("심사 근거 보고서\n", font(17f, Font.BOLD, BRAND)))
            add(chunk("Decision Evidence Report", font(11f, color = GREY)))
        }))
        head.addCell(plain(Phrase().apply {
            add(chunk("${app.applicationNumber}\n", font(15f, Font.BOLD)))
            add(chunk("${t("생성", "generated")} ${fmt.format(generatedAt)} (${props.timeZone})\n", font(8f, color = GREY)))
            add(chunk("${t("근거 지문", "evidence fingerprint")} $fingerprint", font(8f, color = GREY)))
        }, Element.ALIGN_RIGHT))
        doc.add(head)
        if (draft || !app.status.isFinal) {
            doc.add(Paragraph(txt("결정 전 임시본 · DRAFT: not yet a decision record"), font(10f, Font.BOLD, RED)).apply { spacingBefore = 6f })
        }

        // 1. Decision
        section(doc, "심사 결정", "Decision")
        val decisionText = when (app.status) {
            ApplicationStatus.APPROVED -> "승인 · APPROVED"
            ApplicationStatus.REJECTED -> "거절 · REJECTED"
            else -> "결정 전 · not decided (${app.status.label})"
        }
        kv(doc, listOf(
            "결정 · Decision" to decisionText,
            "결정자 · Decided by" to (app.decidedBy ?: "-"),
            "결정 시각 · Decided at" to (app.decidedAt?.let { fmt.format(it) } ?: "-"),
            "결정 메모 · Note" to (app.decisionNote ?: "-"),
            "국가 · Country" to app.country,
            "딜러 · Dealer" to listOfNotNull(dealer?.dealerCode, dealer?.name, dealer?.tier?.let { "${it.labelKo} · ${it.label}" }).joinToString(" · ").ifBlank { "-" },
            "신청 생성 · Created" to fmt.format(app.createdAt),
            "서류 라운드 · Document rounds" to app.reviewRounds.toString(),
        ))

        // 2. Customer
        section(doc, "고객", "Customer")
        kv(doc, listOf(
            "이름 · Name" to (app.extractedName ?: app.customerName ?: "-"),
            "신분증 번호 · ID number" to (app.extractedNik ?: "-"),
            "생년월일 · Date of birth" to (app.extractedDob ?: "-"),
            "주소 · Address" to (app.extractedAddress ?: "-"),
            "전화 · Phone" to (app.customerPhone?.let { "+$it" } ?: "-"),
        ))

        // 3. AI risk assessment
        section(doc, "AI 리스크 평가", "AI risk assessment")
        if (assessment == null) {
            doc.add(Paragraph(txt("리스크 평가 전 · Not assessed (documents or checks incomplete)"), font(9f, color = GREY)))
        } else {
            kv(doc, listOf(
                "등급 · Grade" to "${assessment.grade.name} · ${assessment.grade.labelKo} · ${assessment.grade.label}",
                "점수 · Score" to "${assessment.score} / 100",
                "평가 방식 · Method" to "${assessment.method.labelKo} · ${assessment.method.label}",
                "평가 시각 · Assessed at" to fmt.format(assessment.assessedAt),
                "위조·사기 경고 · Fraud warnings" to assessment.fraudFlags.size.toString(),
                "배지 · Badges" to assessment.badges.joinToString(", ") { "${it.labelKo} (${it.label})" }.ifBlank { "-" },
            ))
            if (assessment.flags.isEmpty()) {
                doc.add(Paragraph(txt("Risk Flag 없음 · No risk flags"), font(9f, color = GOOD)).apply { spacingBefore = 4f })
            } else {
                val table = table(floatArrayOf(1.1f, 3.2f, 1.3f, 4.4f), "심각도 · Severity", "Flag", "출처 · Source", "근거 · Evidence")
                assessment.flags.sortedByDescending { it.severity.points }.forEach { f ->
                    val sevColor = when (f.severity) { RiskSeverity.HIGH -> RED; RiskSeverity.MEDIUM -> ORANGE; RiskSeverity.LOW -> AMBER }
                    table.addCell(cell("${f.severity.labelKo} · ${f.severity.label} (+${f.severity.points})", font(8f, Font.BOLD, sevColor)))
                    table.addCell(cell("${if (f.fraudSignal) "⚠ " else ""}${f.titleKo}\n${f.title}\n[${f.code}]", font(8f)))
                    table.addCell(cell("${f.source.labelKo}\n${f.source.label}", font(8f)))
                    table.addCell(cell(f.detail, font(8f)))
                }
                doc.add(table)
                doc.add(Paragraph(txt("점수 = Flag 심각도 합계 (낮음 ${RiskSeverity.LOW.points} · 중간 ${RiskSeverity.MEDIUM.points} · 높음 ${RiskSeverity.HIGH.points}, 최대 100). 심각도 '높음'의 위조·사기 신호가 있으면 최소 C 등급. 등급은 코드의 규칙이 계산하며 모델이 직접 정하지 않습니다. " +
                    "Score = sum of flag severities, capped at 100; any HIGH fraud signal floors the grade at C; the grade is computed by rules, not by the model."), font(7.5f, color = GREY)).apply { spacingBefore = 3f })
            }
        }

        // 4. Provider lookups
        section(doc, "신원·신용 조회", "Identity & credit lookups")
        if (app.verifiedAt == null) {
            doc.add(Paragraph(txt(if (app.riskAssessedAt != null) "조회 생략: 신분증 번호를 확신 있게 읽지 못해 담당자 확인으로 넘김 · Skipped: ID number not read confidently, handed to the officer" else "조회 미실행 · Not run"), font(9f, color = GREY)))
        } else {
            kv(doc, listOf(
                "신원 조회 · Identity registry" to listOfNotNull(app.idVerificationStatus?.let { "${it.label}" }, app.idVerificationDetail).joinToString(" — ").ifBlank { "-" },
                "신용 조회 · Credit bureau" to listOfNotNull(app.creditCheckStatus?.label, app.creditScore?.let { "score $it" }, app.creditCheckDetail).joinToString(" — ").ifBlank { "-" },
                "조회 시각 · Checked at" to fmt.format(app.verifiedAt),
            ))
        }

        // 5. Documents
        section(doc, "서류", "Documents")
        if (docs.isEmpty()) doc.add(Paragraph(txt("접수된 서류 없음 · No documents received"), font(9f, color = GREY)))
        else {
            val table = table(floatArrayOf(2.2f, 2.1f, 1.6f, 2.3f, 2.6f, 1.2f), "서류 · Document", "접수 · Received", "검사 · Check", "감지 · Detected", "위조·품질 · Forgery / quality", "검토 · Review")
            docs.forEach { d ->
                val an = codec.fromJson(d.analysisJson)
                val check = when (d.checkPassed) { true -> "통과 · passed"; false -> "실패 · failed" + (d.checkReason?.let { " ($it)" } ?: ""); null -> "검사 중 · pending" }
                val issues = buildList {
                    an?.tamperSignals?.forEach { add("⚠ ${it.type.labelKo} (${it.severity})") }
                    an?.qualityIssues?.forEach { add("${it.type} (${it.severity})") }
                }
                table.addCell(cell("${d.type.label}${if (d.superseded) "\n(이전 제출본 · superseded)" else ""}", font(8f)))
                table.addCell(cell(fmt.format(d.createdAt), font(8f)))
                table.addCell(cell(check, font(8f, color = if (d.checkPassed == false) RED else Color.BLACK)))
                table.addCell(cell(an?.let { "${it.documentType.label} ${(it.documentTypeConfidence * 100).toInt()}%" } ?: (d.detectedType ?: "-"), font(8f)))
                table.addCell(cell(if (issues.isEmpty()) (if (an != null) "없음 · none" else "-") else issues.joinToString("\n"), font(8f, color = if (an?.tamperSignals?.isNotEmpty() == true) RED else Color.BLACK)))
                table.addCell(cell(if (d.needsOfficerReview == true) "담당자 · officer" else "-", font(8f)))
            }
            doc.add(table)
        }

        // 6. Review agent summary
        if (!app.reviewSummary.isNullOrBlank() || app.reviewDecision != null) {
            section(doc, "자동 리뷰 요약", "Review agent summary")
            doc.add(Paragraph(txt("${app.reviewDecision ?: "-"} · ${t("담당자 검토 필요", "officer review required")}: ${if (app.officerReviewRequired == true) "yes" else "no"}${app.reviewedAt?.let { " · ${fmt.format(it)}" } ?: ""}"), font(8f, color = GREY)))
            app.reviewSummary?.let { doc.add(Paragraph(txt(it.trim().take(1800) + if (it.length > 1800) "…" else ""), font(8.5f)).apply { spacingBefore = 3f }) }
        }

        // 7. Fraud network
        section(doc, "사기 연결망", "Fraud network")
        if (cluster == null) doc.add(Paragraph(txt("다른 신청과 공유된 신분증 번호·전화번호·이름 없음 · No ID number, phone or name shared with another application"), font(9f, color = GOOD)))
        else {
            doc.add(Paragraph(txt("묶음 ${cluster.id} · ${if (cluster.severity == "HIGH") "높음 · HIGH" else "중간 · MEDIUM"} · ${cluster.summaryKo} · ${cluster.summary}"), font(9f, Font.BOLD, if (cluster.severity == "HIGH") RED else ORANGE)))
            val table = table(floatArrayOf(2.2f, 2.4f, 2.4f, 3f), "연결 · Tie", "신청 · Application", "고객 · Customer", "상태 · Status")
            ties.forEach { (kind, other, detail) ->
                val (en, ko) = FraudNetworkService.KIND_LABELS[kind] ?: (kind to kind)
                table.addCell(cell("$ko · $en${detail?.let { "\n$it" } ?: ""}", font(8f)))
                table.addCell(cell("${other.number}\n${t("딜러", "dealer")} ${other.dealerCode ?: "-"}", font(8f)))
                table.addCell(cell(other.name ?: "-", font(8f)))
                table.addCell(cell("${other.status}${other.grade?.let { " · ${t("등급", "grade")} $it ${other.score ?: ""}" } ?: ""}", font(8f)))
            }
            doc.add(table)
        }

        // 8. Officer timeline
        section(doc, "심사 이력", "Officer timeline")
        if (events.isEmpty()) doc.add(Paragraph(txt("이력 없음 · No events"), font(9f, color = GREY)))
        else {
            val table = table(floatArrayOf(2f, 3f, 1.6f, 4.4f), "시각 · Time", "이벤트 · Event", "담당 · By", "내용 · Detail")
            events.forEach { e ->
                table.addCell(cell(fmt.format(e.createdAt), font(8f)))
                table.addCell(cell("${e.kind.labelKo}\n${e.kind.label}", font(8f)))
                table.addCell(cell(e.actor ?: "-", font(8f)))
                table.addCell(cell(e.detail ?: "-", font(8f)))
            }
            doc.add(table)
        }

        doc.add(Paragraph(txt("이 보고서는 NEXT ASEAN 온보딩 백오피스가 결정 시점의 저장 데이터로 자동 생성했습니다. 근거 지문(SHA-256 앞 16자리)은 신청번호·결정·점수·Flag·서류 식별자로 계산되며, 같은 데이터면 같은 값이 나옵니다. " +
            "Generated automatically by the NEXT ASEAN onboarding back office from the data stored at decision time. The evidence fingerprint (first 16 hex digits of SHA-256) is computed from the application number, decision, score, flags and document identifiers."), font(7f, color = GREY)).apply { spacingBefore = 14f })
        doc.close()
        return out.toByteArray()
    }

    // ---------- layout helpers ----------

    private val unicode: Boolean get() = baseFont != null
    private fun font(size: Float, style: Int = Font.NORMAL, color: Color = Color.BLACK): Font = baseFont?.let { Font(it, size, style, color) } ?: Font(Font.HELVETICA, size, style, color)
    /** Text for the chosen font: with the built-in font, characters outside Latin-1 are replaced so nothing silently disappears. */
    private fun txt(s: String?): String = (s ?: "-").let { v -> if (unicode) v else v.map { c -> if (c.code < 256) c else '?' }.joinToString("") }
    private fun chunk(s: String, f: Font) = com.lowagie.text.Chunk(txt(s), f)
    private fun t(ko: String, en: String) = "$ko · $en"

    private var sectionNo = 0
    private fun section(doc: Document, ko: String, en: String) {
        val no = ++sectionNo
        val table = PdfPTable(1).also { it.widthPercentage = 100f; it.setSpacingBefore(11f); it.setSpacingAfter(4f) }
        table.addCell(PdfPCell(Phrase().apply { add(chunk("$no. $ko ", font(11f, Font.BOLD, BRAND))); add(chunk(en, font(9f, color = GREY))) }).apply {
            border = Rectangle.BOTTOM; borderColor = BRAND; borderWidth = 1f; setPadding(2f); paddingBottom = 4f
        })
        doc.add(table)
    }

    private fun kv(doc: Document, rows: List<Pair<String, String>>) {
        val table = PdfPTable(floatArrayOf(1.6f, 4.4f)).also { it.widthPercentage = 100f }
        rows.forEach { (k, v) ->
            table.addCell(PdfPCell(Phrase(txt(k), font(8f, color = GREY))).apply { border = Rectangle.BOTTOM; borderColor = GRID; setPadding(3f); backgroundColor = TINT })
            table.addCell(PdfPCell(Phrase(txt(v), font(9f))).apply { border = Rectangle.BOTTOM; borderColor = GRID; setPadding(3f) })
        }
        doc.add(table)
    }

    private fun table(widths: FloatArray, vararg headers: String): PdfPTable {
        val table = PdfPTable(widths).also { it.widthPercentage = 100f; it.headerRows = 1; it.setSpacingBefore(4f) }
        headers.forEach { h -> table.addCell(PdfPCell(Phrase(txt(h), font(8f, Font.BOLD, Color.WHITE))).apply { backgroundColor = BRAND; border = Rectangle.NO_BORDER; setPadding(4f) }) }
        return table
    }

    private fun cell(text: String, f: Font) = PdfPCell(Phrase(txt(text), f)).apply { border = Rectangle.BOTTOM; borderColor = GRID; setPadding(3.5f) }
    private fun plain(phrase: Phrase, align: Int = Element.ALIGN_LEFT) = PdfPCell(phrase).apply { border = Rectangle.NO_BORDER; horizontalAlignment = align; setPadding(0f) }

    private fun fingerprint(app: DealerApplication, a: RiskAssessment?, docs: List<DocumentRecord>): String {
        val canonical = listOf(
            app.applicationNumber, app.status.name, app.decidedBy ?: "", app.decidedAt?.toString() ?: "", app.decisionNote ?: "",
            a?.grade?.name ?: "", a?.score?.toString() ?: "", a?.flags?.joinToString(",") { it.code } ?: "",
            app.idVerificationStatus?.name ?: "", app.creditCheckStatus?.name ?: "", app.creditScore?.toString() ?: "",
            docs.joinToString(",") { "${it.id}:${it.checkPassed}:${it.superseded}" },
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    private companion object {
        val BRAND = Color(0x0a, 0x31, 0xa8)
        val TINT = Color(0xf3, 0xf5, 0xf9)
        val GRID = Color(0xe1, 0xe5, 0xee)
        val GREY = Color(0x72, 0x81, 0x97)
        val RED = Color(0xd9, 0x30, 0x25)
        val ORANGE = Color(0xec, 0x7a, 0x3c)
        val AMBER = Color(0xb8, 0x86, 0x00)
        val GOOD = Color(0x0e, 0x9f, 0x6e)
    }
}
