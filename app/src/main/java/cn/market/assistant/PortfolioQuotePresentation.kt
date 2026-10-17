package cn.market.assistant

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val portfolioQuoteTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.of("Asia/Shanghai"))

internal fun portfolioQuoteSourceLabel(source: String?): String = when (source) {
    "MANUAL" -> "手动报价"
    "FUND_NAV" -> "基金净值"
    "MANUAL_BANK_BUYBACK" -> "银行报价 · 回购价估值"
    "sge-au9999" -> "上海黄金交易所 · Au99.99"
    "tencent" -> "腾讯行情"
    "eastmoney" -> "东方财富行情"
    null, "" -> "价格来源未知"
    else -> source
}

internal fun portfolioQuoteTimeLabel(value: String?): String = value?.let {
    runCatching { portfolioQuoteTimeFormatter.format(Instant.parse(it)) }.getOrNull()
} ?: "时点未知"

internal fun portfolioQuoteValidityLabel(value: String?): String = value?.let {
    runCatching { "有效至 ${portfolioQuoteTimeFormatter.format(Instant.parse(it))}" }.getOrNull()
} ?: "有效期未知"
