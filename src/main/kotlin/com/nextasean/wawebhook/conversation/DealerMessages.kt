package com.nextasean.wawebhook.conversation

import com.nextasean.wawebhook.application.ApplicationStatus
import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.application.IssuedLink
import com.nextasean.wawebhook.dealer.Dealer
import com.nextasean.wawebhook.risk.RiskAssessment
import com.nextasean.wawebhook.verification.VerificationOutcome
import com.nextasean.wawebhook.whatsapp.OutboundMessage
import com.nextasean.wawebhook.whatsapp.OutboundMessage.Button
import com.nextasean.wawebhook.whatsapp.OutboundMessage.Buttons
import com.nextasean.wawebhook.whatsapp.OutboundMessage.ListMenu
import com.nextasean.wawebhook.whatsapp.OutboundMessage.Row
import com.nextasean.wawebhook.whatsapp.OutboundMessage.Section
import com.nextasean.wawebhook.whatsapp.OutboundMessage.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Reply IDs carried by list rows and buttons. Application-specific IDs use a `PREFIX:number` form. */
object ReplyIds {
    const val MENU_NEW = "MENU_NEW"
    const val MENU_STATUS = "MENU_STATUS"
    const val MENU_HELP = "MENU_HELP"
    const val MAIN_MENU = "MAIN_MENU"
    const val NEW_CONFIRM = "NEW_CONFIRM"
    const val NEW_CANCEL = "NEW_CANCEL"
    const val STATUS_PREFIX = "STATUS:"
    const val LINK_PREFIX = "LINK:"

    fun status(number: String) = STATUS_PREFIX + number
    fun link(number: String) = LINK_PREFIX + number
}

/** Builds every message of the dealer flow in one place so wording and button layout stay consistent. */
class DealerMessages(private val zone: ZoneId) {

    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(zone)

    private val mainMenuButton = Button(ReplyIds.MAIN_MENU, "Main menu")

    fun notRegistered(contactName: String?): OutboundMessage = Text(
        "Hello${contactName?.let { " $it" } ?: ""}, thank you for contacting NEXT ASEAN.\n\n" +
            "This WhatsApp number is not registered as a dealer, so we are unable to assist you through this channel. " +
            "If you are a dealer, please ask your account manager to register this number. Thank you for understanding.",
    )

    fun mainMenu(dealer: Dealer, intro: String? = null): OutboundMessage = ListMenu(
        header = "NEXT ASEAN Dealer Desk",
        body = (intro?.let { "$it\n\n" } ?: "") + "Hi ${dealer.name} (${dealer.dealerCode}), what would you like to do?",
        footer = "Type MENU at any time to come back here",
        buttonText = "Open menu",
        sections = listOf(
            Section(
                "Applications",
                listOf(
                    Row(ReplyIds.MENU_NEW, "New application", "Get an application number and form link"),
                    Row(ReplyIds.MENU_STATUS, "Check status", "See where your applications stand"),
                ),
            ),
            Section("Support", listOf(Row(ReplyIds.MENU_HELP, "Help", "How this assistant works"))),
        ),
    )

    fun confirmNewApplication(): OutboundMessage = Buttons(
        body = "Create a new application?\n\nYou will receive an application number and a one-time link to the online form.",
        buttons = listOf(Button(ReplyIds.NEW_CONFIRM, "Yes, create"), Button(ReplyIds.NEW_CANCEL, "Cancel")),
    )

    fun applicationCreated(link: IssuedLink): OutboundMessage = Buttons(
        header = "Application created",
        body = "Your application number is *${link.application.applicationNumber}*.\n\n" +
            "Complete the form using this one-time link (valid until ${fmt(link.expiresAt)}):\n${link.url}\n\n" +
            "The link works once. If it expires, choose Check status and request a new one.",
        buttons = listOf(Button(ReplyIds.status(link.application.applicationNumber), "Check status"), mainMenuButton),
    )

    /** Customer-facing text the dealer forwards; the wa.me link pre-fills "START <number>". */
    fun customerStartLink(number: String, waMeUrl: String?): OutboundMessage = Text(
        if (waMeUrl != null) {
            "Forward the message below to your customer:\n\n" +
                "Hello! Your dealer has started application $number for you. " +
                "Tap this link and send the pre-filled message to begin:\n$waMeUrl"
        } else {
            "Ask your customer to send the message \"START $number\" to this WhatsApp number to begin."
        },
        previewUrl = false,
    )

    fun cancelled(dealer: Dealer): OutboundMessage = mainMenu(dealer, "No problem, nothing was created.")

    fun noApplications(): OutboundMessage = Buttons(
        body = "You have no applications yet. Would you like to start one?",
        buttons = listOf(Button(ReplyIds.MENU_NEW, "New application"), mainMenuButton),
    )

    fun chooseApplication(apps: List<DealerApplication>): OutboundMessage = ListMenu(
        header = "Check status",
        body = "Select an application to see its current status.",
        buttonText = "Applications",
        sections = listOf(
            Section(
                "Most recent first",
                apps.take(10).map { Row(ReplyIds.status(it.applicationNumber), it.applicationNumber, "${it.status.label} · ${fmt(it.createdAt)}") },
            ),
        ),
    )

    fun statusDetail(app: DealerApplication): OutboundMessage {
        val body = buildString {
            append("*${app.applicationNumber}*\n")
            append("Status: ${app.status.label}\n")
            append("Created: ${fmt(app.createdAt)}\n")
            append("Last update: ${fmt(app.updatedAt)}")
            app.resubmissionReason?.takeIf { app.status == ApplicationStatus.RESUBMISSION_REQUESTED }
                ?.let { append("\n\nResubmission reason: $it") }
            app.decisionNote?.takeIf { app.status.isFinal }?.let { append("\n\nNote: $it") }
            if (app.customerPhone != null) append("\nCustomer: ${app.extractedName ?: app.customerName ?: "linked"} (+${app.customerPhone})")
            app.idVerificationStatus?.let { append("\nID verification: ${it.label}") }
            app.creditCheckStatus?.let { append("\nCredit bureau: ${it.label}${app.creditScore?.let { s -> ", score $s" } ?: ""}") }
            when (app.status) {
                ApplicationStatus.DRAFT -> append("\n\nThe form has not been submitted. Need the link again? Tap Get new link.")
                ApplicationStatus.RESUBMISSION_REQUESTED -> append("\n\nTap Resubmit now to receive a fresh one-time link.")
                ApplicationStatus.SUBMITTED, ApplicationStatus.READY_FOR_REVIEW -> append("\n\nWe will notify you here as soon as a decision is made.")
                else -> Unit
            }
        }
        val buttons = buildList {
            when (app.status) {
                ApplicationStatus.DRAFT -> add(Button(ReplyIds.link(app.applicationNumber), "Get new link"))
                ApplicationStatus.RESUBMISSION_REQUESTED -> add(Button(ReplyIds.link(app.applicationNumber), "Resubmit now"))
                else -> Unit
            }
            add(mainMenuButton)
        }
        return Buttons(header = "Application status", body = body, buttons = buttons)
    }

    fun linkReissued(link: IssuedLink): OutboundMessage = Buttons(
        header = "New one-time link",
        body = "Here is a fresh link for *${link.application.applicationNumber}* (valid until ${fmt(link.expiresAt)}):\n${link.url}\n\n" +
            "Previous links for this application no longer work.",
        buttons = listOf(Button(ReplyIds.status(link.application.applicationNumber), "Check status"), mainMenuButton),
    )

    fun linkUnavailable(app: DealerApplication): OutboundMessage = Buttons(
        body = "A new link cannot be issued for *${app.applicationNumber}* because its status is \"${app.status.label}\".",
        buttons = listOf(Button(ReplyIds.status(app.applicationNumber), "Check status"), mainMenuButton),
    )

    fun unknownApplication(): OutboundMessage = Buttons(
        body = "We could not find that application under your dealer account. Please pick one from the list.",
        buttons = listOf(Button(ReplyIds.MENU_STATUS, "Check status"), mainMenuButton),
    )

    fun help(): OutboundMessage = Buttons(
        header = "Help",
        body = "This assistant lets registered dealers:\n" +
            "- start a new application and receive a one-time form link,\n" +
            "- check the status of existing applications,\n" +
            "- receive requests for resubmission and final decisions here.\n\n" +
            "Use the buttons and menus to navigate. Type MENU at any time to return to the main menu.",
        buttons = listOf(mainMenuButton),
    )

    fun didNotUnderstand(dealer: Dealer): OutboundMessage =
        mainMenu(dealer, "Sorry, I did not understand that. Please choose an option below.")

    fun imageReceived(mediaId: String): OutboundMessage = Text("Image received, ID: $mediaId")

    fun unsupportedType(dealer: Dealer, type: String): OutboundMessage =
        mainMenu(dealer, "I cannot process $type messages here. Please use the menu below.")

    // --- notifications pushed by the back office / form ---

    fun submitted(app: DealerApplication): OutboundMessage = Buttons(
        header = "Application received",
        body = "Thank you. Application *${app.applicationNumber}* was submitted on ${fmt(app.updatedAt)} and is now under review.\n\n" +
            "We will notify you here when there is a decision.",
        buttons = listOf(Button(ReplyIds.status(app.applicationNumber), "Check status"), mainMenuButton),
    )

    fun resubmissionRequested(link: IssuedLink): OutboundMessage = Buttons(
        header = "Resubmission requested",
        body = "Application *${link.application.applicationNumber}* needs to be resubmitted.\n\n" +
            "Reason: ${link.application.resubmissionReason ?: "not specified"}\n\n" +
            "Please update and resubmit the form using this one-time link (valid until ${fmt(link.expiresAt)}):\n${link.url}",
        buttons = listOf(Button(ReplyIds.status(link.application.applicationNumber), "Check status"), mainMenuButton),
    )

    /** Documents are complete but some data could not be read automatically, so no provider check was run. */
    fun documentsForOfficerReview(app: DealerApplication, risk: RiskAssessment? = null): OutboundMessage = Buttons(
        header = "Documents submitted",
        body = "Customer documents for *${app.applicationNumber}* are complete and have been handed to the review team, " +
            "because some data on the KTP could not be confirmed automatically.\n\n" +
            "Customer: ${app.extractedName ?: app.customerName ?: "unknown"} (+${app.customerPhone ?: "?"})\n" +
            riskLines(risk) +
            "Status: ${app.status.label}.\n\nWe will notify you here as soon as a decision is made.",
        buttons = listOf(Button(ReplyIds.status(app.applicationNumber), "Check status"), mainMenuButton),
    )

    fun documentsSubmitted(outcome: VerificationOutcome, risk: RiskAssessment? = null): OutboundMessage {
        val app = outcome.application
        return Buttons(
            header = "Documents submitted",
            body = "Customer documents for *${app.applicationNumber}* are complete.\n\n" +
                "Customer: ${app.extractedName ?: app.customerName ?: "unknown"} (+${app.customerPhone ?: "?"})\n" +
                "NIK: ${app.extractedNik?.let { "***" + it.takeLast(4) } ?: "n/a"}\n" +
                "ID verification: ${outcome.id.outcome.label}\n" +
                "Credit bureau: ${outcome.credit.outcome.label}${outcome.credit.score?.let { ", score $it" } ?: ""}\n" +
                riskLines(risk) + "\n" +
                "Status: ${app.status.label}.",
            buttons = listOf(Button(ReplyIds.status(app.applicationNumber), "Check status"), mainMenuButton),
        )
    }

    fun decision(app: DealerApplication): OutboundMessage {
        val approved = app.status == ApplicationStatus.APPROVED
        return Buttons(
            header = if (approved) "Application approved" else "Application rejected",
            body = "Application *${app.applicationNumber}* has been *${if (approved) "APPROVED" else "REJECTED"}* on ${fmt(app.updatedAt)}." +
                (app.decisionNote?.let { "\n\nNote: $it" } ?: "") +
                (if (approved) "\n\nCongratulations! Our team will be in touch about next steps." else "\n\nYou can start a new application at any time."),
            buttons = listOf(Button(ReplyIds.MENU_NEW, "New application"), mainMenuButton),
        )
    }

    private fun fmt(instant: Instant): String = dateFormat.format(instant)

    /** "Risk grade" and badge lines for the dealer summary; empty when no assessment was made. */
    private fun riskLines(risk: RiskAssessment?): String {
        if (risk == null) return ""
        val fraud = risk.fraudFlags.size
        val warnings = when {
            fraud > 0 -> " - ⚠️ $fraud fraud/forgery warning${if (fraud > 1) "s" else ""}"
            risk.flags.isEmpty() -> " - no warnings"
            else -> " - ${risk.flags.size} warning${if (risk.flags.size > 1) "s" else ""}"
        }
        val badges = if (risk.badges.isEmpty()) "" else "\nBadges: " + risk.badges.joinToString(", ") { it.label }
        return "Risk grade: *${risk.grade.name}* (${risk.grade.label})$warnings$badges\n"
    }
}
