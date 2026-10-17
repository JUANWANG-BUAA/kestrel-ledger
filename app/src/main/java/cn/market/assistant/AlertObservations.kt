package cn.market.assistant

import cn.market.assistant.domain.alert.AlertObservation
import cn.market.assistant.domain.alert.AlertRule
import cn.market.assistant.domain.alert.AlertRuleKind
import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.portfolio.Position
import java.math.BigDecimal
import java.math.RoundingMode

/** Turns local ledger state and cached quotes into one factual observation per rule. */
internal object AlertObservations {
    fun build(
        rules: List<AlertRule>,
        quotes: Map<String, MarketQuote>,
        positions: List<Position>,
        nowMillis: Long = System.currentTimeMillis()
    ): Map<String, AlertObservation> {
        val accountCost = positions.fold(BigDecimal.ZERO) { sum, position -> sum + position.cost }
        val valuationComplete = positions.isNotEmpty() && positions.all { it.value != null }
        // A missing quote is unknown, not zero: suppress account P&L alerts until every holding has
        // a value. Add realized P&L so the percentage matches the account's cumulative result.
        val accountProfit = if (valuationComplete) {
            positions.fold(BigDecimal.ZERO) { sum, position ->
                sum + (position.holdingProfit ?: BigDecimal.ZERO) + position.realized
            }
        } else null
        val accountProfitPct = if (accountCost.signum() > 0 && accountProfit != null) {
            accountProfit.multiply(BigDecimal("100")).divide(accountCost, 4, RoundingMode.HALF_UP).toDouble()
        } else {
            null
        }
        val domesticGold = quotes["GOLD.SGE.AU9999"]?.price
        val internationalGram = quotes["GOLD.XAU.CNY.GRAM"]?.price
        val goldPremiumPct = if (domesticGold != null && internationalGram != null && internationalGram > 0) {
            (domesticGold / internationalGram - 1.0) * 100.0
        } else {
            null
        }
        // Staleness is the oldest cached quote, not the newest: one fresh index quote must not hide
        // an old fund/GOLD quote that a rule may actually depend on.
        val oldestQuoteMillis = quotes.values
            .mapNotNull { quote -> quote.fetchedAt.takeIf { it > 0 }?.times(1_000) }
            .minOrNull()
        val staleHours = oldestQuoteMillis?.let { (nowMillis - it) / 3_600_000.0 }

        return rules.associate { rule ->
            val observation = when (rule.kind) {
                AlertRuleKind.PRICE_ABOVE, AlertRuleKind.PRICE_BELOW -> {
                    val quote = rule.targetId?.let { quotes[it] } ?: rule.targetId?.let { targetId ->
                        positions.firstOrNull { it.asset.symbol == targetId && it.quote != null }?.let { position ->
                            MarketQuote(
                                secid = position.asset.symbol,
                                name = position.asset.name,
                                price = position.quote!!.toDouble(),
                                change = 0.0,
                                changePct = 0.0,
                                quoteTime = 0,
                                fetchedAt = 0,
                                source = position.quoteSource ?: "本地账本"
                            )
                        }
                    }
                    AlertObservation(
                        value = quote?.price,
                        source = quote?.source,
                        observedAtMillis = quote?.quoteTime?.takeIf { it > 0 }?.times(1_000),
                        description = quote?.name ?: rule.targetLabel
                    )
                }
                AlertRuleKind.DAILY_CHANGE_ABOVE, AlertRuleKind.DAILY_CHANGE_BELOW -> {
                    val quote = rule.targetId?.let { quotes[it] }
                    AlertObservation(
                        value = quote?.changePct,
                        source = quote?.source,
                        observedAtMillis = quote?.quoteTime?.takeIf { it > 0 }?.times(1_000),
                        description = quote?.name ?: rule.targetLabel
                    )
                }
                AlertRuleKind.HOLDING_PROFIT_ABOVE, AlertRuleKind.HOLDING_LOSS_BELOW -> AlertObservation(
                    value = accountProfitPct,
                    source = "本地加密账本",
                    observedAtMillis = nowMillis,
                    description = "账户持仓 ${positions.size} 项"
                )
                AlertRuleKind.FUND_ESTIMATE_ABOVE -> AlertObservation(
                    value = null,
                    source = null,
                    observedAtMillis = nowMillis,
                    description = "当前版本未接入基金盘中估算，规则不会触发"
                )
                AlertRuleKind.GOLD_PREMIUM_ABOVE -> AlertObservation(
                    value = goldPremiumPct,
                    source = "sge-au9999 + 国际金价折算",
                    observedAtMillis = quotes["GOLD.SGE.AU9999"]?.quoteTime?.takeIf { it > 0 }?.times(1_000),
                    description = "境内金价相对国际折算价"
                )
                AlertRuleKind.QUOTE_STALE -> AlertObservation(
                    value = staleHours,
                    source = "本机行情缓存",
                    observedAtMillis = oldestQuoteMillis,
                    description = "以最新缓存报价计算"
                )
            }
            rule.id to observation
        }
    }
}
