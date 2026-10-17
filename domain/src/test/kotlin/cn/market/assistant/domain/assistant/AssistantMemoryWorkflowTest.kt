package cn.market.assistant.domain.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantMemoryWorkflowTest {
    @Test
    fun explicitPreferencesAreSummarizedAndAddedToTheAssistantContext() {
        val summary = AssistantMemoryRanker.summarize(
            listOf(
                MemoryEvent(MemoryKind.PREFERENCE, "先看最大回撤，再看收益", 1_000L),
                MemoryEvent(MemoryKind.INSTRUMENT, "黄金", 2_000L)
            )
        )

        assertEquals("先看最大回撤，再看收益", summary.preferences.single().key)
        assertTrue(AssistantMemoryRanker.habitPrompt(summary).orEmpty().contains("先看最大回撤，再看收益"))
    }

    @Test
    fun workflowCodecKeepsAttachmentNamesAndOldPayloadsStillDecode() {
        val turn = WorkflowTurn(
            question = "分析这张截图",
            answer = "截图显示了价格走势",
            atMillis = 7L,
            attachmentNames = listOf("行情.png", "交易.csv")
        )
        val decoded = WorkflowCodec.decode(WorkflowCodec.encode(listOf(turn)))

        assertEquals(listOf("行情.png", "交易.csv"), decoded.single().attachmentNames)
        fun encoded(value: String) = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray())
        val legacy = listOf(
            encoded("分析"),
            encoded("回答"),
            "",
            "market.quote",
            "7",
            "0"
        ).joinToString("\t")
        assertTrue(WorkflowCodec.decode(legacy).single().attachmentNames.isEmpty())
    }
}
