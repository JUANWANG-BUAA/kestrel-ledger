package cn.market.assistant.domain.assistant

/**
 * Offline, deterministic narration over typed facts. The engine never calls the network and never
 * emits investment advice; an optional provider can be plugged behind [AssistantAgent] later.
 */
enum class AssistantIntent(val label: String) {
    PORTFOLIO_OVERVIEW("持仓总览"),
    MARKET_QUOTE("行情查询"),
    PERIOD_REPORT("周期报告"),
    FUND_ESTIMATE("基金估值"),
    RISK("组合风险"),
    HELP("使用说明"),
    UNKNOWN("未识别")
}

data class AssistantTurn(
    val question: String,
    val intent: AssistantIntent,
    val reply: AssistantReply,
    val answeredAtMillis: Long
)

data class AssistantSessionState(
    val conversationId: String,
    val consent: AssistantDataSharingConsent = AssistantDataSharingConsent.PUBLIC_AND_AGGREGATE,
    val turns: List<AssistantTurn> = emptyList()
) {
    val lastIntent: AssistantIntent? get() = turns.lastOrNull()?.intent
}

object AssistantEngine {
    const val MAX_FACTS_PER_ANSWER = 12
    const val DEFAULT_ANSWER_LIMIT = 1_000

    private val ADVICE_KEYWORDS = listOf(
        "买入", "卖出", "加仓", "减仓", "清仓", "抄底", "止盈", "止损", "推荐", "荐股", "该买", "该卖",
        "能买", "要不要买", "必涨", "必跌", "稳赚", "保证收益", "目标价", "建仓", "换仓"
    )
    private val FOLLOW_UP_MARKERS = listOf("它", "他", "这个", "这只", "该基金", "刚才", "上面", "呢", "继续", "详细说")

    private val INTENT_KEYWORDS = listOf(
        AssistantIntent.FUND_ESTIMATE to listOf("估值", "估算", "预估", "预测", "盘中"),
        AssistantIntent.RISK to listOf("风险", "波动", "回撤", "var", "相关性", "集中度", "夏普", "sortino"),
        AssistantIntent.PERIOD_REPORT to listOf("报告", "周报", "月报", "日报", "收益", "表现", "复盘", "赚了多少"),
        AssistantIntent.MARKET_QUOTE to listOf("行情", "价格", "涨", "跌", "指数", "金价", "黄金", "多少"),
        AssistantIntent.PORTFOLIO_OVERVIEW to listOf("持仓", "资产", "市值", "总览", "我的", "账户", "盈亏"),
        AssistantIntent.HELP to listOf("帮助", "怎么用", "能做什么", "功能", "说明")
    )

    fun findAdviceRequest(question: String): String? =
        ADVICE_KEYWORDS.firstOrNull { question.contains(it, ignoreCase = true) }

    fun classify(question: String, previousIntent: AssistantIntent? = null): AssistantIntent {
        val normalized = question.lowercase()
        INTENT_KEYWORDS.forEach { (intent, keywords) ->
            if (keywords.any { normalized.contains(it) }) return intent
        }
        if (previousIntent != null && FOLLOW_UP_MARKERS.any { normalized.contains(it) }) {
            return previousIntent
        }
        return AssistantIntent.UNKNOWN
    }

    /** Visible facts after the session consent gate; private facts need an explicit opt-in. */
    fun visibleFacts(facts: List<AssistantFact>, consent: AssistantDataSharingConsent): List<AssistantFact> =
        when (consent) {
            AssistantDataSharingConsent.PUBLIC_AND_AGGREGATE ->
                facts.filter { it.sensitivity != FactSensitivity.PRIVATE_DETAIL }
            AssistantDataSharingConsent.INCLUDE_PRIVATE_DETAILS -> facts
        }

    fun ask(
        state: AssistantSessionState,
        question: String,
        facts: List<AssistantFact>,
        nowMillis: Long = System.currentTimeMillis()
    ): Pair<AssistantSessionState, AssistantReply> {
        val adviceRequest = findAdviceRequest(question)
        val previous = state.lastIntent
        val intent = if (adviceRequest != null) AssistantIntent.HELP else classify(question, previous)
        val reply = if (adviceRequest != null) {
            refusal()
        } else {
            val visible = visibleFacts(facts, state.consent)
            narrate(intent, visible, state.consent, question)
        }
        val turn = AssistantTurn(question, intent, reply, nowMillis)
        return state.copy(turns = (state.turns + turn).takeLast(MAX_TURNS)) to reply
    }

    fun answer(request: AssistantRequest): AssistantReply {
        val adviceRequest = findAdviceRequest(request.question)
        if (adviceRequest != null) return refusal()
        val intent = classify(request.question, previousIntent = null)
        val visible = visibleFacts(request.facts, request.dataSharingConsent)
        val reply = narrate(intent, visible, request.dataSharingConsent, request.question)
        return reply.copy(text = reply.text.take(request.answerLimitCharacters.coerceAtLeast(80)))
    }

    private fun narrate(
        intent: AssistantIntent,
        facts: List<AssistantFact>,
        consent: AssistantDataSharingConsent,
        question: String
    ): AssistantReply {
        if (facts.isEmpty()) {
            return AssistantReply(
                text = "目前没有可引用的事实数据。可以先在「记账」录入持仓，或等待行情与快照刷新后再问。"
            )
        }
        val selected = selectFacts(intent, facts, question)
        if (selected.isEmpty()) {
            return AssistantReply(
                text = "没有与「${intent.label}」匹配的已授权事实。可以换一个问法，或在设置中允许使用持仓明细。"
            )
        }
        val lines = mutableListOf<String>()
        lines += when (intent) {
            AssistantIntent.PORTFOLIO_OVERVIEW -> "以下是本地账本中的最新账户事实："
            AssistantIntent.MARKET_QUOTE -> "以下是已缓存行情（含来源与时点）："
            AssistantIntent.PERIOD_REPORT -> "以下是本周期的事实报告摘要："
            AssistantIntent.FUND_ESTIMATE -> "以下是已接入路由的基金估值事实（估算，非官方净值）："
            AssistantIntent.RISK -> "以下是基于历史快照的风险统计（描述性，不是预测）："
            AssistantIntent.HELP -> "我可以汇总本地账本与已缓存公开数据。可以这样问："
            AssistantIntent.UNKNOWN -> "这条问题暂时没有匹配到具体数据类型，先列出几条可用事实："
        }
        selected.forEach { fact ->
            val unit = fact.unit?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
            val source = fact.source?.takeIf { it.isNotBlank() }?.let { " · 来源 $it" } ?: ""
            val time = fact.observedAt?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
            lines += "· ${factLabel(fact.key)}：${fact.value}$unit$source$time"
        }
        if (consent == AssistantDataSharingConsent.PUBLIC_AND_AGGREGATE) {
            lines += "（当前仅使用公开与汇总事实；持仓明细未参与回答。）"
        }
        lines += "以上为事实汇总，不构成投资建议；估值均为估算。"
        val citations = selected.map { AssistantCitation(it.key, it.source, it.observedAt, it.value) }
        return AssistantReply(text = lines.joinToString("\n"), citations = citations)
    }

    private fun selectFacts(
        intent: AssistantIntent,
        facts: List<AssistantFact>,
        question: String
    ): List<AssistantFact> {
        val prefixes = when (intent) {
            AssistantIntent.PORTFOLIO_OVERVIEW -> listOf(
                "portfolio.report.account_allocation", "portfolio.report.account_", "portfolio.report.holdings_"
            )
            AssistantIntent.MARKET_QUOTE -> listOf("market.")
            AssistantIntent.PERIOD_REPORT -> listOf("portfolio.report.")
            AssistantIntent.FUND_ESTIMATE -> listOf("fund.")
            AssistantIntent.RISK -> listOf(
                "portfolio.report.account_max_drawdown", "portfolio.report.account_twr", "risk."
            )
            AssistantIntent.HELP -> listOf("portfolio.report.period")
            AssistantIntent.UNKNOWN -> listOf("")
        }
        val matched = facts.filter { fact -> prefixes.any { fact.key.startsWith(it) } }
        val fallback = matched.ifEmpty { facts }
        return fallback
            .sortedWith(
                compareByDescending<AssistantFact> { relevanceScore(it, question) }
                    .thenByDescending { it.sensitivity == FactSensitivity.PUBLIC_MARKET }
                    .thenBy { it.key }
            )
            .take(if (intent == AssistantIntent.UNKNOWN) MAX_FACTS_FOR_UNKNOWN else MAX_FACTS_PER_ANSWER)
    }

    private fun factLabel(key: String): String {
        val parts = key.split('.')
        if (parts.size >= 3 && parts[0] == "market" && parts[1] == "quote") {
            val instrument = if (parts.size > 4) {
                parts.drop(2).dropLast(1)
                    .filterNot { it.length == 1 && it.all(Char::isDigit) }
                    .joinToString("/")
            } else {
                ""
            }
            val field = when (parts.last()) {
                "instrument_id" -> "标的代码"
                "instrument_name" -> "标的名称"
                "price" -> "现价"
                "change_pct" -> "涨跌幅"
                "change" -> "涨跌额"
                "age_seconds" -> "延迟秒数"
                "fresh_within_72h" -> "数据时效检查"
                "volume" -> "成交量"
                "open" -> "开盘价"
                "high" -> "最高价"
                "low" -> "最低价"
                "close" -> "收盘价"
                else -> parts.last().replace('_', ' ')
            }
            return listOf(instrument, field).filter(String::isNotBlank).joinToString(" · ")
        }
        return when (key) {
            "portfolio.report.account_twr_pct" -> "账户时间加权收益率"
            "portfolio.report.account_max_drawdown_pct" -> "账户最大回撤"
            "portfolio.report.account_volatility_pct" -> "账户波动率"
            else -> key.substringAfterLast('.').replace('_', ' ')
        }
    }

    /**
     * Ranks facts by how many query term groups they match, so "黄金价格" answers with gold quotes
     * instead of the alphabetically first cached instrument.
     */
    private fun relevanceScore(fact: AssistantFact, question: String): Int {
        val query = question.lowercase()
        val haystack = "${fact.key} ${fact.value}".lowercase()
        return TERM_GROUPS.count { group -> group.any { query.contains(it) } && group.any { haystack.contains(it) } }
    }

    private fun refusal() = AssistantReply(ADVICE_REFUSAL)

    private const val MAX_TURNS = 20
    private const val MAX_FACTS_FOR_UNKNOWN = 4

    const val ADVICE_REFUSAL =
        "本应用只做本地事实汇总与复盘，不提供买卖建议、不做交易通道、不承诺收益。\n" +
            "可以问：我的持仓和盈亏、某只基金的盘中估值、本周收益与最大回撤、某标的的最新行情。"

    private val TERM_GROUPS = listOf(
        listOf("黄金", "金价", "金币", "au9999", "xau", "sge", "518880"),
        listOf("大盘", "指数", "沪深", "上证", "深证", "创业板", "科创", "纳指", "标普", "道指", "恒生", "日经", "韩国"),
        listOf("基金", "估值", "净值", "万份", "折溢价"),
        listOf("持仓", "资产", "账户", "盈亏", "市值", "现金"),
        listOf("风险", "波动", "回撤", "相关性", "集中度", "夏普"),
        listOf("报告", "周报", "月报", "日报", "收益", "复盘")
    )
}
