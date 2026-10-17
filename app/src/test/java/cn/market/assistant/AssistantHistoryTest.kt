package cn.market.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantHistoryTest {
    private fun turn(id: String, question: String, answer: String) = AssistantTurnUi(
        id = id,
        question = question,
        answer = answer
    )

    @Test
    fun privateConversationHistoryIsNotSent() {
        val history = assistantChatHistory(
            listOf(turn("1", "我的持仓金额是多少", "总资产 5293.04 元")),
            excludeTurnId = "2",
            includePreviousTurns = false
        )
        assertTrue(history.isEmpty())
    }

    @Test
    fun sharedConversationKeepsRecentTurnsAndExcludesCurrent() {
        val turns = (1..8).map { turn("$it", "问题$it", "回答$it") }
        val history = assistantChatHistory(turns, excludeTurnId = "4", includePreviousTurns = true)
        assertEquals(12, history.size)
        assertEquals("问题2", history.first().content)
        assertEquals("回答8", history.last().content)
        assertTrue(history.none { it.content == "问题4" || it.content == "回答4" })
    }
}
