package com.nextasean.wawebhook.admin

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.nextasean.wawebhook.admin.DashboardController.Metrics
import com.nextasean.wawebhook.admin.DashboardController.Stats
import com.nextasean.wawebhook.config.ClaudeProperties
import com.nextasean.wawebhook.review.ReviewCopilotService
import com.nextasean.wawebhook.review.ReviewCopilotService.Answer
import com.nextasean.wawebhook.review.ReviewCopilotService.Turn
import com.nextasean.wawebhook.review.copilotConversation
import com.nextasean.wawebhook.risk.RiskGrade
import com.nextasean.wawebhook.risk.RiskSeverity
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.jvm.optionals.getOrNull

/**
 * The dashboard's copilot: answers portfolio-level questions (trends, countries, dealers, risk and fraud, the review
 * backlog, what the AI agents did) from the same statistics the dashboard draws, written out as a portfolio file.
 *
 * With `app.document-check=claude` the questions go to Claude with the portfolio file as a cached system block.
 * Without an API client (stub demo) a rule-based answerer summarises the figures, so the panel always works.
 */
@Service
class DashboardCopilotService(
    private val client: ObjectProvider<AnthropicClient>,
    private val props: ClaudeProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** A question with the earlier turns of this conversation (oldest first). */
    data class Request(val question: String = "", val history: List<Turn> = emptyList(), val reviewer: String? = null)

    fun ask(stats: Stats, question: String, history: List<Turn>, reviewer: String?): Answer {
        val started = System.currentTimeMillis()
        val c = client.ifAvailable
        if (c == null) {
            return Answer(RulesDashboardCopilot.answer(stats, question), "rules", null, durationMs = System.currentTimeMillis() - started)
        }
        val system = listOf(
            TextBlockParam.builder().text(SYSTEM).cacheControl(CacheControlEphemeral.builder().build()).build(),
            TextBlockParam.builder().text("# Portfolio file\n\n${portfolioFile(stats)}").cacheControl(CacheControlEphemeral.builder().build()).build(),
        )
        val params = MessageCreateParams.builder()
            .model(props.model)
            .maxTokens(4_000L)
            .systemOfTextBlockParams(system)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(ReviewCopilotService.EFFORT)).build())
            .messages(copilotConversation(history, question, reviewer, ReviewCopilotService.MAX_HISTORY))
            .build()
        val response = c.messages().create(params)
        val ms = System.currentTimeMillis() - started
        val stop = response.stopReason().getOrNull()
        log.info("Dashboard copilot | stop={} | in={} | out={} | {}ms", stop?.asString(), response.usage().inputTokens(), response.usage().outputTokens(), ms)
        val text = when (stop) {
            StopReason.REFUSAL -> "이 질문에는 답변할 수 없습니다 (모델 안전 정책). 질문을 바꿔서 다시 시도해 주세요."
            else -> response.content().mapNotNull { it.text().getOrNull()?.text() }.joinToString("\n").trim()
                .ifBlank { "답변을 생성하지 못했습니다. 다시 시도해 주세요." }
        }
        return Answer(text, "claude", response.model().asString(), response.usage().inputTokens(), response.usage().outputTokens(), ms)
    }

    /** Everything the dashboard shows, as text. Customer names stay out; applications are referred to by number. */
    fun portfolioFile(s: Stats): String = buildString {
        val t = s.totals
        appendLine("## Snapshot")
        appendLine("- Generated: ${fmt(s.generatedAt)} · markets: ${s.countries.joinToString(", ")}")
        appendLine("- Applications ${t.applications} · dealers ${t.dealers} · in progress ${t.inProgress} · ready for review ${t.readyForReview} · documents requested ${t.documentsRequested} · approved ${t.approved} · rejected ${t.rejected}")
        appendLine("- Assessed ${t.assessed} · high risk (grade C/D) ${t.highRisk} · fraud/forgery warnings ${t.fraudWarnings} · officer review required ${t.officerReviewRequired}")
        appendLine("- Oldest application waiting for a decision: ${hours(t.oldestWaitingHours)} · average time to decision: ${hours(t.avgDecisionHours)}")
        if (t.demoApplications > 0) appendLine("- ${t.demoApplications} of the applications are demo seed data.")

        appendLine()
        appendLine("## Scoring rules")
        appendLine("- Risk score = sum of the points of every risk flag: LOW ${RiskSeverity.LOW.points}, MEDIUM ${RiskSeverity.MEDIUM.points}, HIGH ${RiskSeverity.HIGH.points}; capped at 100.")
        appendLine("- Grade thresholds: " + RiskGrade.entries.joinToString(", ") { "${it.name} (${it.labelKo} · ${it.label}) from ${it.minScore}" } + ". Any HIGH fraud signal floors the grade at C. C and D are high risk.")
        appendLine("- The officer decides each application; grades are advisory.")

        appendLine()
        appendLine("## Pipeline (open applications by current stage)")
        if (s.pipeline.isEmpty()) appendLine("- No open applications.")
        s.pipeline.forEach { appendLine("- ${it.label} (${it.labelKo}): ${it.count}") }

        appendLine()
        appendLine("## Onboarding funnel")
        s.funnel.forEach { appendLine("- ${it.stage} (${it.stageKo}): ${it.count}") }

        appendLine()
        appendLine("## Review queue (${s.reviewQueue.size}, highest risk first)")
        if (s.reviewQueue.isEmpty()) appendLine("- Empty.")
        s.reviewQueue.take(MAX_ROWS).forEach { q ->
            appendLine("- ${q.applicationNumber} · ${q.country} · dealer ${q.dealerCode ?: "?"} · ${q.status} · grade ${q.grade ?: "n/a"}${q.score?.let { " ($it pts)" } ?: ""} · fraud warnings ${q.fraudWarnings}${if (q.officerReviewRequired) " · officer review required" else ""} · waiting ${hours(q.waitingHours)}")
        }
        if (s.reviewQueue.size > MAX_ROWS) appendLine("- … ${s.reviewQueue.size - MAX_ROWS} more")

        appendLine()
        appendLine("## Weekly trend (ISO weeks, oldest first)")
        if (s.trend.isEmpty()) appendLine("- No data.")
        s.trend.forEach { w ->
            appendLine("- Week ${w.label} (from ${w.weekStart}): created ${w.created} · completed ${w.completed} · approved ${w.approved} · rejected ${w.rejected} · high risk ${w.highRisk} · fraud ${w.fraudWarnings} · created by country ${map(w.createdByCountry)} · high risk by country ${map(w.highRiskByCountry)} · created by dealer tier ${map(w.createdByTier)} · approved by tier ${map(w.approvedByTier)}")
        }

        appendLine()
        appendLine("## Countries")
        s.byCountry.forEach { c ->
            appendLine("- ${c.code} ${c.name} (${c.nameKo}) · ID document ${c.idDocument} · dealers ${c.dealers} · applications ${c.applications} · in progress ${c.inProgress} · ready for review ${c.readyForReview} · approved ${c.approved} · rejected ${c.rejected} · assessed ${c.assessed} · high risk ${c.highRisk} · fraud ${c.fraudWarnings} · identity match rate ${pct(c.identityMatchRate)} · average credit score ${num(c.avgCreditScore)} · grades ${map(c.grades)} · badges ${map(c.badges)}")
        }

        appendLine()
        appendLine("## Risk grades (assessed applications)")
        s.riskGrades.forEach { appendLine("- ${it.grade} ${it.label} (${it.labelKo}): ${it.count}") }
        appendLine()
        appendLine("## Risk flags (occurrences across all assessed applications)")
        if (s.flagCounts.isEmpty()) appendLine("- None yet.")
        s.flagCounts.forEach { appendLine("- ${it.code} · ${it.severity}${if (it.fraud) " · FRAUD SIGNAL" else ""}: ${it.title} (${it.titleKo}) × ${it.count}") }
        appendLine()
        appendLine("## Customer badges")
        if (s.badges.isEmpty()) appendLine("- None yet.")
        s.badges.forEach { appendLine("- ${it.code}: ${it.label} (${it.labelKo}) × ${it.count}") }

        appendLine()
        appendLine("## High-risk customers (grade C or D, ${s.highRiskApplications.size})")
        if (s.highRiskApplications.isEmpty()) appendLine("- None.")
        s.highRiskApplications.take(MAX_ROWS).forEach { r ->
            appendLine("- ${r.applicationNumber} · ${r.country} · dealer ${r.dealerCode ?: "?"} · ${r.status} · grade ${r.grade} (${r.score} pts, ${r.method}) · fraud warnings ${r.fraudWarnings} · flags ${r.flags.joinToString(", ") { "${it.code}/${it.severity}${if (it.fraud) "!" else ""}" }.ifBlank { "none" }} · badges ${r.badges.joinToString(", ").ifBlank { "none" }} · assessed ${fmt(r.assessedAt)}")
        }
        if (s.highRiskApplications.size > MAX_ROWS) appendLine("- … ${s.highRiskApplications.size - MAX_ROWS} more")

        appendLine()
        appendLine("## Dealer tiers")
        s.dealerTiers.forEach { appendLine("- ${it.tier} ${it.label} (${it.labelKo}) · ${it.dealers} dealers · ${metrics(it.metrics)}") }
        appendLine()
        appendLine("## Dealers (${s.dealers.size})")
        s.dealers.forEach { appendLine("- ${it.code} ${it.name} · ${it.country} · ${it.tier} · ${metrics(it.metrics)}") }

        appendLine()
        appendLine("## AI agent activity (all applications)")
        val a = s.agent
        appendLine("- Steps ${a.steps} · vision analyses ${a.visionAnalyses} (avg ${num(a.avgVisionMs)} ms) · agent turns ${a.agentTurns} · tool calls ${a.toolCalls} · documents requested by the agent ${a.documentsRequestedByAgent} · summaries written ${a.summariesWritten} · code guardrails ${a.guardrails} · fallbacks ${a.fallbacks} · customer retakes ${a.customerRetakes} · provider lookups ${a.providerLookups}")
        appendLine("- Steps by kind: ${map(a.byKind)}")

        appendLine()
        appendLine("## In progress (${s.inProgress.size} open applications)")
        if (s.inProgress.isEmpty()) appendLine("- None.")
        s.inProgress.take(MAX_ROWS).forEach { r ->
            appendLine("- ${r.applicationNumber} · ${r.country} · dealer ${r.dealerCode ?: "?"} · ${r.status} · stage ${r.current} (${r.currentKo}) ${r.done}/${r.total}${r.idleHours?.let { " · idle ${hours(it)}" } ?: ""}${r.note?.let { " · $it" } ?: ""}")
        }
        if (s.inProgress.size > MAX_ROWS) appendLine("- … ${s.inProgress.size - MAX_ROWS} more")
    }

    private fun metrics(m: Metrics): String =
        "applications ${m.applications} (open ${m.open}, last 30 days ${m.applications30d}) · approved ${m.approved} · rejected ${m.rejected} · approval rate ${pct(m.approvalRate)} · assessed ${m.assessed} · high risk ${m.highRisk} (${pct(m.highRiskRate)}) · fraud ${m.fraudWarnings} (${pct(m.fraudRate)}) · avg risk score ${num(m.avgRiskScore)} · avg credit score ${num(m.avgCreditScore)} · identity match ${pct(m.identityMatchRate)} · referral badges ${m.referralBadges} · last application ${fmt(m.lastApplicationAt)}"

    private fun map(m: Map<String, Int>): String = if (m.isEmpty()) "none" else m.entries.joinToString(", ") { "${it.key} ${it.value}" }
    private fun fmt(i: Instant?): String = i?.let { FMT.format(it) } ?: "–"

    companion object {
        const val MAX_QUESTION = 2_000
        const val MAX_ROWS = 25
        private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Jakarta"))

        fun pct(v: Double?): String = v?.let { "${Math.round(it * 100)}%" } ?: "n/a"
        fun num(v: Double?): String = v?.let { if (it == Math.rint(it)) it.toLong().toString() else "%.1f".format(it) } ?: "n/a"
        fun hours(h: Double?): String = when {
            h == null -> "n/a"
            h < 1 -> "${Math.round(h * 60)} min"
            h < 48 -> "%.1f h".format(h)
            else -> "%.1f days".format(h / 24)
        }

        val SYSTEM = """
            You are the dashboard copilot inside the NEXT ASEAN onboarding back office of JB Financial Group's ASEAN
            subsidiaries (Indonesia, Myanmar, Cambodia). Credit officers and managers look at the onboarding
            dashboard and ask you portfolio-level questions: trends, the review backlog, countries, dealers, risk
            grades, fraud warnings, and what the AI agents did. The portfolio file in the next system block is the
            exact data the dashboard shows right now.

            Rules:
            - Ground every statement in the portfolio file. Quote the numbers, flag codes and application numbers
              you rely on. If something is not in the file, say it is not available on the dashboard instead of
              guessing. Never invent figures.
            - Compare and rank when asked (countries, dealers, weeks, flags); show the figures behind a comparison.
            - For the backlog, name the application numbers to look at first and why (grade, fraud warnings,
              officer review required, waiting time). You cannot see documents or case files: for one application's
              evidence, point the officer to the review page for that application number.
            - Decisions are made by officers; you advise and summarise.
            - Answer in Korean by default; answer in English if the question is in English. Be concise: short
              paragraphs or bullet lists, no headings, no preamble.
        """.trimIndent()
    }
}

/** Deterministic answers for the demo without an API key: summarises the dashboard figures by topic of the question. */
object RulesDashboardCopilot {
    private val COUNTRY = Regex("국가|인도네시아|미얀마|캄보디아|시장|country|countries|market|indonesia|myanmar|cambodia|\\b(ID|MM|KH)\\b", RegexOption.IGNORE_CASE)
    private val DEALER = Regex("딜러|우수|dealer|excellent|tier|등급별 딜러", RegexOption.IGNORE_CASE)
    private val QUEUE = Regex("대기|큐|먼저|우선|backlog|queue|first|urgent|waiting|priority", RegexOption.IGNORE_CASE)
    private val TREND = Regex("추이|주간|이번 주|지난주|주별|trend|week|weekly|growth", RegexOption.IGNORE_CASE)
    private val AGENT = Regex("에이전트|자동화|자동|agent|automation|pipeline|vision|claude|AI", RegexOption.IGNORE_CASE)
    private val RISK = Regex("위조|사기|리스크|위험|등급|flag|플래그|배지|fraud|forg|risk|grade|badge|tamper|duplicate|중복", RegexOption.IGNORE_CASE)

    private val pct = DashboardCopilotService.Companion::pct
    private val num = DashboardCopilotService.Companion::num
    private val hours = DashboardCopilotService.Companion::hours

    fun answer(s: Stats, question: String): String {
        val head = "규칙 기반 답변입니다 (Claude API 키가 설정되지 않아 대시보드 통계만으로 요약합니다).\n\n"
        val body = when {
            QUEUE.containsMatchIn(question) -> queue(s)
            TREND.containsMatchIn(question) -> trend(s)
            COUNTRY.containsMatchIn(question) -> countries(s)
            DEALER.containsMatchIn(question) -> dealers(s)
            AGENT.containsMatchIn(question) -> agent(s)
            RISK.containsMatchIn(question) -> risk(s)
            else -> overview(s)
        }
        return head + body
    }

    fun overview(s: Stats): String = buildString {
        val t = s.totals
        appendLine("**전체 현황 (${s.countries.joinToString(" · ")})**")
        appendLine("- 총 신청 ${t.applications}건 · 진행 중 ${t.inProgress} · 심사 대기 ${t.readyForReview} · 서류 재요청 중 ${t.documentsRequested} · 승인 ${t.approved} · 거절 ${t.rejected}")
        appendLine("- 리스크 평가 ${t.assessed}건 중 고위험(C·D) ${t.highRisk}건, 위조·사기 경고 ${t.fraudWarnings}건, 담당자 검토 필요 ${t.officerReviewRequired}건")
        appendLine("- 최장 대기 ${hours(t.oldestWaitingHours)} · 평균 결정 시간 ${hours(t.avgDecisionHours)}")
        if (s.pipeline.isNotEmpty()) appendLine("- 진행 단계: " + s.pipeline.filter { it.count > 0 }.joinToString(", ") { "${it.labelKo} ${it.count}" }.ifBlank { "열린 신청 없음" })
        appendLine("- 국가별·딜러별·리스크·주간 추이·AI 에이전트 활동을 물어보면 해당 수치를 정리해 드립니다.")
    }

    fun countries(s: Stats): String = buildString {
        appendLine("**국가별 현황**")
        s.byCountry.forEach { c ->
            appendLine("- ${c.nameKo} (${c.code}): 신청 ${c.applications} · 진행 중 ${c.inProgress} · 심사 대기 ${c.readyForReview} · 승인 ${c.approved} · 거절 ${c.rejected} · 고위험 ${c.highRisk} · 위조 경고 ${c.fraudWarnings} · 신원 일치율 ${pct(c.identityMatchRate)} · 평균 신용점수 ${num(c.avgCreditScore)} · 딜러 ${c.dealers}")
        }
        val worst = s.byCountry.filter { it.assessed > 0 }.maxByOrNull { it.highRisk.toDouble() / it.assessed }
        if (worst != null) appendLine("- 평가 대비 고위험 비율이 가장 높은 시장: ${worst.nameKo} (${worst.highRisk}/${worst.assessed}).")
    }

    fun dealers(s: Stats): String = buildString {
        appendLine("**우수 딜러 vs 일반 딜러**")
        s.dealerTiers.forEach { d -> appendLine("- ${d.labelKo}: 딜러 ${d.dealers}명 · 신청 ${d.metrics.applications} · 승인율 ${pct(d.metrics.approvalRate)} · 고위험 비율 ${pct(d.metrics.highRiskRate)} · 위조 경고 ${d.metrics.fraudWarnings} · 평균 리스크 ${num(d.metrics.avgRiskScore)} · 평균 신용 ${num(d.metrics.avgCreditScore)}") }
        val top = s.dealers.sortedByDescending { it.metrics.applications }.take(5)
        if (top.isNotEmpty()) {
            appendLine("**신청 많은 딜러**")
            top.forEach { d -> appendLine("- ${d.code} ${d.name} (${d.country}, ${d.tierLabelKo}): 신청 ${d.metrics.applications} · 승인 ${d.metrics.approved} · 고위험 ${d.metrics.highRisk} · 위조 경고 ${d.metrics.fraudWarnings} · 최근 30일 ${d.metrics.applications30d}") }
        }
    }

    fun queue(s: Stats): String = buildString {
        if (s.reviewQueue.isEmpty()) { appendLine("심사 대기 큐가 비어 있습니다."); return@buildString }
        appendLine("**먼저 볼 신청 (리스크 높은 순, ${s.reviewQueue.size}건 중 상위)**")
        s.reviewQueue.take(5).forEach { q ->
            val why = buildList {
                q.grade?.let { add("등급 $it${q.score?.let { p -> " ${p}점" } ?: ""}") }
                if (q.fraudWarnings > 0) add("위조·사기 경고 ${q.fraudWarnings}건")
                if (q.officerReviewRequired) add("담당자 검토 필요")
                add("대기 ${hours(q.waitingHours)}")
            }
            appendLine("- ${q.applicationNumber} (${q.country}, 딜러 ${q.dealerCode ?: "?"}): ${why.joinToString(" · ")}")
        }
        appendLine("- 각 신청의 서류와 근거는 심사 화면에서 신청번호로 열어 확인하세요.")
    }

    fun trend(s: Stats): String = buildString {
        if (s.trend.isEmpty()) { appendLine("주간 추이 데이터가 아직 없습니다."); return@buildString }
        appendLine("**주간 추이 (ISO 주, 오래된 순)**")
        s.trend.forEach { w -> appendLine("- ${w.label}: 신청 ${w.created} · 서류 완료 ${w.completed} · 승인 ${w.approved} · 거절 ${w.rejected} · 고위험 ${w.highRisk} · 위조 경고 ${w.fraudWarnings}") }
        val last = s.trend.last(); val prev = s.trend.dropLast(1).lastOrNull()
        if (prev != null) appendLine("- 최근 주 신청은 전주 대비 ${last.created - prev.created}건 ${if (last.created >= prev.created) "증가" else "감소"} (${prev.created} → ${last.created}).")
    }

    fun agent(s: Stats): String = buildString {
        val a = s.agent
        appendLine("**AI 에이전트 활동 (전체 신청)**")
        appendLine("- 기록된 단계 ${a.steps} · 비전 분석 ${a.visionAnalyses}건 (평균 ${num(a.avgVisionMs)} ms) · 에이전트 턴 ${a.agentTurns} · 도구 호출 ${a.toolCalls}")
        appendLine("- 에이전트가 서류 재요청 ${a.documentsRequestedByAgent}건 · 심사 요약 작성 ${a.summariesWritten}건 · 코드 가드레일 ${a.guardrails}건 · 폴백 ${a.fallbacks}건 · 고객 재촬영 ${a.customerRetakes}건 · 신원·신용 조회 ${a.providerLookups}건")
        if (a.byKind.isNotEmpty()) appendLine("- 단계 종류별: " + a.byKind.entries.joinToString(", ") { "${it.key} ${it.value}" })
    }

    fun risk(s: Stats): String = buildString {
        val t = s.totals
        appendLine("**리스크 등급 분포 (평가 ${t.assessed}건)**")
        s.riskGrades.forEach { appendLine("- ${it.grade} ${it.labelKo}: ${it.count}") }
        if (s.flagCounts.isNotEmpty()) {
            appendLine("**가장 많이 발생한 Risk Flag**")
            s.flagCounts.take(5).forEach { appendLine("- ${if (it.fraud) "⚠ " else ""}${it.titleKo} (${it.code}, ${it.severity}): ${it.count}건") }
        }
        appendLine("- 고위험 고객 ${t.highRisk}건, 위조·사기 경고 ${t.fraudWarnings}건." + (s.highRiskApplications.take(3).takeIf { it.isNotEmpty() }?.let { " 예: " + it.joinToString(", ") { r -> "${r.applicationNumber} (${r.grade}, ${r.score}점)" } } ?: ""))
        if (s.badges.isNotEmpty()) appendLine("- 배지: " + s.badges.joinToString(", ") { "${it.labelKo} ${it.count}" })
    }
}
