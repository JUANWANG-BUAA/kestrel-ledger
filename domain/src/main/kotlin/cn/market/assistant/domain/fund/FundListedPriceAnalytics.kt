package cn.market.assistant.domain.fund

import cn.market.assistant.domain.market.MarketQuote
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Validates exact listed-fund quotes before comparing them with the latest published NAV. */
object FundListedPriceAnalytics {
    private const val MAX_QUOTE_AGE_SECONDS = 36 * 60 * 60L
    private const val MAX_NAV_AGE_DAYS = 14L
    private val marketZone = ZoneId.of("Asia/Shanghai")

    /** Returns the EastMoney exchange ID for supported Shanghai and Shenzhen listed fund codes. */
    fun marketSecid(code: String): String? {
        val normalized = code.trim()
        if (!normalized.matches(Regex("\\d{6}"))) return null
        val exchange = when {
            normalized.startsWith('5') -> "1"
            normalized.startsWith("15") || normalized.startsWith("16") || normalized.startsWith("18") -> "0"
            else -> return null
        }
        return "$exchange.$normalized"
    }

    fun compare(
        profile: FundProfile,
        quote: MarketQuote?,
        now: Long = System.currentTimeMillis() / 1000
    ): FundMarketPriceComparison {
        val secid = marketSecid(profile.code).orEmpty()
        val today = runCatching { Instant.ofEpochSecond(now).atZone(marketZone).toLocalDate() }
            .getOrElse { LocalDate.now(marketZone) }
        val latestPoint = profile.navHistory
            .filter { point ->
                point.unitNav.isFinite() && point.unitNav > 0 &&
                    runCatching { LocalDate.parse(point.day).let { !it.isAfter(today) } }.getOrDefault(false)
            }
            .maxByOrNull { it.day }
        val nav = latestPoint?.unitNav ?: profile.unitNav?.takeIf { it.isFinite() && it > 0 }
        val navDate = latestPoint?.day ?: profile.navDate
        val freshNavDate = navDate?.let { value ->
            runCatching { LocalDate.parse(value) }.getOrNull()?.let { day ->
                !day.isAfter(today) && ChronoUnit.DAYS.between(day, today) in 0..MAX_NAV_AGE_DAYS
            }
        } == true
        val sourceTime = quote?.quoteTime?.takeIf { it > 0 }
        val quoteAge = sourceTime?.let { now - it }
        val quoteFresh = quote != null && quote.secid == secid && quote.price.isFinite() && quote.price > 0 &&
            quote.quoteTime > 0 && quote.fetchedAt > 0 && quote.quoteTime <= now + 300 &&
            quote.fetchedAt <= now + 300 && quoteAge in 0..MAX_QUOTE_AGE_SECONDS &&
            now - quote.fetchedAt in 0..MAX_QUOTE_AGE_SECONDS
        val currentMarketPrice = quote?.price?.takeIf { quoteFresh }
        val comparisonPct = if (currentMarketPrice != null && nav != null && freshNavDate) {
            ((currentMarketPrice / nav) - 1.0).times(100.0).takeIf(Double::isFinite)
        } else null
        val reason = when {
            secid.isBlank() -> "该代码不属于已支持的场内基金代码"
            quote == null -> "场内报价暂不可用"
            quote.secid != secid -> "报价代码与基金代码不匹配"
            !quoteFresh -> "场内报价已过期或字段无效"
            nav == null || navDate.isNullOrBlank() -> "缺少最近公布的基金净值"
            !freshNavDate -> "最近公布净值日期超过 14 天，仅显示场内市价"
            comparisonPct == null -> "无法计算场内市价与公布净值的差异"
            else -> null
        }
        return FundMarketPriceComparison(
            marketSecid = secid,
            marketPrice = currentMarketPrice,
            latestNav = nav,
            navDate = navDate,
            premiumDiscountPct = comparisonPct,
            source = quote?.source,
            quoteTime = sourceTime,
            reason = reason
        )
    }
}
