package cn.market.assistant

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.domain.alert.AlertRule
import cn.market.assistant.domain.alert.AlertRuleKind
import cn.market.assistant.domain.market.MarketQuote
import cn.market.assistant.domain.portfolio.Position
import java.time.Instant
import java.time.ZoneId

/**
 * Alert rules page: threshold rules over local facts, the fired-event log, and the notification
 * permission state. Alerts only state facts with source and time; they never suggest an action.
 */
@Composable
internal fun AlertScreen(
    state: AlertFeatureState,
    quotes: Map<String, MarketQuote>,
    positions: List<Position>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(AlertNotifier.hasPermission(context)) }
    var editing by remember { mutableStateOf<AlertRule?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MarketBackButton(label = "总览", onClick = onBack)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("提醒", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("阈值规则 · 只陈述事实与来源时点 · 不提供买卖建议", color = Muted, fontSize = 10.sp)
            }
            TextButton(onClick = { state.checkNow(quotes, positions) }) { Text("立即检查", color = Blue, fontSize = 12.sp) }
        }

        if (!permissionGranted) {
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SoftBlue)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("尚未授予通知权限", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Android 13+ 需要通知权限；Android 16 会把进行中的进度通知提升为状态栏实时通知。", color = Muted, fontSize = 9.sp, lineHeight = 13.sp)
                    }
                    TextButton(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text("授权", color = Blue, fontSize = 11.sp)
                    }
                }
            }
        }

        state.lastCheck?.let { Text(it, color = Muted, fontSize = 10.sp, lineHeight = 14.sp) }
        state.lastFired?.let { fired ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MarketTheme.colors.surface),
                border = BorderStroke(1.dp, AssistantUp)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("最近触发 · ${fired.rule.target}", color = AssistantUp, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(fired.body, color = Ink, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = MarketNavBarClearance),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("我的规则", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (state.rules.isEmpty()) {
                Text("还没有规则。可以从下面的模板开始，或点「新建规则」。", color = Muted, fontSize = 11.sp)
            }
            state.rules.forEach { rule -> AlertRuleCard(rule, state, onEdit = { editing = rule; editorOpen = true }) }
            TextButton(onClick = { editing = null; editorOpen = true }) { Text("+ 新建规则", color = Blue, fontSize = 12.sp) }

            Text("快捷模板", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "一键加入后仍可修改阈值、去抖次数、冷却时间与静默时段；模板使用本机已有标的与账户口径。",
                color = Muted, fontSize = 10.sp, lineHeight = 14.sp
            )
            AlertFeatureState.templates().forEach { template ->
                Row(
                    Modifier.fillMaxWidth().clickable { state.addTemplate(template) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${template.target} · ${template.kind.label} ${formatThreshold(template)}",
                            color = Ink, fontSize = 12.sp
                        )
                        Text(template.kind.explanation, color = Muted, fontSize = 9.sp)
                    }
                    Text("+ 加入", color = Blue, fontSize = 11.sp)
                }
            }

            Text("触发记录", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (state.events.isEmpty()) {
                Text("还没有触发记录。", color = Muted, fontSize = 11.sp)
            }
            state.events.forEach { event ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${formatTime(event.firedAtMillis)} · ${event.ruleKind.label}",
                        color = Muted, fontSize = 9.sp
                    )
                    Text(event.body, color = Ink, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
            if (state.events.isNotEmpty()) {
                TextButton(onClick = { state.clearEvents() }) { Text("清空触发记录", color = AssistantDown, fontSize = 10.sp) }
            }
            Text(
                "后台每 30 分钟用本机缓存评估一次（WorkManager 尽力调度，省电模式下可能延后）；" +
                    "前台刷新行情与账本后也会立即评估。提醒不构成投资建议，估值均为估算。",
                color = Muted, fontSize = 9.sp, lineHeight = 13.sp, modifier = Modifier.padding(bottom = 20.dp)
            )
        }
    }

    if (editorOpen) {
        AlertRuleEditor(
            initial = editing,
            onDismiss = { editorOpen = false; editing = null },
            onSave = { rule -> state.saveRule(rule); editorOpen = false; editing = null },
            onDelete = { id -> state.deleteRule(id); editorOpen = false; editing = null }
        )
    }
}

@Composable
private fun AlertRuleCard(rule: AlertRule, state: AlertFeatureState, onEdit: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MarketTheme.colors.surface),
        border = BorderStroke(1.dp, MarketTheme.colors.line)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${rule.target} · ${rule.kind.label} ${formatThreshold(rule)}",
                        color = Ink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(rule.kind.explanation, color = Muted, fontSize = 9.sp)
                }
                Switch(checked = rule.enabled, onCheckedChange = { state.toggleRule(rule, it) })
            }
            Text(
                buildString {
                    append("连续 ${rule.requiredBreaches} 次触发 · 冷却 ${rule.cooldownMinutes} 分钟")
                    rule.quietWindow?.let { append(" · $it") }
                    append(" · 已触发 ${rule.triggerCount} 次")
                    if (rule.lastFiredAtMillis > 0) append(" · 最近 ${formatTime(rule.lastFiredAtMillis)}")
                },
                color = Muted, fontSize = 9.sp, lineHeight = 13.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("编辑", color = Blue, fontSize = 10.sp)
                }
                TextButton(onClick = { state.deleteRule(rule.id) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("删除", color = AssistantDown, fontSize = 10.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertRuleEditor(
    initial: AlertRule?,
    onDismiss: () -> Unit,
    onSave: (AlertRule) -> Unit,
    onDelete: (String) -> Unit
) {
    var kind by remember(initial) { mutableStateOf(initial?.kind ?: AlertRuleKind.PRICE_ABOVE) }
    var targetId by remember(initial) { mutableStateOf(initial?.targetId.orEmpty()) }
    var targetLabel by remember(initial) { mutableStateOf(initial?.targetLabel.orEmpty()) }
    var threshold by remember(initial) { mutableStateOf(initial?.threshold?.toString() ?: "") }
    var requiredBreaches by remember(initial) { mutableStateOf((initial?.requiredBreaches ?: 1).toString()) }
    var cooldown by remember(initial) { mutableStateOf((initial?.cooldownMinutes ?: 60).toString()) }
    var quietEnabled by remember(initial) { mutableStateOf(initial?.quietStartHour != null) }
    var quietStart by remember(initial) { mutableStateOf((initial?.quietStartHour ?: 22).toString()) }
    var quietEnd by remember(initial) { mutableStateOf((initial?.quietEndHour ?: 8).toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MarketTheme.colors.surfaceAlt) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(horizontal = 18.dp).padding(bottom = 26.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(if (initial == null) "新建提醒规则" else "编辑提醒规则", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AlertRuleKind.entries.forEach { option ->
                    FilterChip(
                        selected = kind == option,
                        onClick = { kind = option },
                        label = { Text(option.label, fontSize = 10.sp) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SoftBlue, selectedLabelColor = Ink,
                            containerColor = White, labelColor = Muted
                        )
                    )
                }
            }
            Text(kind.explanation, color = Muted, fontSize = 10.sp)
            if (kind.requiresTarget) {
                OutlinedTextField(
                    value = targetId, onValueChange = { targetId = it.take(60) },
                    label = { Text("标 ID（如 1.000300 / GOLD.SGE.AU9999 / 518880）", fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
                OutlinedTextField(
                    value = targetLabel, onValueChange = { targetLabel = it.take(40) },
                    label = { Text("显示名称（可选）", fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
            }
            OutlinedTextField(
                value = threshold, onValueChange = { threshold = it.filter { char -> char.isDigit() || char == '.' || char == '-' }.take(12) },
                label = { Text("阈值（${kind.unit}）", fontSize = 10.sp) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = requiredBreaches,
                    onValueChange = { requiredBreaches = it.filter(Char::isDigit).take(2) },
                    label = { Text("连续次数", fontSize = 10.sp) },
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
                OutlinedTextField(
                    value = cooldown,
                    onValueChange = { cooldown = it.filter(Char::isDigit).take(4) },
                    label = { Text("冷却分钟", fontSize = 10.sp) },
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("静默时段", color = Ink, fontSize = 12.sp)
                    Text("静默期间不打扰，静默结束后仍满足条件才提醒", color = Muted, fontSize = 9.sp)
                }
                Switch(checked = quietEnabled, onCheckedChange = { quietEnabled = it })
            }
            if (quietEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quietStart, onValueChange = { quietStart = it.filter(Char::isDigit).take(2) },
                        label = { Text("开始小时", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                    )
                    OutlinedTextField(
                        value = quietEnd, onValueChange = { quietEnd = it.filter(Char::isDigit).take(2) },
                        label = { Text("结束小时", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                    )
                }
            }
            error?.let { Text(it, color = AssistantDown, fontSize = 11.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        val thresholdValue = threshold.toDoubleOrNull()
                        if (thresholdValue == null || !thresholdValue.isFinite()) {
                            error = "请填写有效阈值"
                            return@TextButton
                        }
                        if (kind.requiresTarget && targetId.isBlank()) {
                            error = "该规则需要填写标 ID"
                            return@TextButton
                        }
                        val now = System.currentTimeMillis()
                        onSave(
                            AlertRule(
                                id = initial?.id ?: "rule-${now % 1_000_000}",
                                kind = kind,
                                targetId = targetId.takeIf { it.isNotBlank() },
                                targetLabel = targetLabel.takeIf { it.isNotBlank() },
                                threshold = thresholdValue,
                                enabled = initial?.enabled ?: true,
                                requiredBreaches = requiredBreaches.toIntOrNull()?.coerceIn(1, 10) ?: 1,
                                cooldownMinutes = cooldown.toIntOrNull()?.coerceIn(0, 10_080) ?: 60,
                                quietStartHour = if (quietEnabled) quietStart.toIntOrNull()?.coerceIn(0, 23) ?: 22 else null,
                                quietEndHour = if (quietEnabled) quietEnd.toIntOrNull()?.coerceIn(0, 23) ?: 8 else null,
                                lastFiredAtMillis = initial?.lastFiredAtMillis ?: 0,
                                triggerCount = initial?.triggerCount ?: 0,
                                consecutiveBreaches = initial?.consecutiveBreaches ?: 0,
                                createdAtMillis = initial?.createdAtMillis ?: now,
                                updatedAtMillis = now,
                                note = initial?.note.orEmpty()
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (initial == null) "创建规则" else "保存修改", color = Ink) }
                if (initial != null) {
                    TextButton(onClick = { onDelete(initial.id) }) { Text("删除", color = AssistantDown) }
                }
            }
        }
    }
}

private fun formatThreshold(rule: AlertRule): String {
    val value = if (rule.threshold == rule.threshold.toLong().toDouble()) {
        rule.threshold.toLong().toString()
    } else {
        String.format(java.util.Locale.US, "%.2f", rule.threshold)
    }
    return "$value${rule.kind.unit}"
}

private fun formatTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Shanghai")).toString().replace('T', ' ').take(16)
