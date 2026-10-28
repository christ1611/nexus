package com.nextasean.wawebhook.review

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.application.StageProgressService
import com.nextasean.wawebhook.config.AppProperties
import com.nextasean.wawebhook.config.ClaudeProperties
import com.nextasean.wawebhook.dealer.DealerRepository
import com.nextasean.wawebhook.documents.DocumentRecord
import com.nextasean.wawebhook.documents.DocumentRepository
import com.nextasean.wawebhook.risk.RiskAssessment
import com.nextasean.wawebhook.risk.RiskAssessmentService
import com.nextasean.wawebhook.risk.RiskGrade
import com.nextasean.wawebhook.risk.RiskSeverity
import com.nextasean.wawebhook.trace.AgentTraceService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.jvm.optionals.getOrNull

/**
 * The reviewer's copilot: answers questions about one application (why this score, what the fraud flags mean,
 * what to ask the customer for, approve or not) from a case file built out of everything the back office stores.
 *
 * With `app.document-check=claude` the questions go to Claude with the case file as a cached system block. Without
 * an API client (stub demo) a rule-based answerer explains the stored assessment, so the panel always works.
 */
@Service
class ReviewCopilotService(
    private val client: ObjectProvider<AnthropicClient>,
    private val props: ClaudeProperties,
    private val appProps: AppProperties,
    private val documents: DocumentRepository,
    private val dealers: DealerRepository,
    private val risk: RiskAssessmentService,
    private val trace: AgentTraceService,
    private val audit: ReviewAuditService,
    private val stages: StageProgressService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Turn(val role: String = "user", val content: String = "")

    data class Answer(
        val answer: String,
        /** `claude` or `rules`. */
        val mode: String,
        val model: String?,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val durationMs: Long,
        /** Name shown on the answer (app.copilot-label) and whether the engine behind it is disclosed in the UI. */
        val label: String = "NEXUS AI",
        val showEngine: Boolean = false,
    )

    val enabled: Boolean get() = client.ifAvailable != null

    fun ask(app: DealerApplication, question: String, history: List<Turn>, reviewer: String?): Answer {
        val assessment = risk.stored(app)
        val started = System.currentTimeMillis()
        val c = client.ifAvailable
        if (c == null) {
            val text = RulesCopilot.answer(app, assessment, question)
            return Answer(text, "rules", null, durationMs = System.currentTimeMillis() - started, label = appProps.copilotLabel, showEngine = appProps.copilotShowEngine)
        }
        val caseFile = caseFile(app, assessment)
        val system = listOf(
            TextBlockParam.builder().text(SYSTEM).cacheControl(CacheControlEphemeral.builder().build()).build(),
            TextBlockParam.builder().text("# Case file\n\n$caseFile").cacheControl(CacheControlEphemeral.builder().build()).build(),
        )
        val messages = copilotConversation(history, question, reviewer, MAX_HISTORY)
        val params = MessageCreateParams.builder()
            .model(props.model)
            .maxTokens(4_000L)
            .systemOfTextBlockParams(system)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(EFFORT)).build())
            .messages(messages)
            .build()
        val response = c.messages().create(params)
        val ms = System.currentTimeMillis() - started
        val stop = response.stopReason().getOrNull()
        log.info(
            "Review copilot | application={} | stop={} | in={} | out={} | {}ms",
            app.applicationNumber, stop?.asString(), response.usage().inputTokens(), response.usage().outputTokens(), ms,
        )
        val text = when (stop) {
            StopReason.REFUSAL -> "이 질문에는 답변할 수 없습니다 (모델 안전 정책). 질문을 바꿔서 다시 시도해 주세요."
            else -> response.content().mapNotNull { it.text().getOrNull()?.text() }.joinToString("\n").trim()
                .ifBlank { "답변을 생성하지 못했습니다. 다시 시도해 주세요." }
        }
        return Answer(text, "claude", response.model().asString(), response.usage().inputTokens(), response.usage().outputTokens(), ms, appProps.copilotLabel, appProps.copilotShowEngine)
    }

    /** Everything the reviewer can see on the screen, as text. ID numbers are masked to their last four digits. */
    fun caseFile(app: DealerApplication, assessment: RiskAssessment? = risk.stored(app)): String = buildString {
        val dealer = app.dealerId.let { dealers.findById(it).orElse(null) }
        val docs = app.id?.let { documents.findByApplicationIdOrderByCreatedAtAsc(it) }.orEmpty()
        val progress = stages.forApplication(app)
        appendLine("## Application")
        appendLine("- Number: ${app.applicationNumber}")
        appendLine("- Status: ${app.status.name} (${app.status.label})")
        appendLine("- Country: ${app.country}")
        appendLine("- Dealer: ${dealer?.dealerCode ?: "?"} ${dealer?.name ?: ""} (tier ${dealer?.tier?.name ?: "?"})")
        appendLine("- Created: ${fmt(app.createdAt)} · last update: ${fmt(app.updatedAt)}")
        appendLine("- Customer: ${app.extractedName ?: app.customerName ?: "(not linked)"}${app.customerPhone?.let { " · phone ${mask(it)}" } ?: ""}")
        if (app.extractedNik != null) appendLine("- Extracted from the ID card: NIK ${mask(app.extractedNik)}, name ${app.extractedName}, date of birth ${app.extractedDob}, address ${app.extractedAddress}")
        appendLine("- Pipeline stage now: ${progress.currentKo} (${progress.done}/${progress.total} stages passed)")
        progress.stages.filter { it.note != null }.forEach { appendLine("  - ${it.labelKo}: ${it.state.name}${it.note?.let { n -> " · $n" } ?: ""}") }
        if (app.resubmissionReason != null) appendLine("- Resubmission reason: ${app.resubmissionReason}")
        if (app.status.isFinal) appendLine("- Decision: ${app.status.name} by ${app.decidedBy ?: "?"} at ${fmt(app.decidedAt)}${app.decisionNote?.let { " · note: $it" } ?: ""}")

        appendLine()
        appendLine("## Scoring rules")
        appendLine("- Score = sum of the points of every risk flag: LOW ${RiskSeverity.LOW.points}, MEDIUM ${RiskSeverity.MEDIUM.points}, HIGH ${RiskSeverity.HIGH.points}; capped at 100.")
        appendLine("- Grade thresholds: " + RiskGrade.entries.joinToString(", ") { "${it.name} (${it.labelKo} · ${it.label}) from ${it.minScore}" } + ".")
        appendLine("- Any HIGH-severity fraud/forgery signal floors the grade at C, whatever the score.")
        appendLine("- The officer makes the decision; the grade is advisory. C and D are high risk.")

        appendLine()
        appendLine("## Risk assessment")
        if (assessment == null) appendLine("- Not assessed yet (documents or checks incomplete).")
        else {
            appendLine("- Score ${assessment.score} / 100 → grade ${assessment.grade.name} (${assessment.grade.labelKo} · ${assessment.grade.label}) · method ${assessment.method.label} · assessed ${fmt(assessment.assessedAt)}")
            appendLine("- Fraud / forgery warnings: ${assessment.fraudFlags.size}")
            if (assessment.flags.isEmpty()) appendLine("- Flags: none")
            assessment.flags.forEach { f ->
                appendLine("- Flag ${f.code} · ${f.severity.name} (+${f.severity.points}) · source ${f.source.label}${if (f.fraudSignal) " · FRAUD SIGNAL" else ""}: ${f.title} (${f.titleKo}) — ${f.detail}")
            }
            assessment.badges.forEach { b -> appendLine("- Badge ${b.name}: ${b.label} (${b.labelKo}) — ${b.description}") }
        }

        appendLine()
        appendLine("## Provider lookups")
        if (app.verifiedAt == null) appendLine("- Not run${if (app.riskAssessedAt != null) " (skipped: no confidently read ID number, officer must verify)" else ""}.")
        else {
            appendLine("- Identity registry: ${app.idVerificationStatus?.label ?: "?"}${app.idVerificationDetail?.let { " — $it" } ?: ""}")
            appendLine("- Credit bureau: ${app.creditCheckStatus?.label ?: "?"}${app.creditScore?.let { " · score $it" } ?: ""}${app.creditCheckDetail?.let { " — $it" } ?: ""}")
            appendLine("- Verified at ${fmt(app.verifiedAt)}")
        }

        appendLine()
        appendLine("## Review agent")
        if (app.reviewDecision == null) appendLine("- No agent review recorded.")
        else {
            appendLine("- Decision ${app.reviewDecision} · officer review required: ${app.officerReviewRequired ?: false} · document rounds ${app.reviewRounds} · reviewed ${fmt(app.reviewedAt)}")
            app.reviewSummary?.let { appendLine("- Summary:\n${it.trim().prependIndent("  ")}") }
        }

        appendLine()
        appendLine("## Documents (${docs.size})")
        if (docs.isEmpty()) appendLine("- None received.")
        docs.forEach { d -> appendLine("- " + describe(d)) }

        val steps = trace.steps(app.id)
        appendLine()
        appendLine("## AI agent timeline (${steps.size} steps)")
        steps.forEach { s ->
            appendLine("- ${fmt(s.createdAt)} · round ${s.round + 1} · ${s.kind.label} · ${s.status.name} · ${s.actor}: ${s.title}${s.detail?.let { " — ${it.take(240)}" } ?: ""}")
        }

        val events = audit.timeline(app)
        appendLine()
        appendLine("## Officer timeline (${events.size} events)")
        events.forEach { e -> appendLine("- ${fmt(e.createdAt)} · ${e.kind.label}${e.actor?.let { " · $it" } ?: ""}${e.detail?.let { " — ${it.take(240)}" } ?: ""}") }
    }

    private fun describe(d: DocumentRecord): String = buildString {
        append("${d.type.label} · received ${fmt(d.createdAt)} · check ${when (d.checkPassed) { true -> "passed"; false -> "failed"; null -> "pending" }}")
        d.checkReason?.let { append(" ($it)") }
        d.detectedType?.let { append(" · seen as $it") }
        if (d.needsOfficerReview == true) append(" · needs officer review")
        if (d.superseded) append(" · superseded by a later upload")
        d.analysisJson?.let { append("\n  analysis: ${it.take(MAX_ANALYSIS_CHARS)}${if (it.length > MAX_ANALYSIS_CHARS) "…" else ""}") }
    }

    private fun fmt(i: Instant?): String = i?.let { FMT.format(it) } ?: "–"
    private fun mask(v: String): String = if (v.length <= 4) "****" else "*".repeat(v.length - 4) + v.takeLast(4)

    companion object {
        const val MAX_HISTORY = 12
        const val MAX_ANALYSIS_CHARS = 1_200
        const val EFFORT = "medium"
        private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Jakarta"))

        val SYSTEM = """
            You are the review copilot inside the NEXT ASEAN onboarding back office of JB Financial Group's ASEAN
            subsidiaries. A credit reviewing officer (심사원) is looking at one customer application and asks you
            questions about it. The case file in the next system block is everything the back office knows.

            Rules:
            - Ground every statement in the case file. Quote the flag codes, points, lookup results and timeline
              entries you rely on. If the case file does not contain something, say that it is not available instead
              of guessing. Never invent documents, lookups or numbers.
            - Explain the score with the scoring rules: list the flags with their points, show the sum and the cap,
              and say which threshold the grade comes from. Point out when a HIGH fraud signal floors the grade.
            - The officer decides; you advise. When asked whether to approve or reject, give options with the
              conditions under which each is reasonable, and name what should be checked first.
            - Treat forgery, tampering and cross-application duplicates as serious and say so plainly.
            - Answer in Korean by default; answer in English if the officer writes in English. Be concise: short
              paragraphs or bullet lists, no headings, no preamble. Keep ID numbers masked to their last 4 digits.
            - You only know this application. For anything else, say it is outside this application.
        """.trimIndent()
    }
}

/** Deterministic answers for the demo without an API key: explains the stored assessment by topic of the question. */
object RulesCopilot {
    private val RECOMMEND = Regex("승인|거절|추천|권고|결정|approve|reject|recommend|decision|should", RegexOption.IGNORE_CASE)
    private val FRAUD = Regex("위조|사기|변조|조작|fraud|forg|tamper|duplicate|중복", RegexOption.IGNORE_CASE)
    private val LOOKUP = Regex("조회|신원|신용|dukcapil|pefindo|credit|identity|registry|bureau", RegexOption.IGNORE_CASE)
    private val DOCS = Regex("서류|문서|사진|재요청|추가|document|photo|selfie|ktp|request", RegexOption.IGNORE_CASE)

    fun answer(app: DealerApplication, a: RiskAssessment?, question: String): String {
        if (a == null) return CopilotVoice.open(question, app.applicationNumber) + "아직 리스크 평가가 없습니다. 고객 서류와 신원·신용 조회가 끝나면 점수와 등급이 계산되고, 그때 근거를 바로 설명해 드릴게요." + CopilotVoice.close(question)
        val body = when {
            RECOMMEND.containsMatchIn(question) -> recommendation(app, a)
            FRAUD.containsMatchIn(question) -> fraud(a)
            LOOKUP.containsMatchIn(question) -> lookups(app)
            DOCS.containsMatchIn(question) -> documentsAdvice(app, a)
            else -> scoreBreakdown(a)
        }
        return CopilotVoice.open(question, app.applicationNumber) + body.trimEnd() + CopilotVoice.close(question)
    }

    fun scoreBreakdown(a: RiskAssessment): String = buildString {
        appendLine("**점수 ${a.score} / 100 → 등급 ${a.grade.name} (${a.grade.labelKo})**")
        if (a.flags.isEmpty()) appendLine("- Risk Flag가 없어 0점, 등급 A입니다.")
        else {
            appendLine("점수는 Risk Flag 심각도의 합입니다 (낮음 ${RiskSeverity.LOW.points} · 중간 ${RiskSeverity.MEDIUM.points} · 높음 ${RiskSeverity.HIGH.points}, 최대 100):")
            a.flags.forEach { f -> appendLine("- ${if (f.fraudSignal) "⚠ " else ""}${f.titleKo} (${f.code}) · ${f.severity.labelKo} +${f.severity.points} · ${f.source.labelKo}: ${f.detail}") }
            val raw = a.flags.sumOf { it.severity.points }
            appendLine("- 합계 $raw" + (if (raw > 100) " → 상한 100 적용" else "") + " → ${a.score}점")
        }
        appendLine("- 등급 기준: " + RiskGrade.entries.joinToString(" · ") { "${it.name} ${it.minScore}점 이상" })
        if (a.fraudFlags.any { it.severity == RiskSeverity.HIGH }) appendLine("- 심각도 '높음'의 위조·사기 신호가 있어 점수와 무관하게 최소 C 등급이 적용됩니다.")
        if (a.badges.isNotEmpty()) appendLine("- 배지: " + a.badges.joinToString(", ") { it.labelKo })
    }

    fun fraud(a: RiskAssessment): String = buildString {
        if (a.fraudFlags.isEmpty()) appendLine("위조·사기 신호는 없습니다. 모든 Flag는 데이터 품질·조회 결과·고객 행동에서 나온 것입니다.")
        else {
            appendLine("**위조·사기 경고 ${a.fraudFlags.size}건**")
            a.fraudFlags.forEach { f -> appendLine("- ${f.titleKo} (${f.code}) · ${f.severity.labelKo}: ${f.detail}") }
            appendLine("- 위조 의심은 재촬영으로 해소되지 않습니다. 원본 신분증 대면 확인 또는 거절을 검토하세요.")
        }
    }

    fun lookups(app: DealerApplication): String = buildString {
        if (app.verifiedAt == null) appendLine("신원·신용 조회가 아직 실행되지 않았습니다" + (if (app.riskAssessedAt != null) " (신분증 번호를 확신하지 못해 생략, 담당자 확인 필요)." else "."))
        else {
            appendLine("- 신원 조회: ${app.idVerificationStatus?.label ?: "?"}${app.idVerificationDetail?.let { " — $it" } ?: ""}")
            appendLine("- 신용 조회: ${app.creditCheckStatus?.label ?: "?"}${app.creditScore?.let { " · 점수 $it" } ?: ""}${app.creditCheckDetail?.let { " — $it" } ?: ""}")
            appendLine("- 'Data mismatch'는 신분증에서 읽은 이름·생년월일이 기관 기록과 다르다는 뜻입니다. 추출 데이터와 이미지를 대조하세요.")
        }
    }

    fun documentsAdvice(app: DealerApplication, a: RiskAssessment): String = buildString {
        val needs = a.flags.filter { it.source.name == "DOCUMENT" }
        if (needs.isEmpty()) appendLine("서류 품질·위조 관련 Flag가 없습니다. 추가 서류 요청 없이 조회 결과와 메모를 기준으로 판단할 수 있습니다.")
        else {
            appendLine("서류에서 나온 Flag:")
            needs.forEach { f -> appendLine("- ${f.titleKo}: ${f.detail}") }
            appendLine(if (needs.any { it.fraudSignal }) "- 위조 신호가 포함되어 있어 재촬영보다는 담당자 검토·원본 확인이 적절합니다." else "- 품질 문제라면 '고객 서류 재요청'으로 해당 서류를 다시 받을 수 있습니다.")
        }
        if (app.officerReviewRequired == true) appendLine("- 자동 분석이 일부 필드를 확신하지 못해 '담당자 검토 필요'로 표시되어 있습니다. 이미지와 대조해 주세요.")
    }

    fun recommendation(app: DealerApplication, a: RiskAssessment): String = buildString {
        appendLine("결정은 심사원이 합니다. 저장된 평가 기준으로 보면:")
        when (a.grade) {
            RiskGrade.A -> appendLine("- 등급 A (${a.score}점): 신원·신용 조회가 일치하고 위조 신호가 없으면 승인에 무리가 없습니다.")
            RiskGrade.B -> appendLine("- 등급 B (${a.score}점): 아래 Flag를 확인한 뒤 승인 가능합니다. 조회 불일치가 있으면 데이터 대조 후 결정하세요.")
            RiskGrade.C -> appendLine("- 등급 C (${a.score}점): 고위험입니다. 위조 신호가 있으면 거절 또는 원본 확인, 없으면 추가 확인 후 조건부 승인을 검토하세요.")
            RiskGrade.D -> appendLine("- 등급 D (${a.score}점): 위험 고객입니다. 거절을 우선 검토하고, 승인하려면 사유를 메모에 남기세요.")
        }
        if (a.flags.isNotEmpty()) appendLine("- 확인할 Flag: " + a.flags.joinToString(", ") { "${it.titleKo}(${it.severity.labelKo})" })
        if (app.officerReviewRequired == true) appendLine("- '담당자 검토 필요' 표시가 있으니 추출 데이터를 이미지와 대조하세요.")
    }

}
