package com.nextasean.wawebhook.trace

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Service
import java.time.Instant

/** Which part of the pipeline a step belongs to; drives the colour and grouping on the review page. */
enum class TracePhase(val label: String, val labelKo: String) {
    DOCUMENT("Document intake", "서류 수신"),
    ANALYSIS("Document analysis", "서류 분석"),
    VALIDATION("Validation", "검증"),
    AGENT("Review agent", "리뷰 에이전트"),
    VERIFICATION("Provider verification", "신원·신용 조회"),
    RISK("Risk assessment", "리스크 평가"),
    OUTCOME("Outcome", "결과"),
    OFFICER("Officer", "심사자"),
}

enum class TraceStatus { OK, FAIL, WARN, INFO, DECISION }

/** Every kind of step the pipeline can record. Labels are shown on the review page; the code is stable for statistics. */
enum class TraceKind(val phase: TracePhase, val label: String, val labelKo: String) {
    DOCUMENT_RECEIVED(TracePhase.DOCUMENT, "Document received", "서류 수신"),
    VISION_ANALYSIS(TracePhase.ANALYSIS, "Claude vision analysis", "Claude 비전 분석"),
    QUALITY_CHECK(TracePhase.ANALYSIS, "Quality check", "품질 확인"),
    NIK_VALIDATION(TracePhase.VALIDATION, "NIK structural validation", "NIK 구조 검증"),
    FIELD_EXTRACTION(TracePhase.VALIDATION, "Field extraction", "필드 추출"),
    AGENT_TURN(TracePhase.AGENT, "Review agent turn", "리뷰 에이전트 턴"),
    AGENT_TOOL_CALL(TracePhase.AGENT, "Tool call", "도구 호출"),
    AGENT_RETRY(TracePhase.AGENT, "Agent asked to retry", "에이전트 재시도 요청"),
    AGENT_FALLBACK(TracePhase.AGENT, "Rule-based fallback", "규칙 기반 폴백"),
    AGENT_GUARDRAIL(TracePhase.AGENT, "Code guardrail applied", "코드 가드레일 적용"),
    AGENT_DECISION(TracePhase.AGENT, "Agent decision", "에이전트 결정"),
    CUSTOMER_MESSAGED(TracePhase.OUTCOME, "Customer messaged", "고객 안내 전송"),
    ID_VERIFICATION(TracePhase.VERIFICATION, "Identity registry lookup", "신원 조회"),
    CREDIT_CHECK(TracePhase.VERIFICATION, "Credit bureau lookup", "신용 조회"),
    RISK_ASSESSMENT(TracePhase.RISK, "Risk assessment", "리스크 평가"),
    HANDOFF(TracePhase.OUTCOME, "Handed to officer without provider checks", "심사자 인계 (조회 생략)"),
    SUBMITTED(TracePhase.OUTCOME, "Ready for review", "심사 대기 전환"),
    OFFICER_REQUEST(TracePhase.OFFICER, "Officer requested documents", "심사자 서류 재요청"),
    OFFICER_DECISION(TracePhase.OFFICER, "Officer decision", "심사자 결정"),
}

/**
 * One step of the automated pipeline for one application: what ran (vision analysis, NIK validation, an agent turn with
 * its stop reason and tool call, a provider lookup, the risk rules), who did it ([actor]: `claude:<model>`, `rules`,
 * `stub`, `provider:<name>`, `customer`, `officer:<name>`), the outcome and the structured evidence ([dataJson]).
 * The review page renders these as the "AI agent timeline"; the trail is written as the work happens so it can be watched live.
 */
@Table("AGENT_STEPS")
data class AgentStep(
    @Id val id: Long? = null,
    val applicationId: Long,
    val kind: TraceKind,
    val status: TraceStatus,
    val actor: String,
    val title: String,
    val detail: String? = null,
    val dataJson: String? = null,
    val durationMs: Long? = null,
    /** Document round the step belongs to (0 = first submission). */
    val round: Int = 0,
    val createdAt: Instant = Instant.now(),
)

interface AgentStepRepository : CrudRepository<AgentStep, Long> {
    fun findByApplicationIdOrderByCreatedAtAscIdAsc(applicationId: Long): List<AgentStep>
    fun countByApplicationId(applicationId: Long): Long
}

/** Appends steps to the per-application trace. Best effort: a failure here is logged and never breaks the customer flow. */
@Service
class AgentTraceService(private val steps: AgentStepRepository, private val objectMapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun record(
        applicationId: Long?,
        kind: TraceKind,
        status: TraceStatus,
        actor: String,
        title: String,
        detail: String? = null,
        data: Any? = null,
        durationMs: Long? = null,
        round: Int = 0,
    ): AgentStep? {
        if (applicationId == null) return null
        return try {
            val json = data?.let { d -> runCatching { objectMapper.writeValueAsString(d) }.getOrNull() }?.let { full ->
                if (full.length <= MAX_DATA_CHARS) full
                else objectMapper.writeValueAsString(mapOf("truncated" to true, "chars" to full.length, "preview" to full.take(MAX_DATA_CHARS - 200)))
            }
            steps.save(
                AgentStep(
                    applicationId = applicationId, kind = kind, status = status, actor = actor.take(80), title = title.take(200),
                    detail = detail?.take(1_000), dataJson = json, durationMs = durationMs, round = round,
                ),
            ).also { log.info("Agent trace | application={} | {} | {} | {} | {}", applicationId, kind, status, actor, title) }
        } catch (e: Exception) {
            log.error("Could not record agent step {} for application {}", kind, applicationId, e)
            null
        }
    }

    fun steps(applicationId: Long?): List<AgentStep> = applicationId?.let(steps::findByApplicationIdOrderByCreatedAtAscIdAsc).orEmpty()

    fun count(applicationId: Long?): Long = applicationId?.let(steps::countByApplicationId) ?: 0L

    /** Only for statistics on the dashboard. */
    fun all(): List<AgentStep> = steps.findAll().toList()

    companion object {
        const val MAX_DATA_CHARS = 8_000
        const val RULES = "rules"
        const val STUB = "stub"
        const val CUSTOMER = "customer"
        const val SYSTEM = "system"
        fun claude(model: String) = "claude:$model"
        fun provider(name: String) = "provider:$name"
        fun officer(name: String?) = "officer:${name?.trim()?.ifBlank { null } ?: "unknown"}"
    }
}
