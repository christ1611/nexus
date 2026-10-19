package com.nextasean.wawebhook.conversation

import com.nextasean.wawebhook.application.ApplicationService
import com.nextasean.wawebhook.application.DealerApplication
import com.nextasean.wawebhook.config.AppProperties
import com.nextasean.wawebhook.dealer.Dealer
import com.nextasean.wawebhook.dealer.DealerRepository
import com.nextasean.wawebhook.webhook.Message
import com.nextasean.wawebhook.whatsapp.BusinessPhoneHolder
import com.nextasean.wawebhook.whatsapp.OutboundMessage
import com.nextasean.wawebhook.whatsapp.WhatsAppSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** What the dealer sent, reduced to what the state machine cares about. */
sealed interface Input {
    data class Text(val text: String) : Input
    data class Selection(val id: String, val title: String?) : Input
    data class Image(val mediaId: String?, val caption: String? = null) : Input
    data class Other(val type: String) : Input

    companion object {
        fun of(message: Message): Input = when (message.type) {
            "text" -> Text(message.text?.body.orEmpty().trim())
            "interactive" -> (message.interactive?.buttonReply ?: message.interactive?.listReply)
                ?.let { Selection(it.id.orEmpty(), it.title) } ?: Other("interactive")
            "button" -> Selection(message.button?.payload.orEmpty(), message.button?.text)
            "image" -> Image(message.image?.id, message.image?.caption)
            else -> Other(message.type)
        }
    }
}

/**
 * The dealer conversation state machine. The current state lives in the `conversations` table;
 * every incoming message loads it, produces replies, and stores the next state.
 */
@Service
class DealerConversationService(
    private val dealers: DealerRepository,
    private val conversations: ConversationRepository,
    private val applications: ApplicationService,
    private val sender: WhatsAppSender,
    private val businessPhone: BusinessPhoneHolder,
    props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val messages = DealerMessages(props.zoneId)

    @Transactional
    fun handle(from: String, contactName: String?, message: Message) {
        val dealer = dealers.findByPhone(from)
        if (dealer == null || !dealer.active) {
            if (message.type in REPLYABLE_TYPES) {
                log.info("Unregistered number | from={} | type={} | replying with rejection", from, message.type)
                sender.send(from, messages.notRegistered(contactName))
            } else {
                log.info("Unregistered number | from={} | type={} | ignored", from, message.type)
            }
            return
        }

        val conversation = conversations.findByDealerId(dealer.id!!) ?: Conversation(dealerId = dealer.id)
        val input = Input.of(message)
        val next = when (input) {
            is Input.Selection -> onSelection(dealer, conversation, input)
            is Input.Text -> onText(dealer, conversation, input)
            is Input.Image -> {
                input.mediaId?.let { reply(dealer, messages.imageReceived(it)) }
                conversation.state
            }
            is Input.Other -> reply(dealer, messages.unsupportedType(dealer, input.type), ConversationState.MAIN_MENU)
        }
        conversations.save(conversation.copy(state = next, lastMessageId = message.id, updatedAt = Instant.now()))
        log.info("Conversation | dealer={} | {} -> {} | input={}", dealer.dealerCode, conversation.state, next, input)
    }

    /** Button and list selections carry their own IDs, so they are honoured from any state. */
    private fun onSelection(dealer: Dealer, conversation: Conversation, input: Input.Selection): ConversationState {
        val id = input.id
        return when {
            id == ReplyIds.MENU_NEW -> reply(dealer, messages.confirmNewApplication(), ConversationState.NEW_APPLICATION_CONFIRM)
            id == ReplyIds.NEW_CONFIRM -> createApplication(dealer)
            id == ReplyIds.NEW_CANCEL -> reply(dealer, messages.cancelled(dealer), ConversationState.MAIN_MENU)
            id == ReplyIds.MENU_STATUS -> showApplicationList(dealer)
            id == ReplyIds.MENU_HELP -> reply(dealer, messages.help(), ConversationState.MAIN_MENU)
            id == ReplyIds.MAIN_MENU -> reply(dealer, messages.mainMenu(dealer), ConversationState.MAIN_MENU)
            id.startsWith(ReplyIds.STATUS_PREFIX) -> showStatus(dealer, id.removePrefix(ReplyIds.STATUS_PREFIX))
            id.startsWith(ReplyIds.LINK_PREFIX) -> reissueLink(dealer, id.removePrefix(ReplyIds.LINK_PREFIX))
            else -> {
                log.warn("Unknown reply id | dealer={} | id={} | state={}", dealer.dealerCode, id, conversation.state)
                reply(dealer, messages.didNotUnderstand(dealer), ConversationState.MAIN_MENU)
            }
        }
    }

    /** Free text is interpreted in the light of the current state, with MENU as a global escape hatch. */
    private fun onText(dealer: Dealer, conversation: Conversation, input: Input.Text): ConversationState {
        val text = input.text.lowercase()
        if (text in MENU_WORDS || text.isEmpty()) {
            return reply(dealer, messages.mainMenu(dealer), ConversationState.MAIN_MENU)
        }
        return when (conversation.state) {
            ConversationState.NEW_APPLICATION_CONFIRM -> when (text) {
                in YES_WORDS -> createApplication(dealer)
                in NO_WORDS -> reply(dealer, messages.cancelled(dealer), ConversationState.MAIN_MENU)
                else -> reply(dealer, messages.confirmNewApplication(), ConversationState.NEW_APPLICATION_CONFIRM)
            }

            ConversationState.STATUS_SELECT -> {
                val app = ownedApplication(dealer, input.text)
                if (app != null) reply(dealer, messages.statusDetail(app), ConversationState.MAIN_MENU)
                else reply(dealer, messages.unknownApplication(), ConversationState.STATUS_SELECT)
            }

            ConversationState.IDLE -> reply(dealer, messages.mainMenu(dealer), ConversationState.MAIN_MENU)
            ConversationState.MAIN_MENU -> {
                // Allow typing an application number straight away as a shortcut.
                val app = ownedApplication(dealer, input.text)
                if (app != null) reply(dealer, messages.statusDetail(app), ConversationState.MAIN_MENU)
                else reply(dealer, messages.didNotUnderstand(dealer), ConversationState.MAIN_MENU)
            }
        }
    }

    private fun createApplication(dealer: Dealer): ConversationState {
        val link = applications.create(dealer)
        reply(dealer, messages.applicationCreated(link))
        val number = link.application.applicationNumber
        return reply(dealer, messages.customerStartLink(number, businessPhone.startLink(number)), ConversationState.MAIN_MENU)
    }

    private fun showApplicationList(dealer: Dealer): ConversationState {
        val apps = applications.recentForDealer(dealer.id!!)
        return if (apps.isEmpty()) reply(dealer, messages.noApplications(), ConversationState.MAIN_MENU)
        else reply(dealer, messages.chooseApplication(apps), ConversationState.STATUS_SELECT)
    }

    private fun showStatus(dealer: Dealer, number: String): ConversationState {
        val app = ownedApplication(dealer, number) ?: return reply(dealer, messages.unknownApplication(), ConversationState.MAIN_MENU)
        return reply(dealer, messages.statusDetail(app), ConversationState.MAIN_MENU)
    }

    private fun reissueLink(dealer: Dealer, number: String): ConversationState {
        val app = ownedApplication(dealer, number) ?: return reply(dealer, messages.unknownApplication(), ConversationState.MAIN_MENU)
        val link = applications.reissueLink(app)
        return if (link != null) reply(dealer, messages.linkReissued(link), ConversationState.MAIN_MENU)
        else reply(dealer, messages.linkUnavailable(app), ConversationState.MAIN_MENU)
    }

    private fun ownedApplication(dealer: Dealer, number: String): DealerApplication? =
        applications.findByNumber(number)?.takeIf { it.dealerId == dealer.id }

    private fun reply(dealer: Dealer, message: OutboundMessage, next: ConversationState = ConversationState.MAIN_MENU): ConversationState {
        sender.send(dealer.phone, message)
        return next
    }

    private companion object {
        val REPLYABLE_TYPES = setOf("text", "interactive", "button", "image", "document", "video", "audio", "sticker", "location", "contacts", "unsupported")
        val MENU_WORDS = setOf("menu", "hi", "hello", "hey", "start", "help", "halo", "0")
        val YES_WORDS = setOf("yes", "y", "ok", "oke", "ya", "sure", "create", "confirm")
        val NO_WORDS = setOf("no", "n", "cancel", "batal", "stop")
    }
}
