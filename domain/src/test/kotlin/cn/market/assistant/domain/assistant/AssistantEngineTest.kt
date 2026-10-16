package cn.market.assistant.domain.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantEngineTest {
    private val publicFact = AssistantFact(
        key = "market.quote.1.518880.price",
        value = "5.1234",
        unit = "CNY/g",
        source = "sge-au9999",
        observedAt = "2026-10-16T14:30:00+08:00",
        sensitivity = FactSensitivity.PUBLIC_MARKET
    )
    private val aggregateFact = AssistantFact(
        key = "portfolio.report.account_twr_pct",
        value = "+2.10",
        unit = "%",
        source = "本地快照与交易流水",
        observedAt = "2026-10-18T16:00:00+08:00",
        sensitivity = FactSensitivity.AGGREGATE_PORTFOLIO
    )
    private val privateFact = AssistantFact(
        key = "portfolio.report.allocation.000001.weight_pct",
        value = "77.67",
        unit = "%",
        source = "本地快照与交易流水",
        observedAt = "2026-10-18T16:00:00+08:00",
        sensitivity = FactSensitivity.PRIVATE_DETAIL
    )

    @Test
    fun classifiesChineseQuestionIntents() {
        assertEquals(AssistantIntent.FUND_ESTIMATE, AssistantEngine.classify("这只基金盘中估值多少"))
        assertEquals(AssistantIntent.RISK, AssistantEngine.classify("我的组合回撤和波动怎么样"))
        assertEquals(AssistantIntent.PERIOD_REPORT, AssistantEngine.classify("本周收益报告"))
        assertEquals(AssistantIntent.MARKET_QUOTE, AssistantEngine.classify("黄金现在什么价格"))
        assertEquals(AssistantIntent.PORTFOLIO_OVERVIEW, AssistantEngine.classify("我持有哪些资产"))
        assertEquals(AssistantIntent.HELP, AssistantEngine.classify("这个应用能做什么"))
    }

    @Test
    fun adviceRequestsAreRefusedWithoutCitations() {
        val refusal = AssistantEngine.findAdviceRequest("现在应该买入哪只基金")
        requireNotNull(refusal)

        val (_, reply) = AssistantEngine.ask(
            AssistantSessionState("c1"),
            "帮我推荐一只必涨的基金",
            listOf(publicFact, privateFact)
        )

        assertTrue(reply.text.contains("不提供买卖建议"))
        assertTrue(reply.citations.isEmpty())
    }

    @Test
    fun privateFactsStayOutUnlessTheSessionOptsIn() {
        val (aggregateSession, aggregateReply) = AssistantEngine.ask(
            AssistantSessionState("c1"),
            "本周收益报告",
            listOf(publicFact, aggregateFact, privateFact)
        )
        assertTrue(aggregateReply.text.contains("+2.10"))
        assertFalse(aggregateReply.text.contains("77.67"))
        assertTrue(aggregateSession.turns.single().reply.text.contains("未参与回答"))

        val (_, detailedReply) = AssistantEngine.ask(
            AssistantSessionState("c2", AssistantDataSharingConsent.INCLUDE_PRIVATE_DETAILS),
            "本周收益报告",
            listOf(publicFact, aggregateFact, privateFact)
        )
        assertTrue(detailedReply.text.contains("77.67"))
    }

    @Test
    fun followUpQuestionsReuseThePreviousIntent() {
        val state = AssistantSessionState("c1")
        val (afterMarket, marketReply) = AssistantEngine.ask(state, "黄金现在什么价格", listOf(publicFact))
        assertEquals(AssistantIntent.MARKET_QUOTE, afterMarket.turns.single().intent)
        assertTrue(marketReply.citations.single().source == "sge-au9999")

        val (afterFollowUp, followUpReply) = AssistantEngine.ask(afterMarket, "它呢", listOf(publicFact))
        assertEquals(AssistantIntent.MARKET_QUOTE, afterFollowUp.turns.last().intent)
        assertTrue(followUpReply.text.contains("sge-au9999"))
    }

    @Test
    fun citationsCarrySourceAndObservedAtAndRespectTheAnswerLimit() {
        val reply = AssistantEngine.answer(
            AssistantRequest(
                conversationId = "c1",
                question = "本周收益报告",
                facts = listOf(aggregateFact),
                dataSharingConsent = AssistantDataSharingConsent.PUBLIC_AND_AGGREGATE
            )
        )

        assertEquals("portfolio.report.account_twr_pct", reply.citations.single().factKey)
        assertEquals("2026-10-18T16:00:00+08:00", reply.citations.single().observedAt)
        assertTrue(reply.text.contains("事实汇总"))
    }

    @Test
    fun goldQuestionRanksGoldQuotesAboveUnrelatedInstruments() {
        val equity = AssistantFact(
            key = "market.quote.1.000300.price",
            value = "4166.01",
            unit = "点位",
            source = "tencent",
            observedAt = "2026-10-16T00:56:04Z",
            sensitivity = FactSensitivity.PUBLIC_MARKET
        )
        val gold = AssistantFact(
            key = "market.quote.GOLD.SGE.AU9999.price",
            value = "960.50",
            unit = "CNY/g",
            source = "sge-au9999",
            observedAt = "2026-10-16T00:56:41Z",
            sensitivity = FactSensitivity.PUBLIC_MARKET
        )

        val (_, reply) = AssistantEngine.ask(
            AssistantSessionState("test"),
            "黄金价格",
            listOf(equity, gold)
        )

        assertTrue(reply.citations.first().factKey.contains("GOLD"))
        assertTrue(reply.text.indexOf("GOLD.SGE.AU9999") < reply.text.indexOf("000300"))
    }

    @Test
    fun emptyFactSetExplainsWhatToDoNextInsteadOfInventingNumbers() {
        val (_, reply) = AssistantEngine.ask(AssistantSessionState("c1"), "我持有哪些资产", emptyList())

        assertTrue(reply.text.contains("没有可引用的事实数据"))
        assertTrue(reply.citations.isEmpty())
    }
}
