package com.nextasean.wawebhook.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bound from the `whatsapp.*` keys in application.yml, which in turn read the
 * WHATSAPP_* entries of wa.env / .env or real environment variables.
 */
@ConfigurationProperties(prefix = "whatsapp")
data class WhatsAppProperties(
    val phoneNumberId: String = "",
    val accessToken: String = "",
    val appSecret: String = "",
    val verifyToken: String = "",
    val graphApiBaseUrl: String = "https://graph.facebook.com",
    val graphApiVersion: String = "v23.0",
    val businessPhone: String = "",
) {
    init {
        val missing = buildList {
            if (phoneNumberId.isBlank()) add("WHATSAPP_PHONE_NUMBER_ID")
            if (accessToken.isBlank()) add("WHATSAPP_ACCESS_TOKEN")
            if (appSecret.isBlank()) add("WHATSAPP_APP_SECRET")
            if (verifyToken.isBlank()) add("WHATSAPP_VERIFY_TOKEN")
        }
        require(missing.isEmpty()) {
            "Missing required WhatsApp configuration: ${missing.joinToString()}. " +
                "Add them to wa.env (or .env) in the working directory, or export them as environment variables."
        }
    }

    /** Never expose secrets through toString (e.g. in logs or error messages). */
    override fun toString(): String =
        "WhatsAppProperties(phoneNumberId=$phoneNumberId, graphApiBaseUrl=$graphApiBaseUrl, " +
            "graphApiVersion=$graphApiVersion, businessPhone=$businessPhone, accessToken=***, appSecret=***, verifyToken=***)"
}
