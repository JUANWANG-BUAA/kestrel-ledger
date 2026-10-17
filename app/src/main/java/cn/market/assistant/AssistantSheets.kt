package cn.market.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.domain.assistant.AssistantSkill
import cn.market.assistant.domain.assistant.AssistantWorkflow
import cn.market.assistant.domain.assistant.HabitSummary
import cn.market.assistant.domain.assistant.LlmProvider
import cn.market.assistant.domain.assistant.LlmProtocol
import cn.market.assistant.domain.assistant.LlmEndpointValidator
import cn.market.assistant.domain.assistant.LlmSettings
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

internal enum class AssistantSheet { SETTINGS, SKILLS, WORKFLOWS, HABITS, SKILL_EDITOR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantSheets(
    sheet: AssistantSheet?,
    state: AssistantFeatureState,
    editingSkill: AssistantSkill?,
    onDismiss: () -> Unit,
    onEditSkill: (AssistantSkill?) -> Unit,
    onExportWorkflow: (AssistantWorkflow) -> Unit,
    onExportMemory: () -> Unit
) {
    if (sheet == null) return
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MarketTheme.colors.surfaceAlt) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 18.dp).padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (sheet) {
                AssistantSheet.SETTINGS -> AssistantSettingsPanel(state)
                AssistantSheet.SKILLS -> AssistantSkillsPanel(state, onEditSkill)
                AssistantSheet.SKILL_EDITOR -> AssistantSkillEditor(state, editingSkill)
                AssistantSheet.WORKFLOWS -> AssistantWorkflowsPanel(state, onDismiss, onExportWorkflow)
                AssistantSheet.HABITS -> AssistantHabitsPanel(state, onExportMemory)
            }
        }
    }
}

@Composable
private fun AssistantSettingsPanel(state: AssistantFeatureState) {
    var provider by remember(state.settings.provider) { mutableStateOf(state.settings.provider) }
    var baseUrl by remember(state.settings.baseUrl) { mutableStateOf(state.settings.baseUrl) }
    var model by remember(state.settings.model) { mutableStateOf(state.settings.model) }
    var protocol by remember(state.settings.protocol) { mutableStateOf(state.settings.protocol) }
    var apiKey by remember { mutableStateOf("") }
    var temperature by remember(state.settings.temperature) { mutableStateOf(state.settings.temperature) }
    var maxTokens by remember(state.settings.maxTokens) { mutableStateOf(state.settings.maxTokens) }
    var systemPrompt by remember(state.settings.systemPrompt) { mutableStateOf(state.settings.systemPrompt) }
    var sharePrivate by remember(state.settings.sharePrivateDetails) { mutableStateOf(state.settings.sharePrivateDetails) }
    var imageInputEnabled by remember(state.settings.imageInputEnabled) { mutableStateOf(state.settings.imageInputEnabled) }

    Text("模型接入", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "选择与服务商匹配的接口协议。API Key 使用 Android Keystore 加密保存在本机，不进入账本备份。",
        color = Muted, fontSize = 10.sp, lineHeight = 14.sp
    )
    Row(Modifier.fillMaxWidth().heightIn(max = 132.dp).verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LlmProvider.entries.chunked(2).forEach { column ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                column.forEach { option ->
                    FilterChip(
                        selected = provider == option,
                        onClick = {
                            val next = LlmSettings(
                                provider = provider,
                                baseUrl = baseUrl,
                                model = model,
                                protocol = protocol
                            ).withProvider(option)
                            provider = next.provider
                            protocol = next.protocol
                            baseUrl = next.baseUrl
                            model = next.model
                            apiKey = ""
                            imageInputEnabled = cn.market.assistant.domain.assistant.ModelImageCapabilities
                                .infer(option, next.model)
                        },
                        label = { Text(option.label, fontSize = 10.sp) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SoftBlue, selectedLabelColor = Ink,
                            containerColor = White, labelColor = Muted
                        )
                    )
                }
            }
        }
    }
    Text("控制台：${provider.consoleHint}", color = Muted, fontSize = 9.sp)
    Text("接口协议", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LlmProtocol.entries.forEach { option ->
            FilterChip(
                selected = protocol == option,
                onClick = {
                    val next = LlmSettings(
                        provider = provider,
                        baseUrl = baseUrl,
                        model = model,
                        protocol = protocol
                    ).withProtocol(option)
                    protocol = next.protocol
                    baseUrl = next.baseUrl
                },
                label = { Text(option.label, fontSize = 10.sp) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = SoftBlue, selectedLabelColor = Ink,
                    containerColor = White, labelColor = Muted
                )
            )
        }
    }
    val protocolHint = when (protocol) {
        LlmProtocol.OPENAI_CHAT_COMPLETIONS -> "兼容 OpenAI Chat Completions；使用 Bearer Key。"
        LlmProtocol.OPENAI_RESPONSES -> "兼容 OpenAI Responses；使用 Bearer Key。"
        LlmProtocol.ANTHROPIC_MESSAGES -> "兼容 Anthropic Messages；使用 x-api-key。"
    }
    val endpointPreview = LlmEndpointValidator.endpointUrl(
        LlmSettings(provider = provider, baseUrl = baseUrl, model = model, protocol = protocol)
    )
    Text(
        "${protocol.label} · $protocolHint 地址栏填写 Base URL。DeepSeek 支持这三种格式。",
        color = Muted, fontSize = 9.sp, lineHeight = 13.sp
    )
    Text(
        "完整请求地址：$endpointPreview",
        color = Muted, fontSize = 9.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
    )
    Text(
        "若连接失败，请确认当前服务商和模型支持所选协议；切换协议会自动更新默认地址。",
        color = Muted, fontSize = 9.sp, lineHeight = 13.sp
    )
    OutlinedTextField(
        value = baseUrl, onValueChange = { baseUrl = it.take(300) },
        label = { Text("接口地址", fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth(),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
    )
    OutlinedTextField(
        value = model, onValueChange = {
            model = it.take(120)
            imageInputEnabled = cn.market.assistant.domain.assistant.ModelImageCapabilities.infer(provider, model)
        },
        label = { Text("模型名称", fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth(),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
    )
    if (provider.popularModels.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            provider.popularModels.take(4).forEach { option ->
                TextButton(onClick = {
                    model = option
                    imageInputEnabled = cn.market.assistant.domain.assistant.ModelImageCapabilities.infer(provider, option)
                }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(option, color = Blue, fontSize = 10.sp)
                }
            }
        }
    }
    OutlinedTextField(
        value = apiKey, onValueChange = { apiKey = it.take(300) },
        label = {
            Text(
                when {
                    apiKey.isNotBlank() -> "API Key（保存时加密）"
                    state.hasApiKey && provider == state.settings.provider -> "API Key（已保存，留空则不修改）"
                    else -> "API Key"
                },
                fontSize = 11.sp
            )
        },
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
    )
    if (provider == LlmProvider.DEEPSEEK) {
        Text("DeepSeek API Key 通常以 sk- 开头。", color = Muted, fontSize = 9.sp)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("允许图片输入", color = Ink, fontSize = 12.sp)
            Text("仅在模型接口支持视觉输入时开启；图片会发送到当前模型服务。", color = Muted, fontSize = 9.sp, lineHeight = 13.sp)
        }
        Switch(checked = imageInputEnabled, onCheckedChange = { imageInputEnabled = it })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("温度 ${String.format(java.util.Locale.US, "%.1f", temperature)}", color = Ink, fontSize = 11.sp, modifier = Modifier.width(88.dp))
        androidx.compose.material3.Slider(
            value = temperature.toFloat(),
            onValueChange = { temperature = it.toDouble() },
            valueRange = 0f..1.5f,
            modifier = Modifier.weight(1f)
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("最大输出", color = Ink, fontSize = 11.sp, modifier = Modifier.width(88.dp))
        OutlinedTextField(
            value = maxTokens.toString(), onValueChange = { maxTokens = it.filter(Char::isDigit).toIntOrNull() ?: maxTokens },
            modifier = Modifier.weight(1f),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("允许发送持仓明细", color = Ink, fontSize = 12.sp)
            Text("开启时，单只持仓数据会发给当前模型服务；关闭时只提供汇总事实与公开行情。", color = Muted, fontSize = 9.sp, lineHeight = 13.sp)
        }
        Switch(checked = sharePrivate, onCheckedChange = { sharePrivate = it })
    }
    Text("系统提示词", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistantPromptPresets.all.take(5).forEach { preset ->
            TextButton(onClick = {
                state.applyPromptPreset(preset.id)
                systemPrompt = preset.prompt
            }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(preset.label, color = Blue, fontSize = 10.sp)
            }
        }
    }
    OutlinedTextField(
        value = systemPrompt, onValueChange = { systemPrompt = it.take(4_000) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
    )
    Text("可用工具", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    state.toolDescriptors.forEach { descriptor ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(descriptor.id, color = Ink, fontSize = 11.sp)
                Text(descriptor.description, color = Muted, fontSize = 9.sp, lineHeight = 12.sp)
            }
            Switch(
                checked = descriptor.id in state.enabledToolIds,
                onCheckedChange = { state.setToolEnabled(descriptor.id, it) }
            )
        }
    }
    state.connectionCheck?.let { message ->
        Text(
            message,
            color = if (message.startsWith("连接成功")) AssistantDown else AssistantUp,
            fontSize = 11.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = {
                state.updateSettings(
                    LlmSettings(
                        provider = provider,
                        baseUrl = baseUrl,
                        model = model,
                        protocol = protocol,
                        temperature = temperature,
                        maxTokens = maxTokens,
                        toolsEnabled = true,
                        sharePrivateDetails = sharePrivate,
                        systemPrompt = systemPrompt,
                        imageInputEnabled = imageInputEnabled
                    ),
                    apiKey
                )
            },
            modifier = Modifier.weight(1f)
        ) { Text("保存设置", color = Ink) }
        TextButton(
            onClick = {
                state.updateSettings(
                    LlmSettings(
                        provider = provider,
                        baseUrl = baseUrl,
                        model = model,
                        protocol = protocol,
                        temperature = temperature,
                        maxTokens = maxTokens,
                        toolsEnabled = true,
                        sharePrivateDetails = sharePrivate,
                        systemPrompt = systemPrompt,
                        imageInputEnabled = imageInputEnabled
                    ),
                    apiKey
                )
                state.testConnection()
            },
            enabled = !state.checkingConnection,
            modifier = Modifier.weight(1f)
        ) { Text(if (state.checkingConnection) "测试中…" else "保存并测试", color = Blue) }
    }
    if (state.hasApiKey) {
        TextButton(onClick = { state.clearApiKey() }) { Text("清除本机 API Key", color = AssistantDown, fontSize = 11.sp) }
    }
}

@Composable
private fun AssistantSkillsPanel(state: AssistantFeatureState, onEditSkill: (AssistantSkill?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("我的技能", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("技能 = 一段可复用指令 + 可选工具范围；输入 /技能ID 或命中触发词时自动启用。", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        TextButton(onClick = { onEditSkill(null) }) { Text("新建", color = Blue) }
    }
    if (state.skills.isEmpty()) {
        Text("还没有技能。可以从周报、估值、风险等模板开始。", color = Muted, fontSize = 11.sp)
    }
    state.skills.forEach { skill ->
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = White)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${skill.name}  /${skill.id}", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(skill.description.ifBlank { "（无描述）" }, color = Muted, fontSize = 10.sp)
                    }
                    Switch(checked = skill.enabled, onCheckedChange = { state.saveSkill(skill.copy(enabled = it)) })
                }
                Text(
                    "使用 ${skill.usageCount} 次 · 工具 ${skill.toolIds.ifEmpty { listOf("默认") }.joinToString("、")} · 触发词 " +
                        skill.triggerWords.joinToString("、").ifBlank { "—" },
                    color = Muted, fontSize = 9.sp, lineHeight = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onEditSkill(skill) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("编辑", color = Blue, fontSize = 10.sp)
                    }
                    TextButton(onClick = { state.deleteSkill(skill.id) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("删除", color = AssistantDown, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantSkillEditor(state: AssistantFeatureState, skill: AssistantSkill?) {
    var id by remember(skill) { mutableStateOf(skill?.id.orEmpty()) }
    var name by remember(skill) { mutableStateOf(skill?.name.orEmpty()) }
    var description by remember(skill) { mutableStateOf(skill?.description.orEmpty()) }
    var instructions by remember(skill) { mutableStateOf(skill?.instructions.orEmpty()) }
    var triggers by remember(skill) { mutableStateOf(skill?.triggerWords?.joinToString("、").orEmpty()) }
    var selectedTools by remember(skill) { mutableStateOf(skill?.toolIds?.toSet() ?: emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }

    Text(if (skill == null) "新建技能" else "编辑技能", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    OutlinedTextField(
        value = id, onValueChange = { id = it.lowercase().filter { char -> char.isLetterOrDigit() || char == '-' || char == '_' } },
        label = { Text("技能 ID（小写，用于 /调用）", fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth(),
        enabled = skill == null, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
    )
    OutlinedTextField(
        value = name, onValueChange = { name = it.take(40) },
        label = { Text("名称", fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth(),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
    )
    OutlinedTextField(
        value = description, onValueChange = { description = it.take(120) },
        label = { Text("适用场景", fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth(),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
    )
    OutlinedTextField(
        value = instructions, onValueChange = { instructions = it.take(2_000) },
        label = { Text("指令（会追加到系统提示词）", fontSize = 11.sp) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
    )
    OutlinedTextField(
        value = triggers, onValueChange = { triggers = it.take(200) },
        label = { Text("触发词，用、分隔", fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth(),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
    )
    Text("允许工具（不选=继承会话默认）", color = Ink, fontSize = 11.sp)
    state.toolDescriptors.forEach { descriptor ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(descriptor.id, color = Ink, fontSize = 11.sp) }
            Switch(
                checked = descriptor.id in selectedTools,
                onCheckedChange = { checked ->
                    selectedTools = if (checked) selectedTools + descriptor.id else selectedTools - descriptor.id
                }
            )
        }
    }
    error?.let { Text(it, color = AssistantDown, fontSize = 11.sp) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            val now = System.currentTimeMillis()
            if (id.isBlank() || name.isBlank() || instructions.isBlank()) {
                error = "ID、名称和指令都不能为空"
                return@TextButton
            }
            state.saveSkill(
                AssistantSkill(
                    id = id,
                    name = name,
                    description = description,
                    instructions = instructions,
                    toolIds = selectedTools.toList(),
                    triggerWords = triggers.split('、', ',', '，').map { it.trim() }.filter { it.isNotBlank() },
                    enabled = skill?.enabled ?: true,
                    usageCount = skill?.usageCount ?: 0,
                    createdAtMillis = skill?.createdAtMillis ?: now,
                    updatedAtMillis = now
                )
            )
            error = null
        }, modifier = Modifier.weight(1f)) { Text("保存技能", color = Ink) }
    }
}

@Composable
private fun AssistantWorkflowsPanel(
    state: AssistantFeatureState,
    onDismiss: () -> Unit,
    onExportWorkflow: (AssistantWorkflow) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("会话工作流", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("每轮问答都会落盘到加密账本，可随时恢复或导出 Markdown。", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        TextButton(onClick = { state.clearAllWorkflows() }) { Text("清空", color = AssistantDown, fontSize = 10.sp) }
    }
    if (state.workflows.isEmpty()) {
        Text("还没有历史会话。", color = Muted, fontSize = 11.sp)
    }
    state.workflows.forEach { workflow: AssistantWorkflow ->
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = White)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(workflow.title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${workflow.turnCount} 轮 · 更新 ${java.time.Instant.ofEpochMilli(workflow.updatedAtMillis).toString().take(16)}",
                    color = Muted, fontSize = 9.sp
                )
                workflow.lastTurn?.let { last ->
                    Text("最近：${last.question.take(40)}", color = Muted, fontSize = 10.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { state.resumeWorkflow(workflow.id); onDismiss() }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("恢复", color = Blue, fontSize = 10.sp)
                    }
                    TextButton(onClick = { onExportWorkflow(workflow) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("导出 Markdown", color = Blue, fontSize = 10.sp)
                    }
                    TextButton(onClick = { state.deleteWorkflow(workflow.id) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("删除", color = AssistantDown, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantHabitsPanel(state: AssistantFeatureState, onExportMemory: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("使用习惯", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("分析方法、常看标的与使用习惯仅保存在本机；你可以随时导出或清空。", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        TextButton(onClick = onExportMemory) { Text("导出", color = Blue, fontSize = 10.sp) }
        TextButton(onClick = { state.clearMemory() }) { Text("清空", color = AssistantDown, fontSize = 10.sp) }
    }
    val summary: HabitSummary = state.habits
    if (summary.isEmpty) {
        Text("还没有使用记录。", color = Muted, fontSize = 11.sp)
        return
    }
    Text("累计 ${summary.totalEvents} 条记录", color = Ink, fontSize = 12.sp)
    HabitList("最近常问", summary.topQuestions.map { "${it.key}（${it.count}）" })
    HabitList("常用工具", summary.topTools.map { "${it.key}（${it.count}）" })
    HabitList("常用技能", summary.topSkills.map { "${it.key}（${it.count}）" })
    HabitList("常看标的", summary.topInstruments.map { "${it.key}（${it.count}）" })
    HabitList("记住的方法", summary.preferences.map { it.key })
    summary.lastActiveAtMillis?.let {
        Text("最近使用：${java.time.Instant.ofEpochMilli(it).toString().take(16)}", color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun HabitList(title: String, values: List<String>) {
    if (values.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(values.joinToString("、"), color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}

@Composable
internal fun AssistantRenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(value); onDismiss() }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("重命名会话", fontSize = 15.sp) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it.take(60) },
                modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
            )
        }
    )
}

internal val AssistantUp: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.up
internal val AssistantDown: Color
    @Composable @ReadOnlyComposable get() = MarketTheme.colors.down
