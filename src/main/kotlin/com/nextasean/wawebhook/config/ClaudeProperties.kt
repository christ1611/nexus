package com.nextasean.wawebhook.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Claude API settings for document analysis and the review agent. Bound from `claude.*` in application.yml,
 * which reads `ANTHROPIC_API_KEY` from wa.env / .env or the environment. Only used when `app.document-check=claude`.
 */
@ConfigurationProperties(prefix = "claude")
data class ClaudeProperties(
    /** Blank means: let the SDK resolve credentials itself (ANTHROPIC_API_KEY, ANTHROPIC_AUTH_TOKEN or an `ant auth login` profile). */
    val apiKey: String = "",
    val model: String = "claude-opus-5-5",
    /** low | medium | high | xhigh | max. Opus 5.5 defaults to medium, so it is set explicitly. */
    val effort: String = "high",
    val timeout: Duration = Duration.ofSeconds(120),
    val maxRetries: Int = 2,
    /** Fields below this confidence are never used; they are marked "needs officer review". */
    val minFieldConfidence: Double = 0.85,
    /** The API accepts images up to 5 MB; larger files are flagged instead of sent. */
    val maxImageBytes: Long = 5L * 1024 * 1024,
    /** How many times the review agent may send the customer back for documents before it must write the summary. */
    val maxReviewRounds: Int = 2,
) {
    override fun toString(): String =
        "ClaudeProperties(model=$model, effort=$effort, timeout=$timeout, maxRetries=$maxRetries, " +
            "minFieldConfidence=$minFieldConfidence, maxImageBytes=$maxImageBytes, maxReviewRounds=$maxReviewRounds, apiKey=***)"
}
