package com.nextasean.wawebhook.review

import com.nextasean.wawebhook.config.AppProperties
import com.nextasean.wawebhook.config.OllamaProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Sends copilot questions to a local Ollama server (`POST /api/chat`) when `app.copilot-engine=ollama`. Shared by the
 * review and dashboard copilots; document analysis and the review agent are not affected. Returns null when the
 * server cannot answer, so the callers fall back to their rule-based answers and the panel keeps working on stage.
 */
@Service
class OllamaCopilot(
    private val appProps: AppProperties,
    private val props: OllamaProperties,
    builder: RestClient.Builder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val http: RestClient = builder.clone()
        .baseUrl(props.baseUrl)
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(2))
            setReadTimeout(props.timeout)
        })
        .build()

    val enabled: Boolean get() = appProps.copilotEngine.equals("ollama", ignoreCase = true)

    private companion object {
        const val FACTS_INTRO = "[Verified figures for this question, computed by the back office. They are already correct and sorted. " +
            "Answer directly from them in the language of the question, in the same order, and keep every number, code and application number exactly. " +
            "Do not recalculate them and do not add figures that are not here.]"
    }

    data class Reply(val text: String, val model: String, val inputTokens: Long?, val outputTokens: Long?)

    /**
     * [context] is the case file or portfolio file; it goes into the system message after [system]. [facts] are the
     * rule engine's figures for this question: local models misread long files, so they get the right numbers next to
     * the question and only have to word the answer.
     */
    fun chat(system: String, context: String, history: List<ReviewCopilotService.Turn>, question: String, reviewer: String?, facts: String? = null): Reply? {
        val turns = copilotTurns(history, question, reviewer, ReviewCopilotService.MAX_HISTORY)
        val messages = buildList {
            add(mapOf("role" to "system", "content" to "$system\n\n$context"))
            turns.forEachIndexed { i, (user, content) ->
                val text = if (i == turns.lastIndex && !facts.isNullOrBlank()) "$content\n\n$FACTS_INTRO\n$facts" else content
                add(mapOf("role" to if (user) "user" else "assistant", "content" to text))
            }
        }
        val body = mapOf(
            "model" to props.model,
            "messages" to messages,
            "stream" to false,
            "think" to false,
            "keep_alive" to props.keepAlive,
            "options" to mapOf("num_ctx" to props.numCtx, "temperature" to props.temperature),
        )
        return try {
            val response = http.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map::class.java)
            val text = ((response?.get("message") as? Map<*, *>)?.get("content") as? String)?.trim().orEmpty()
            if (text.isEmpty()) {
                log.warn("Ollama copilot returned an empty answer | model={}", props.model)
                return null
            }
            val input = (response?.get("prompt_eval_count") as? Number)?.toLong()
            // Ollama silently cuts a prompt that does not fit; a full window means the model did not read the whole file.
            if (input != null && input >= props.numCtx) log.warn("Ollama copilot prompt filled the context window | in={} | numCtx={}", input, props.numCtx)
            Reply(text, response?.get("model") as? String ?: props.model, input, (response?.get("eval_count") as? Number)?.toLong())
        } catch (e: Exception) {
            log.warn("Ollama copilot unavailable, answering from rules | url={} | model={} | {}", props.baseUrl, props.model, e.toString())
            null
        }
    }
}
