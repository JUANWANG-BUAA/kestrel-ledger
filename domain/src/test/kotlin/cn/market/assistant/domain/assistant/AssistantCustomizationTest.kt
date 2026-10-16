package cn.market.assistant.domain.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantCustomizationTest {
    private val weekly = AssistantSkill(
        id = "weekly-review",
        name = "周报复盘",
        description = "每周收益、回撤与配置变化",
        instructions = "1. 先给本周盈亏与 TWR\n2. 再给最大回撤与前三贡献\n3. 最后说明数据缺口",
        toolIds = listOf("portfolio.period_report", "market.quote"),
        triggerWords = listOf("周报", "本周", "复盘")
    )
    private val valuation = AssistantSkill(
        id = "valuation",
        name = "估值解读",
        description = "解读基金盘中估算与误差区间",
        instructions = "解释估算方法与误差，不预测涨跌。",
        triggerWords = listOf("估值", "估算")
    )
    private val disabled = AssistantSkill(
        id = "disabled",
        name = "停用技能",
        description = "",
        instructions = "",
        triggerWords = listOf("周报"),
        enabled = false
    )

    @Test
    fun explicitSlashCommandWinsOverTriggerWords() {
        val question = "/valuation 帮我看看这个基金"

        assertEquals("valuation", AssistantSkillMatcher.explicitId(question, listOf(weekly, valuation)))
        assertEquals("valuation", AssistantSkillMatcher.match(question, listOf(weekly, valuation))?.id)
        assertNull(AssistantSkillMatcher.explicitId("看看估值", listOf(weekly, valuation)))
    }

    @Test
    fun triggerWordsAndNamesMatchButDisabledSkillsNeverDo() {
        assertEquals("weekly-review", AssistantSkillMatcher.match("帮我做一下本周复盘", listOf(weekly, valuation))?.id)
        assertEquals("valuation", AssistantSkillMatcher.match("这个基金的估算误差多大", listOf(weekly, valuation))?.id)
        assertNull(AssistantSkillMatcher.match("今天天气如何", listOf(weekly, valuation)))
        assertNull(AssistantSkillMatcher.match("周报复盘", listOf(disabled)))
    }

    @Test
    fun composedPromptCarriesInstructionsAndToolScope() {
        val prompt = AssistantSkillMatcher.composeSystemPrompt("基础提示", weekly)

        assertTrue(prompt.startsWith("基础提示"))
        assertTrue(prompt.contains("【当前技能：周报复盘】"))
        assertTrue(prompt.contains("先给本周盈亏与 TWR"))
        assertTrue(prompt.contains("portfolio.period_report"))
        assertEquals("基础提示", AssistantSkillMatcher.composeSystemPrompt("基础提示", null))
    }

    @Test
    fun habitSummaryCountsUsagePerKind() {
        val events = listOf(
            memory(MemoryKind.TOOL_USE, "portfolio.period_report", 1_000),
            memory(MemoryKind.TOOL_USE, "portfolio.period_report", 2_000),
            memory(MemoryKind.TOOL_USE, "market.quote", 3_000),
            memory(MemoryKind.SKILL_USE, "weekly-review", 4_000),
            memory(MemoryKind.INSTRUMENT, "1.518880", 5_000),
            memory(MemoryKind.QUESTION, "本周收益怎么样", 6_000)
        )

        val summary = AssistantMemoryRanker.summarize(events)

        assertEquals(6, summary.totalEvents)
        assertEquals("portfolio.period_report", summary.topTools.first().key)
        assertEquals(2, summary.topTools.first().count)
        assertEquals("weekly-review", summary.topSkills.single().key)
        assertEquals("1.518880", summary.topInstruments.single().key)
        assertEquals(6_000L, summary.lastActiveAtMillis)
        assertFalse(summary.isEmpty)
    }

    @Test
    fun retrievalScoresByOverlapAndRecency() {
        val now = 1_000_000_000_000L
        val events = listOf(
            memory(MemoryKind.INSTRUMENT, "黄金 ETF 518880", now - 1_000),
            memory(MemoryKind.QUESTION, "帮我看看黄金价格", now - 2_000),
            memory(MemoryKind.QUESTION, "半导体板块怎么样", now - 90L * 24 * 60 * 60 * 1_000)
        )

        val retrieved = AssistantMemoryRanker.retrieve("黄金现在什么价格", events, limit = 3, nowMillis = now)

        assertTrue(retrieved.isNotEmpty())
        assertTrue(retrieved.first().key.contains("黄金"))
        assertFalse(retrieved.any { it.key.contains("半导体") })
    }

    @Test
    fun habitPromptExplainsThatHabitsAreNotFacts() {
        val summary = AssistantMemoryRanker.summarize(
            listOf(
                memory(MemoryKind.TOOL_USE, "market.quote", 1_000),
                memory(MemoryKind.SKILL_USE, "weekly-review", 2_000)
            )
        )

        val prompt = AssistantMemoryRanker.habitPrompt(summary, listOf(MemoryCount("黄金", 3, 3_000)))

        requireNotNull(prompt)
        assertTrue(prompt.contains("不代表事实依据"))
        assertTrue(prompt.contains("常用工具"))
        assertTrue(prompt.contains("与本次问题相关的历史"))
        assertNull(AssistantMemoryRanker.habitPrompt(HabitSummary()))
    }

    @Test
    fun workflowCodecRoundTripsTabsAndNewlines() {
        val turns = listOf(
            WorkflowTurn(
                question = "第一行\n第二行\t带制表符",
                answer = "回答里有\n换行和 emoji 📈 与 「引号」",
                skillId = "weekly-review",
                toolIds = listOf("portfolio.period_report", "market.quote"),
                atMillis = 1_763_000_000_000,
                failed = true
            ),
            WorkflowTurn(question = "第二轮", answer = "完成", atMillis = 1_763_000_100_000)
        )

        val decoded = WorkflowCodec.decode(WorkflowCodec.encode(turns))

        assertEquals(2, decoded.size)
        assertEquals(turns[0].question, decoded[0].question)
        assertEquals(turns[0].answer, decoded[0].answer)
        assertEquals(listOf("portfolio.period_report", "market.quote"), decoded[0].toolIds)
        assertTrue(decoded[0].failed)
        assertNull(decoded[1].skillId)
        assertFalse(decoded[1].failed)
        assertTrue(WorkflowCodec.decode("").isEmpty())
        assertTrue(WorkflowCodec.decode("garbage-line-without-fields").isEmpty())
    }

    @Test
    fun workflowExportProducesAResumableMarkdownLog() {
        val workflow = AssistantWorkflow(
            id = "wf-1",
            title = "本周复盘",
            status = WorkflowStatus.ACTIVE,
            turns = listOf(
                WorkflowTurn("本周收益", "TWR +1.2%（来源：本地快照）", skillId = "weekly-review", toolIds = listOf("portfolio.period_report"))
            ),
            createdAtMillis = 1_763_000_000_000,
            updatedAtMillis = 1_763_000_000_000
        )

        val markdown = WorkflowExport.toMarkdown(workflow, 1_763_000_500_000)

        assertTrue(markdown.contains("# 养基助手 · 会话记录：本周复盘"))
        assertTrue(markdown.contains("**我**：本周收益"))
        assertTrue(markdown.contains("（技能：weekly-review）"))
        assertTrue(markdown.contains("portfolio.period_report"))
        assertTrue(markdown.contains("不构成投资建议"))
    }

    private fun memory(kind: MemoryKind, key: String, atMillis: Long) =
        MemoryEvent(kind = kind, key = key, atMillis = atMillis)
}
