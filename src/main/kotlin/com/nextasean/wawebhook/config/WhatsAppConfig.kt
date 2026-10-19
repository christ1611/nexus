package com.nextasean.wawebhook.config

import com.nextasean.wawebhook.webhook.SignatureVerifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClient

@Configuration
class WhatsAppConfig {

    @Bean
    fun signatureVerifier(props: WhatsAppProperties): SignatureVerifier = SignatureVerifier(props.appSecret)

    @Bean
    fun graphRestClient(builder: RestClient.Builder, props: WhatsAppProperties): RestClient =
        builder
            .baseUrl(props.graphApiBaseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${props.accessToken}")
            .build()
}
