package cn.market.assistant.domain.alert

/** One fired alert, kept as an audit log independent of the notification tray. */
data class AlertEvent(
    val id: Long = 0,
    val ruleId: String,
    val ruleKind: AlertRuleKind,
    val title: String,
    val body: String,
    val firedAtMillis: Long,
    val targetValue: Double? = null
)

interface AlertRuleStore {
    suspend fun alertRules(): List<AlertRule>
    suspend fun upsertAlertRule(rule: AlertRule)
    suspend fun deleteAlertRule(id: String)
    suspend fun alertEvents(limit: Int = 50): List<AlertEvent>
    suspend fun recordAlertEvent(event: AlertEvent)
    suspend fun clearAlertEvents()
}
