package cn.market.assistant.domain.alert

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

enum class AlertRuleKind(
    val label: String,
    val unit: String,
    val requiresTarget: Boolean,
    val explanation: String
) {
    PRICE_ABOVE("价格高于", "元/点", true, "标的最新价高于阈值时提醒"),
    PRICE_BELOW("价格低于", "元/点", true, "标的最新价低于阈值时提醒"),
    DAILY_CHANGE_ABOVE("单日涨幅超过", "%", true, "标的当日涨幅超过阈值时提醒"),
    DAILY_CHANGE_BELOW("单日跌幅超过", "%", true, "标的当日跌幅超过阈值时提醒"),
    HOLDING_PROFIT_ABOVE("持仓收益率高于", "%", false, "账户或持仓累计收益率高于阈值时提醒"),
    HOLDING_LOSS_BELOW("持仓亏损超过", "%", false, "账户或持仓累计亏损超过阈值时提醒"),
    FUND_ESTIMATE_ABOVE("基金估算涨幅超过", "%", true, "已接入路由的盘中估算涨幅超过阈值时提醒"),
    GOLD_PREMIUM_ABOVE("内外盘溢价超过", "%", false, "境内金价相对国际折算价的溢价超过阈值时提醒"),
    QUOTE_STALE("报价超过", "小时未更新", false, "关键报价超过阈值小时没有更新时提醒")
}

/** A user-configured threshold rule. Breach counts and fire history live with the rule on disk. */
data class AlertRule(
    val id: String,
    val kind: AlertRuleKind,
    val targetId: String? = null,
    val targetLabel: String? = null,
    val threshold: Double,
    val enabled: Boolean = true,
    /** Consecutive breaches required before firing; 1 fires on the first observation. */
    val requiredBreaches: Int = 1,
    val cooldownMinutes: Int = 60,
    val quietStartHour: Int? = null,
    val quietEndHour: Int? = null,
    val lastFiredAtMillis: Long = 0L,
    val triggerCount: Int = 0,
    val consecutiveBreaches: Int = 0,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    val note: String = ""
) {
    val target: String get() = targetLabel ?: targetId ?: "账户"
    val quietWindow: String?
        get() = if (quietStartHour == null || quietEndHour == null) null else "静默 $quietStartHour:00–$quietEndHour:00"
}

data class AlertObservation(
    val value: Double?,
    val source: String? = null,
    val observedAtMillis: Long? = null,
    val description: String? = null
)

sealed interface AlertDecision {
    data class Fire(val title: String, val body: String) : AlertDecision
    data class Suppressed(val reason: String) : AlertDecision
    data object Stay : AlertDecision
}

data class AlertOutcome(val rule: AlertRule, val decision: AlertDecision)

/**
 * Threshold engine with de-bounce, cooldown and quiet hours. It only ever emits factual statements
 * with source and time; it never suggests an action, and an unavailable value suppresses instead of
 * being treated as zero.
 */
object AlertEngine {
    private val zone = ZoneId.of("Asia/Shanghai")
    private const val MAX_OBSERVATION_AGE_MILLIS = 72L * 60 * 60 * 1000
    private const val MAX_FUTURE_SKEW_MILLIS = 5L * 60 * 1000

    fun evaluate(
        rule: AlertRule,
        observation: AlertObservation,
        nowMillis: Long = System.currentTimeMillis(),
        consecutiveBreaches: Int = rule.consecutiveBreaches
    ): AlertOutcome {
        if (!rule.enabled) return AlertOutcome(rule, AlertDecision.Suppressed("规则已停用"))
        val value = observation.value
        if (value == null || !value.isFinite()) {
            return AlertOutcome(rule.copy(consecutiveBreaches = 0), AlertDecision.Suppressed("缺少可用数值，未触发"))
        }
        val observedAt = observation.observedAtMillis
        if (rule.kind != AlertRuleKind.QUOTE_STALE && observedAt != null &&
            (observedAt > nowMillis + MAX_FUTURE_SKEW_MILLIS || nowMillis - observedAt > MAX_OBSERVATION_AGE_MILLIS)
        ) {
            return AlertOutcome(
                rule.copy(consecutiveBreaches = 0),
                AlertDecision.Suppressed("观测时间来自未来或已超过 72 小时，未触发")
            )
        }
        if (!breaches(rule, value)) {
            return AlertOutcome(rule.copy(consecutiveBreaches = 0), AlertDecision.Stay)
        }
        val breaches = consecutiveBreaches + 1
        if (breaches < rule.requiredBreaches.coerceAtLeast(1)) {
            return AlertOutcome(
                rule.copy(consecutiveBreaches = breaches),
                AlertDecision.Suppressed("连续 $breaches/${rule.requiredBreaches} 次达到阈值，等待确认")
            )
        }
        if (isQuietHour(rule, nowMillis)) {
            return AlertOutcome(
                rule.copy(consecutiveBreaches = breaches.coerceAtLeast(rule.requiredBreaches)),
                AlertDecision.Suppressed("处于${rule.quietWindow ?: "静默时段"}，静默结束后再提醒")
            )
        }
        val cooldownMillis = rule.cooldownMinutes.coerceAtLeast(0) * 60_000L
        if (rule.lastFiredAtMillis > 0 && nowMillis - rule.lastFiredAtMillis < cooldownMillis) {
            val remainingMinutes = (cooldownMillis - (nowMillis - rule.lastFiredAtMillis)) / 60_000L + 1
            return AlertOutcome(
                rule.copy(consecutiveBreaches = breaches),
                AlertDecision.Suppressed("冷却中，约 $remainingMinutes 分钟后再提醒")
            )
        }
        val alert = AlertFormatter.format(rule, value, observation)
        return AlertOutcome(
            rule.copy(
                consecutiveBreaches = 0,
                lastFiredAtMillis = nowMillis,
                triggerCount = rule.triggerCount + 1
            ),
            AlertDecision.Fire(alert.first, alert.second)
        )
    }

    fun evaluateAll(
        rules: List<AlertRule>,
        observations: Map<String, AlertObservation>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<AlertOutcome> = rules.map { rule ->
        evaluate(rule, observations[rule.id] ?: AlertObservation(value = null), nowMillis)
    }

    fun breaches(rule: AlertRule, value: Double): Boolean = when (rule.kind) {
        AlertRuleKind.PRICE_ABOVE, AlertRuleKind.DAILY_CHANGE_ABOVE,
        AlertRuleKind.HOLDING_PROFIT_ABOVE, AlertRuleKind.FUND_ESTIMATE_ABOVE,
        AlertRuleKind.GOLD_PREMIUM_ABOVE, AlertRuleKind.QUOTE_STALE -> value > rule.threshold
        AlertRuleKind.PRICE_BELOW -> value < rule.threshold
        AlertRuleKind.DAILY_CHANGE_BELOW, AlertRuleKind.HOLDING_LOSS_BELOW ->
            value < -abs(rule.threshold)
    }

    fun isQuietHour(rule: AlertRule, nowMillis: Long): Boolean {
        val start = rule.quietStartHour ?: return false
        val end = rule.quietEndHour ?: return false
        val hour = Instant.ofEpochMilli(nowMillis).atZone(zone).hour
        return if (start <= end) hour in start until end else hour >= start || hour < end
    }
}

/** Notification copy: fact + source + time + the product red lines. */
object AlertFormatter {
    private val zone = ZoneId.of("Asia/Shanghai")

    fun format(rule: AlertRule, value: Double, observation: AlertObservation): Pair<String, String> {
        val title = "养基助手 · ${rule.kind.label.replace("超过", "").replace("高于", "").replace("低于", "")}提醒"
        val valueText = "${formatNumber(value)}${rule.kind.unit}"
        val thresholdText = "${formatNumber(rule.threshold)}${rule.kind.unit}"
        val source = observation.source?.takeIf { it.isNotBlank() }?.let { "来源 $it" } ?: "来源本机缓存"
        val time = observation.observedAtMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toString().take(16) }
            ?: "时间未知"
        val description = observation.description?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
        val body = buildString {
            append("${rule.target}$description 当前 $valueText")
            append("，阈值 ${rule.kind.label}$thresholdText")
            append("；$source · $time。")
            append("仅为事实提醒，不构成投资建议。")
        }
        return title to body
    }

    private fun formatNumber(value: Double): String = when {
        abs(value) >= 1_000 -> String.format(java.util.Locale.US, "%,.2f", value)
        abs(value) >= 1 -> String.format(java.util.Locale.US, "%.2f", value)
        else -> String.format(java.util.Locale.US, "%.4f", value)
    }
}

/** Ready-made rules users can add with one tap; targets are resolved by the app against real data. */
object AlertTemplates {
    fun all(nowMillis: Long = System.currentTimeMillis()): List<AlertRule> = listOf(
        rule("gold-high", AlertRuleKind.PRICE_ABOVE, "GOLD.SGE.AU9999", "上金所 Au99.99", 1_000.0, nowMillis),
        rule("gold-low", AlertRuleKind.PRICE_BELOW, "GOLD.SGE.AU9999", "上金所 Au99.99", 800.0, nowMillis),
        rule("index-up", AlertRuleKind.DAILY_CHANGE_ABOVE, "1.000300", "沪深300", 2.0, nowMillis),
        rule("index-down", AlertRuleKind.DAILY_CHANGE_BELOW, "1.000300", "沪深300", 2.0, nowMillis),
        rule("account-loss", AlertRuleKind.HOLDING_LOSS_BELOW, null, "账户持仓", 5.0, nowMillis)
            .copy(requiredBreaches = 2, quietStartHour = 22, quietEndHour = 8),
        rule("quote-stale", AlertRuleKind.QUOTE_STALE, null, "关键报价", 72.0, nowMillis).copy(cooldownMinutes = 360)
    )

    private fun rule(
        id: String,
        kind: AlertRuleKind,
        targetId: String?,
        targetLabel: String,
        threshold: Double,
        nowMillis: Long
    ) = AlertRule(
        id = id,
        kind = kind,
        targetId = targetId,
        targetLabel = targetLabel,
        threshold = threshold,
        createdAtMillis = nowMillis,
        updatedAtMillis = nowMillis
    )
}
