package com.nextasean.wawebhook.config

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Creates the Anthropic client only when the Claude-backed document check is switched on. */
@Configuration
@ConditionalOnProperty(prefix = "app", name = ["document-check"], havingValue = "claude")
class ClaudeConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun anthropicClient(props: ClaudeProperties): AnthropicClient {
        val builder = AnthropicOkHttpClient.builder()
            .timeout(props.timeout)
            .maxRetries(props.maxRetries)
        if (props.apiKey.isNotBlank()) builder.apiKey(props.apiKey) else builder.fromEnv()
        log.info("Claude document analysis enabled | {}", props)
        return builder.build()
    }
}
