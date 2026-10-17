package cn.market.assistant

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.market.assistant.domain.alert.AlertDecision
import cn.market.assistant.domain.alert.AlertEngine
import cn.market.assistant.domain.alert.AlertEvent
import cn.market.assistant.domain.alert.AlertObservation
import cn.market.assistant.domain.alert.AlertRule
import cn.market.assistant.domain.alert.AlertRuleKind
import cn.market.assistant.domain.alert.AlertRuleStore
import cn.market.assistant.domain.alert.AlertTemplates
import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.portfolio.Position
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

internal data class AlertFiredUi(
    val rule: AlertRule,
    val title: String,
    val body: String,
    val atMillis: Long
)

/**
 * Alert rules, evaluation and the fired-event log. Evaluation reads only local ledger facts and cached
 * quotes; a missing value suppresses the rule instead of firing on a zero.
 */
@Stable
internal class AlertFeatureState(
    private val store: AlertRuleStore,
    private val scope: CoroutineScope,
    private val notify: (AlertEvent) -> Unit
) {
    var rules by mutableStateOf(emptyList<AlertRule>())
        private set
    var events by mutableStateOf(emptyList<AlertEvent>())
        private set
    var lastCheck by mutableStateOf<String?>(null)
        private set
    var lastFired by mutableStateOf<AlertFiredUi?>(null)
        private set

    fun refresh() {
        scope.launch {
            rules = store.alertRules()
            events = store.alertEvents(limit = 40)
        }
    }

    fun saveRule(rule: AlertRule) {
        scope.launch {
            store.upsertAlertRule(rule)
            refresh()
        }
    }

    fun deleteRule(id: String) {
        scope.launch {
            store.deleteAlertRule(id)
            refresh()
        }
    }

    fun toggleRule(rule: AlertRule, enabled: Boolean) {
        saveRule(rule.copy(enabled = enabled))
    }

    fun addTemplate(rule: AlertRule) {
        saveRule(rule.copy(id = "${rule.id}-${System.currentTimeMillis() % 100_000}"))
    }

    fun clearEvents() {
        scope.launch {
            store.clearAlertEvents()
            refresh()
        }
    }

    fun checkNow(quotes: Map<String, MarketQuote>, positions: List<Position>) {
        scope.launch {
            AlertEvaluationGate.mutex.withLock {
            val currentRules = store.alertRules()
            val observations = buildObservations(currentRules, quotes, positions)
            var firedCount = 0
            val now = System.currentTimeMillis()
            currentRules.forEach { rule ->
                val outcome = AlertEngine.evaluate(rule, observations[rule.id] ?: AlertObservation(value = null), now)
                if (outcome.rule != rule) store.upsertAlertRule(outcome.rule)
                val decision = outcome.decision
                if (decision is AlertDecision.Fire) {
                    val event = AlertEvent(
                        ruleId = rule.id,
                        ruleKind = rule.kind,
                        title = decision.title,
                        body = decision.body,
                        firedAtMillis = now,
                        targetValue = observations[rule.id]?.value
                    )
                    store.recordAlertEvent(event)
                    notify(event)
                    lastFired = AlertFiredUi(rule, decision.title, decision.body, now)
                    firedCount++
                }
            }
            val suppressed = currentRules.size - firedCount
            lastCheck = "已检查 ${currentRules.size} 条规则：触发 $firedCount 条，其余 $suppressed 条未达到阈值或处于冷却/静默"
            rules = store.alertRules()
            events = store.alertEvents(limit = 40)
            }
        }
    }

    /** Factual observation for every rule, using local ledger state and cached quotes only. */
    fun buildObservations(
        rules: List<AlertRule>,
        quotes: Map<String, MarketQuote>,
        positions: List<Position>
    ): Map<String, AlertObservation> = AlertObservations.build(rules, quotes, positions)


    companion object {
        fun templates(): List<AlertRule> = AlertTemplates.all()
    }
}
