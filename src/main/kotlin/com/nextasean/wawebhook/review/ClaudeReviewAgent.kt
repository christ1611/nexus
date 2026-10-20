package com.nextasean.wawebhook.review

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoiceAuto
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.nextasean.wawebhook.config.ClaudeProperties
import com.nextasean.wawebhook.documents.DocumentType
import com.nextasean.wawebhook.documents.analysis.FieldStatus
import com.nextasean.wawebhook.trace.AgentTraceService
import com.nextasean.wawebhook.trace.TraceKind
import com.nextasean.wawebhook.trace.TraceStatus
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import kotlin.jvm.optionals.getOrNull

/**
 * Agent step on the Claude API: the model reads the case file (every document's analysis) and must call exactly one of
 * two tools, `request_documents` or `write_summary`. The code executes the tool and applies two guardrails that the
 * model cannot override: a missing document is always requested while rounds remain, and when no rounds remain the
 * result is always a summary flagged for an officer. Anything that goes wrong falls back to [RuleBasedReviewAgent].
 */
@Service
@ConditionalOnProperty(prefix = "app", name = ["document-check"], havingValue = "claude")
class ClaudeReviewAgent(
    private val client: AnthropicClient,
    private val props: ClaudeProperties,
    private val objectMapper: ObjectMapper,
    private val trace: AgentTraceService,
    private val fallback: RuleBasedReviewAgent = RuleBasedReviewAgent(),
) : TracingReviewAgent {

    private val log = LoggerFactory.getLogger(javaClass)

    override val actor: String get() = AgentTraceService.claude(props.model)
    override val minFieldConfidence: Double get() = props.minFieldConfidence

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RequestDocumentsInput(val documents: List<Item> = emptyList(), val note: String? = null) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Item(val type: String? = null, val reason: String? = null)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WriteSummaryInput(
        val summary: String? = null,
        @JsonProperty("officer_review_required") val officerReviewRequired: Boolean? = null,
    )

    private val tools: List<Tool> = listOf(
        tool(
            name = REQUEST_DOCUMENTS,
            description = "Send the customer back on WhatsApp to retake one or more documents. Use it only when a new photo " +
                "would plausibly fix the problem (missing, blurry, glare, cropped, wrong document, illegible required field). " +
                "Each reason is shown to the customer verbatim after the document name, so keep it to one short, friendly, concrete sentence.",
            properties = mapOf(
                "documents" to mapOf(
                    "type" to "array",
                    "minItems" to 1,
                    "items" to mapOf(
                        "type" to "object",
                        "additionalProperties" to false,
                        "required" to listOf("type", "reason"),
                        "properties" to mapOf(
                            "type" to mapOf("type" to "string", "enum" to DocumentType.entries.map { it.name }),
                            "reason" to mapOf("type" to "string", "description" to "Customer-facing, e.g. 'the NIK line has glare on the right'"),
                        ),
                    ),
                ),
                "note" to mapOf("type" to "string", "description" to "Optional extra tip for the customer; empty string if none."),
            ),
            required = listOf("documents", "note"),
        ),
        tool(
            name = WRITE_SUMMARY,
            description = "Finish the intake: write the one-page summary for the back-office officer. Use it when every required " +
                "document is present and usable and a retake would not improve anything that still needs a human, or when no " +
                "retake rounds remain. officer_review_required must be true if any field is marked NEEDS OFFICER REVIEW, the NIK " +
                "validation failed, or anything else needs a human before verification.",
            properties = mapOf(
                "summary" to mapOf(
                    "type" to "string",
                    "description" to "Plain text, at most about 400 words, WhatsApp-friendly (no markdown tables). Sections: Applicant; " +
                        "Documents received; Extracted data (value, confidence, status - never invent or 'correct' a value); NIK validation; " +
                        "Quality issues; Items for officer review; Recommended next step.",
                ),
                "officer_review_required" to mapOf("type" to "boolean"),
            ),
            required = listOf("summary", "officer_review_required"),
        ),
    )

    private val system: List<TextBlockParam> = listOf(
        TextBlockParam.builder().text(SYSTEM).cacheControl(CacheControlEphemeral.builder().build()).build(),
    )

    override fun decide(context: ReviewContext): ReviewDecision {
        val appId = context.application.id
        val round = context.round
        var failure: String? = null
        val modelDecision = try {
            run(context)
        } catch (e: AnthropicException) {
            log.error("Review agent failed, using rule-based decision | application={}", context.application.applicationNumber, e)
            failure = "Claude API error: ${e.message}"
            null
        } catch (e: RuntimeException) {
            log.error("Review agent error, using rule-based decision | application={}", context.application.applicationNumber, e)
            failure = "Agent error: ${e.message}"
            null
        }
        val decision = modelDecision ?: fallback.decide(context).also {
            trace.record(appId, TraceKind.AGENT_FALLBACK, TraceStatus.WARN, AgentTraceService.RULES, "Model gave no usable decision; deterministic rules decided instead", failure, round = round)
        }
        return guard(context, decision)
    }

    private fun run(context: ReviewContext): ReviewDecision? {
        val messages = mutableListOf<MessageParam>(
            MessageParam.builder().role(MessageParam.Role.USER).content(CaseFile.render(context)).build(),
        )
        repeat(MAX_ROUNDS) { attempt ->
            val builder = MessageCreateParams.builder()
                .model(props.model)
                .maxTokens(8_000L)
                .systemOfTextBlockParams(system)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(props.effort.lowercase())).build())
                .toolChoice(ToolChoiceAuto.builder().disableParallelToolUse(true).build())
                .messages(messages)
            tools.forEach { builder.addTool(it) }
            val started = System.currentTimeMillis()
            val response = client.messages().create(builder.build())
            val ms = System.currentTimeMillis() - started
            val stop = response.stopReason().getOrNull()
            val toolUse = response.content().mapNotNull { it.toolUse().getOrNull() }.firstOrNull()
            val thinking = response.content().mapNotNull { it.text().getOrNull()?.text() }.joinToString("\n").trim()
            log.info(
                "Review agent turn | application={} | attempt={} | stop={} | in={} | out={}",
                context.application.applicationNumber, attempt + 1, stop?.asString(), response.usage().inputTokens(), response.usage().outputTokens(),
            )
            trace.record(
                context.application.id, TraceKind.AGENT_TURN, if (stop == StopReason.REFUSAL) TraceStatus.FAIL else TraceStatus.INFO, actor,
                "Turn ${attempt + 1}: stop=${stop?.asString() ?: "?"}" + (toolUse?.let { " -> tool ${it.name()}" } ?: " (no tool call)"),
                thinking.take(600).ifBlank { null },
                mapOf(
                    "attempt" to attempt + 1, "stopReason" to stop?.asString(), "tool" to toolUse?.name(), "model" to response.model().asString(),
                    "inputTokens" to response.usage().inputTokens(), "cacheReadTokens" to response.usage().cacheReadInputTokens().getOrNull(),
                    "outputTokens" to response.usage().outputTokens(), "durationMs" to ms,
                ),
                durationMs = ms, round = context.round,
            )
            if (stop == StopReason.REFUSAL) {
                log.warn("Review agent refused | application={} | details={}", context.application.applicationNumber, response.stopDetails().getOrNull())
                return null
            }
            if (toolUse != null) {
                val decision = execute(toolUse)
                trace.record(
                    context.application.id, TraceKind.AGENT_TOOL_CALL, if (decision != null) TraceStatus.DECISION else TraceStatus.FAIL, actor,
                    "${toolUse.name()}(" + "...)" + (if (decision == null) " rejected: invalid input" else ""),
                    toolInputSummary(toolUse), mapOf("tool" to toolUse.name(), "input" to toolUse._input()), round = context.round,
                )
                if (decision != null) return decision
                // Invalid tool input: tell the model and let it try once more.
                trace.record(context.application.id, TraceKind.AGENT_RETRY, TraceStatus.WARN, AgentTraceService.RULES, "Tool input rejected by the code; model asked to try again", round = context.round)
                messages += response.toParam()
                messages += MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(
                    listOf(
                        ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder().toolUseId(toolUse.id()).isError(true)
                                .content("Invalid input for ${toolUse.name()}: document types must be one of ${DocumentType.entries.joinToString()} with a non-empty reason; summaries must be non-empty.")
                                .build(),
                        ),
                    ),
                ).build()
            } else {
                trace.record(context.application.id, TraceKind.AGENT_RETRY, TraceStatus.WARN, AgentTraceService.RULES, "No tool call in the reply; model reminded to call exactly one tool", round = context.round)
                messages += response.toParam()
                messages += MessageParam.builder().role(MessageParam.Role.USER)
                    .content("You must now call exactly one of the tools: $REQUEST_DOCUMENTS or $WRITE_SUMMARY.").build()
            }
        }
        log.warn("Review agent made no decision after {} turns | application={}", MAX_ROUNDS, context.application.applicationNumber)
        return null
    }

    private fun execute(toolUse: ToolUseBlock): ReviewDecision? {
        @Suppress("UNCHECKED_CAST")
        val input = toolUse._input().convert(Map::class.java) as Map<String, Any?>
        return when (toolUse.name()) {
            REQUEST_DOCUMENTS -> {
                val parsed = objectMapper.convertValue(input, RequestDocumentsInput::class.java)
                val items = parsed.documents.mapNotNull { item ->
                    val type = item.type?.let { t -> DocumentType.entries.firstOrNull { it.name.equals(t, ignoreCase = true) } }
                    val reason = item.reason?.trim()?.takeIf { it.isNotEmpty() }
                    if (type != null && reason != null) DocumentRequest(type, reason.take(200)) else null
                }.distinctBy { it.type }
                if (items.isEmpty()) null else ReviewDecision.RequestDocuments(items, parsed.note?.trim()?.takeIf { it.isNotEmpty() }?.take(300))
            }
            WRITE_SUMMARY -> {
                val parsed = objectMapper.convertValue(input, WriteSummaryInput::class.java)
                val summary = parsed.summary?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                ReviewDecision.SummaryWritten(summary.take(MAX_SUMMARY_CHARS), parsed.officerReviewRequired ?: true)
            }
            else -> null
        }
    }

    private fun toolInputSummary(toolUse: ToolUseBlock): String? = runCatching {
        @Suppress("UNCHECKED_CAST")
        val input = toolUse._input().convert(Map::class.java) as Map<String, Any?>
        when (toolUse.name()) {
            REQUEST_DOCUMENTS -> (input["documents"] as? List<*>).orEmpty().joinToString("; ") { d -> (d as? Map<*, *>)?.let { "${it["type"]}: ${it["reason"]}" } ?: d.toString() } +
                (input["note"]?.toString()?.takeIf { it.isNotBlank() }?.let { " | note: $it" } ?: "")
            WRITE_SUMMARY -> "officer_review_required=${input["officer_review_required"]} | " + input["summary"]?.toString()?.lineSequence()?.take(3)?.joinToString(" / ").orEmpty()
            else -> input.toString()
        }.take(600)
    }.getOrNull()

    /** Floors under the model's decision. */
    private fun guard(ctx: ReviewContext, decision: ReviewDecision): ReviewDecision {
        val appId = ctx.application.id
        val mustRequest = fallback.requestsFor(ctx).filter { r -> ctx.documents.first { it.type == r.type }.let { !it.received || it.analysis?.usable == false } }
        return when (decision) {
            is ReviewDecision.RequestDocuments -> if (!ctx.canRequestMore) {
                log.info("Agent asked for documents but no rounds remain; writing summary for officer | application={}", ctx.application.applicationNumber)
                trace.record(appId, TraceKind.AGENT_GUARDRAIL, TraceStatus.WARN, AgentTraceService.RULES, "Agent wanted more documents but no retake rounds remain: summary for the officer instead", round = ctx.round)
                ReviewDecision.SummaryWritten(SummaryTemplate.render(ctx, true), true)
            } else {
                decision
            }
            is ReviewDecision.SummaryWritten -> if (mustRequest.isNotEmpty() && ctx.canRequestMore) {
                log.info("Agent wrote a summary while documents are missing or unusable; requesting instead | application={} | {}", ctx.application.applicationNumber, mustRequest)
                trace.record(appId, TraceKind.AGENT_GUARDRAIL, TraceStatus.WARN, AgentTraceService.RULES, "Agent wrote a summary while a document is missing or unusable: requesting it instead", mustRequest.joinToString("; ") { "${it.type.label}: ${it.reason}" }, round = ctx.round)
                ReviewDecision.RequestDocuments(mustRequest)
            } else {
                val anyReview = ctx.documents.any { d -> d.analysis?.needsOfficerReview == true || (d.analysis == null && d.record?.needsOfficerReview == true) }
                if (anyReview && !decision.officerReviewRequired) {
                    trace.record(appId, TraceKind.AGENT_GUARDRAIL, TraceStatus.WARN, AgentTraceService.RULES, "Summary said no manual items, but a document is flagged for review: officer_review_required forced to true", round = ctx.round)
                    decision.copy(officerReviewRequired = true)
                } else {
                    decision
                }
            }
        }
    }

    private fun tool(name: String, description: String, properties: Map<String, Any>, required: List<String>): Tool {
        val props = Tool.InputSchema.Properties.builder()
        properties.forEach { (k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)) }
        return Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(props.build())
                    .required(required)
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build(),
            )
            .strict(true)
            .build()
    }

    companion object {
        const val REQUEST_DOCUMENTS = "request_documents"
        const val WRITE_SUMMARY = "write_summary"
        private const val MAX_ROUNDS = 3
        private const val MAX_SUMMARY_CHARS = 6_000

        val SYSTEM: String = """
            You are the document intake agent of an Indonesian consumer-finance onboarding flow run over WhatsApp. A customer
            has sent the documents a dealer's application needs. A vision analysis of each image is in the case file, including
            quality issues, every field read with its confidence and status, and the structural validation of the NIK.

            Decide the single next step by calling exactly one tool:
            - request_documents: when a new photo would plausibly fix the problem - a document is missing, unusable, the wrong
              document, or a field required for verification (NIK, name, date of birth, gender, address) is NOT VISIBLE or
              marked NEEDS OFFICER REVIEW because of legibility (blur, glare, cropping, low resolution). The case file says how
              many retake rounds remain; do not request when none remain.
            - write_summary: when the documents are complete and usable and nothing left would be fixed by a retake. A NIK
              that fails its structural validation (wrong province code, encoded date of birth or gender not matching the
              card) is a case for the officer, not for a retake, unless the digits themselves were not legible.

            Rules you must follow:
            - Values marked NEEDS OFFICER REVIEW or NOT VISIBLE are unknown. Never guess, complete or "correct" them, never
              present them as facts, and list each of them explicitly for the officer.
            - Do not judge whether the person is who they claim to be and do not speculate about fraud. Report facts; the
              officer decides.
            - The summary must fit on one page (about 400 words), plain text with short headings, in English, and must name
              every item an officer has to look at.
            - Reasons given to the customer are polite, concrete and about the photo, never about suspicion.
        """.trimIndent()
    }
}

/** Renders the review context as the text the agent reads. Verbatim model JSON is left out; the structured analysis is enough. */
object CaseFile {
    fun render(ctx: ReviewContext): String = buildString {
        val app = ctx.application
        appendLine("# Case file for application ${app.applicationNumber}")
        appendLine("Customer name (WhatsApp profile): ${app.customerName ?: "unknown"}; phone +${app.customerPhone ?: "?"}")
        appendLine("Documents required, in order: ${DocumentType.steps.joinToString { "${it.name} (${it.label})" }}")
        appendLine("Retake rounds used: ${ctx.round} of ${ctx.maxRounds}. Rounds remaining: ${(ctx.maxRounds - ctx.round).coerceAtLeast(0)}.")
        for (doc in ctx.documents) {
            appendLine()
            appendLine("## ${doc.type.name} - ${doc.type.label}")
            val a = doc.analysis
            when {
                doc.record == null -> appendLine("Status: NOT RECEIVED")
                a == null -> appendLine("Status: received at ${doc.record.createdAt}; automated analysis unavailable (needs officer review: ${doc.record.needsOfficerReview})")
                else -> {
                    appendLine("Status: received at ${doc.record.createdAt}; check passed: ${doc.record.checkPassed}")
                    appendLine("Seen as: ${a.documentType.name} (confidence ${fmt(a.documentTypeConfidence)}); matches request: ${a.typeMatchesRequest}; usable: ${a.usable}")
                    appendLine("Quality issues: " + if (a.qualityIssues.isEmpty()) "none" else a.qualityIssues.joinToString("; ") { "${it.type} ${it.severity} - ${it.detail}" })
                    if (a.fields.isNotEmpty()) {
                        appendLine("Fields (name | value | confidence | status | note):")
                        a.fields.forEach { f ->
                            val value = if (f.status == FieldStatus.NOT_VISIBLE) "<not visible>" else f.value
                            appendLine("- ${f.name} | $value | ${fmt(f.confidence)} | ${f.status.name.replace('_', ' ')} | ${f.note ?: ""}")
                        }
                    }
                    a.nik?.let { n ->
                        appendLine("NIK validation: ${if (n.valid) "PASSED" else "FAILED"}; province ${n.provinceCode} ${n.provinceName ?: "(unknown)"}, regency ${n.regencyCode}, district ${n.districtCode}; " +
                            "encoded DOB ${n.dobFromNik ?: "invalid"} (${n.genderFromNik ?: "?"}); card DOB ${n.statedDob ?: "?"} (${n.statedGender ?: "?"})")
                        n.issues.forEach { appendLine("- NIK issue: $it") }
                    }
                    appendLine("Review reasons: " + if (a.reviewReasons.isEmpty()) "none" else a.reviewReasons.joinToString("; "))
                    a.modelNotes?.let { appendLine("Analyst notes: $it") }
                }
            }
        }
        appendLine()
        append("Call exactly one tool now.")
    }

    private fun fmt(v: Double) = "%.2f".format(v)
}
