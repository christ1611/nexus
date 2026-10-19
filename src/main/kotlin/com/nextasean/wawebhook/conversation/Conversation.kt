package com.nextasean.wawebhook.conversation

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.time.Instant

enum class ConversationState {
    /** No pending prompt; any message shows the main menu. */
    IDLE,
    /** Main menu list was sent; waiting for a selection. */
    MAIN_MENU,
    /** "Create a new application?" buttons were sent. */
    NEW_APPLICATION_CONFIRM,
    /** List of the dealer's applications was sent; waiting for one to be picked. */
    STATUS_SELECT,
}

@Table("CONVERSATIONS")
data class Conversation(
    @Id val id: Long? = null,
    val dealerId: Long,
    val state: ConversationState = ConversationState.IDLE,
    val context: String? = null,
    val lastMessageId: String? = null,
    val updatedAt: Instant = Instant.now(),
)

interface ConversationRepository : CrudRepository<Conversation, Long> {
    fun findByDealerId(dealerId: Long): Conversation?
}
