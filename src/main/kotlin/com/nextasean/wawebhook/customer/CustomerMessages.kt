package com.nextasean.wawebhook.customer

import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.documents.DocumentType
import com.nextasean.wawebhook.review.DocumentRequest
import com.nextasean.wawebhook.whatsapp.OutboundMessage
import com.nextasean.wawebhook.whatsapp.OutboundMessage.Button
import com.nextasean.wawebhook.whatsapp.OutboundMessage.Buttons
import com.nextasean.wawebhook.whatsapp.OutboundMessage.Text

object CustomerReplyIds {
    const val CONSENT_AGREE = "CONSENT_AGREE"
    const val CONSENT_DECLINE = "CONSENT_DECLINE"
}

/** All customer-facing wording in one place. */
object CustomerMessages {

    fun greetingAndConsent(app: DealerApplication, customerName: String?): OutboundMessage = Buttons(
        header = "NEXT ASEAN application",
        body = "Hello${customerName?.let { " $it" } ?: ""}, welcome! Your dealer has started application *${app.applicationNumber}* for you.\n\n" +
            "To continue we need to collect a photo of your KTP and a selfie, and verify your identity and credit record with " +
            "authorised providers. Your data is used only to process this application and is handled according to our privacy policy.\n\n" +
            "Do you agree?",
        buttons = listOf(Button(CustomerReplyIds.CONSENT_AGREE, "I Agree"), Button(CustomerReplyIds.CONSENT_DECLINE, "Decline")),
    )

    fun declined(): OutboundMessage = Text(
        "No problem, we will not proceed with this application. If you change your mind, ask your dealer for a new link. Thank you.",
    )

    fun requestDocument(type: DocumentType, prefix: String? = null): OutboundMessage = Text(
        (prefix?.let { "$it\n\n" } ?: "") + "Step ${type.step} of ${DocumentType.total}: Please send ${type.request}.",
    )

    fun received(type: DocumentType): OutboundMessage = Text("Received: ${type.label} ✅ (${type.step} of ${DocumentType.total})")

    fun checkFailed(type: DocumentType, reason: String): OutboundMessage = Text(
        "❌ ${type.label} could not be accepted: $reason.\n\nStep ${type.step} of ${DocumentType.total}: Please send ${type.request} again.",
    )

    /** The review agent sent the customer back for one or more documents; [next] is the step asked for first. */
    fun documentsRequested(items: List<DocumentRequest>, next: DocumentType, note: String? = null): OutboundMessage = Text(
        buildString {
            append("Thank you. Before we can continue, we need the following again:\n")
            items.forEach { append("\u2022 ${it.type.label}: ${it.reason.trimEnd('.')}.\n") }
            note?.takeIf { it.isNotBlank() }?.let { append("\n$it\n") }
            append("\nStep ${next.step} of ${DocumentType.total}: Please send ${next.request}.")
        },
    )

    fun downloadFailed(type: DocumentType): OutboundMessage = Text(
        "Sorry, we could not download that image. Please send ${type.request} again.",
    )

    fun checking(): OutboundMessage = Text("Checking your data...")

    fun stillChecking(): OutboundMessage = Text("We are still checking your data, this takes a few seconds. Please wait.")

    fun submitted(app: DealerApplication): OutboundMessage = Text(
        "✅ Your documents are submitted. Application *${app.applicationNumber}* is now with our review team.\n\n" +
            "Your dealer will contact you with the result. Thank you!",
    )

    fun cancelled(app: DealerApplication): OutboundMessage = Text(
        "Your session for application *${app.applicationNumber}* has been cancelled. Send START ${app.applicationNumber} to begin again.",
    )

    fun unknownApplication(number: String?): OutboundMessage = Text(
        if (number == null) "To start, please send START followed by your application number, for example: START APP-2026-001001. " +
            "Your dealer can send you a link that fills this in for you."
        else "Sorry, we could not find application $number. Please check the number with your dealer.",
    )

    fun linkedToOtherPhone(app: DealerApplication): OutboundMessage = Text(
        "Application ${app.applicationNumber} is already linked to another phone number. Please contact your dealer.",
    )

    fun notAcceptingDocuments(app: DealerApplication): OutboundMessage = Text(
        "Application ${app.applicationNumber} is currently \"${app.status.label}\" and is not accepting documents right now. " +
            "Your dealer will let you know if anything else is needed.",
    )
}
