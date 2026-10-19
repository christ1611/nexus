package com.nextasean.wawebhook.whatsapp

import com.nextasean.wawebhook.config.WhatsAppProperties
import com.nextasean.wawebhook.dealer.normalizePhone
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * The business WhatsApp number used in wa.me links. Comes from `whatsapp.business-phone` when set,
 * otherwise from the `display_phone_number` metadata of incoming webhooks.
 */
@Component
class BusinessPhoneHolder(props: WhatsAppProperties) {
    private val configured = normalizePhone(props.businessPhone)
    private val learned = AtomicReference<String?>(null)

    fun remember(displayPhoneNumber: String?) {
        val digits = displayPhoneNumber?.let(::normalizePhone).orEmpty()
        if (digits.isNotEmpty()) learned.set(digits)
    }

    fun get(): String? = configured.ifEmpty { learned.get() }

    /** `https://wa.me/<number>?text=START%20<application number>` or null when the number is unknown. */
    fun startLink(applicationNumber: String): String? =
        get()?.let { "https://wa.me/$it?text=START%20${applicationNumber.replace(" ", "%20")}" }
}
