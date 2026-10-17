package cn.market.assistant

import cn.market.assistant.domain.assistant.ChatMessage
import cn.market.assistant.domain.assistant.ChatRole

/**
 * Previous turns can contain portfolio amounts even when the current privacy switch is off. When
 * private details are not shared, send no prior conversation at all; the current question still goes
 * out with only the current turn's authorized facts.
 */
internal fun assistantChatHistory(
    turns: List<AssistantTurnUi>,
    excludeTurnId: String,
    includePreviousTurns: Boolean
): List<ChatMessage> {
    if (!includePreviousTurns) return emptyList()
    return turns.asSequence()
        .filter { it.id != excludeTurnId && it.answer.isNotBlank() }
        .toList()
        .takeLast(6)
        .flatMap { turn ->
            listOf(
                ChatMessage(ChatRole.USER, turn.question),
                ChatMessage(ChatRole.ASSISTANT, turn.answer)
            )
        }
}
