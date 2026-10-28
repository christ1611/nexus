package com.nextasean.wawebhook.review

import com.anthropic.models.messages.MessageParam

/**
 * Turns a copilot conversation kept in the browser into alternating user/assistant messages ending with the new
 * question. Consecutive same-role turns are merged, a leading assistant turn and a trailing user turn are dropped,
 * and the reviewer's name is prefixed to the question when known. Shared by the review and dashboard copilots.
 */
fun copilotConversation(
    history: List<ReviewCopilotService.Turn>,
    question: String,
    reviewer: String?,
    maxHistory: Int = ReviewCopilotService.MAX_HISTORY,
): List<MessageParam> {
    val turns = mutableListOf<Pair<Boolean, String>>()
    for (t in history.takeLast(maxHistory)) {
        val content = t.content.trim()
        if (content.isEmpty()) continue
        val user = t.role != "assistant"
        if (turns.isNotEmpty() && turns.last().first == user) turns[turns.lastIndex] = user to (turns.last().second + "\n\n" + content)
        else turns += user to content
    }
    if (turns.isNotEmpty() && !turns.first().first) turns.removeAt(0)
    if (turns.isNotEmpty() && turns.last().first) turns.removeAt(turns.lastIndex)
    val asked = reviewer?.trim()?.ifBlank { null }?.let { "[Reviewer: $it] $question" } ?: question
    turns += true to asked
    return turns.map { (user, content) ->
        MessageParam.builder().role(if (user) MessageParam.Role.USER else MessageParam.Role.ASSISTANT).content(content).build()
    }
}
