package cn.market.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
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
import cn.market.assistant.domain.assistant.WorkflowStatus
import cn.market.assistant.domain.assistant.HabitSummary
import cn.market.assistant.domain.assistant.LlmProvider
import cn.market.assistant.domain.assistant.LlmProtocol
import cn.market.assistant.domain.assistant.LlmEndpointValidator
import cn.market.assistant.domain.assistant.LlmSettings
import cn.market.assistant.domain.assistant.LlmModelCapabilities
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
    onCopyWorkflow: (AssistantWorkflow) -> Unit,
    onExportMemory: () -> Unit
) {
    if (sheet == null) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MarketTheme.colors.surfaceAlt,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        if (sheet == AssistantSheet.SETTINGS) {
            AssistantSettingsPanel(
                state = state,
                modifier = Modifier.fillMaxWidth().heightIn(min = 580.dp, max = 680.dp)
                    .padding(horizontal = 18.dp).padding(bottom = 24.dp)
            )
        } else {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 18.dp).padding(bottom = 28.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (sheet) {
                    AssistantSheet.SKILLS -> AssistantSkillsPanel(state, onEditSkill)
                    AssistantSheet.SKILL_EDITOR -> AssistantSkillEditor(state, editingSkill)
                    AssistantSheet.WORKFLOWS -> AssistantWorkflowsPanel(state, onDismiss, onExportWorkflow, onCopyWorkflow)
                    AssistantSheet.HABITS -> AssistantHabitsPanel(state, onExportMemory)
                    AssistantSheet.SETTINGS -> Unit
                }
            }
        }
    }
}

private enum class AssistantSettingsTab(val title: String) {
    MODEL("模型"), REPLY("回复"), DATA("数据与工具")
}

@Composable
private fun AssistantSettingsPanel(state: AssistantFeatureState, modifier: Modifier = Modifier) {
    var provider by remember(state.settings.provider) { mutableStateOf(state.settings.provider) }
    var baseUrl by remember(state.settings.baseUrl) { mutableStateOf(state.settings.baseUrl) }
    var model by remember(state.settings.model) { mutableStateOf(state.settings.model) }
    var protocol by remember(state.settings.protocol) { mutableStateOf(state.settings.protocol) }
    var apiKey by remember { mutableStateOf("") }
    var temperature by remember(state.settings.temperature) { mutableStateOf(state.settings.temperature) }
    var maxTokens by remember(state.settings.maxTokens) { mutableStateOf(state.settings.maxTokens) }
    var reasoningEffort by remember(state.settings.reasoningEffort) { mutableStateOf(state.settings.reasoningEffort) }
    var systemPrompt by remember(state.settings.systemPrompt) { mutableStateOf(state.settings.systemPrompt) }
    var sharePrivate by remember(state.settings.sharePrivateDetails) { mutableStateOf(state.settings.sharePrivateDetails) }
    var allowLocalChanges by remember(state.settings.allowLocalChanges) { mutableStateOf(state.settings.allowLocalChanges) }
    var imageInputEnabled by remember(state.settings.imageInputEnabled) { mutableStateOf(state.settings.imageInputEnabled) }
    var selectedTab by remember { mutableStateOf(AssistantSettingsTab.MODEL) }

    fun currentSettings() = LlmSettings(
        provider = provider,
        baseUrl = baseUrl,
        model = model,
        protocol = protocol,
        temperature = temperature,
        maxTokens = maxTokens,
        reasoningEffort = reasoningEffort,
        toolsEnabled = true,
        sharePrivateDetails = sharePrivate,
        allowLocalChanges = allowLocalChanges,
        systemPrompt = systemPrompt,
        imageInputEnabled = imageInputEnabled
    )

    fun saveSettings() = state.updateSettings(currentSettings(), apiKey)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("助手设置", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.isLive) "${state.settings.provider.label} · ${state.settings.model}"
                    else "配置模型、回复习惯与本地数据权限",
                    color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                color = if (state.isLive) AssistantDown.copy(alpha = 0.12f) else MarketTheme.colors.surface,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(if (state.isLive) "已连接" else "未连接", color = if (state.isLive) AssistantDown else Muted,
                    fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().background(MarketTheme.colors.surface, RoundedCornerShape(16.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AssistantSettingsTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                Surface(
                    modifier = Modifier.weight(1f).clickable { selectedTab = tab },
                    color = if (selected) Ink else Color.Transparent,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        tab.title, color = if (selected) White else Muted,
                        fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }

        AnimatedContent(
            targetState = selectedTab,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                (fadeIn(tween(150)) + slideInHorizontally(tween(150)) { it / 14 }) togetherWith
                    fadeOut(tween(100))
            },
            label = "assistant_settings_tabs"
        ) { tab ->
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (tab) {
                    AssistantSettingsTab.MODEL -> {
                        SettingsGroupTitle("服务商", "切换后地址、模型和协议会自动填入默认值")
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LlmProvider.entries.forEach { option ->
                                FilterChip(
                                    selected = provider == option,
                                    onClick = {
                                        val next = currentSettings().withProvider(option)
                                        provider = next.provider
                                        protocol = next.protocol
                                        baseUrl = next.baseUrl
                                        model = next.model
                                        apiKey = ""
                                        imageInputEnabled = next.imageInputEnabled
                                        reasoningEffort = next.reasoningEffort
                                        maxTokens = next.maxTokens
                                    },
                                    label = { Text(option.label, fontSize = 10.sp, maxLines = 1) },
                                    colors = settingsChipColors()
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(provider.consoleHint, color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                            Text("接口协议", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        }
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LlmProtocol.entries.forEach { option ->
                                FilterChip(
                                    selected = protocol == option,
                                    onClick = {
                                        val next = currentSettings().withProtocol(option)
                                        protocol = next.protocol
                                        baseUrl = next.baseUrl
                                    },
                                    label = { Text(option.label.replace("OpenAI ", "").replace("Anthropic ", ""), fontSize = 9.sp, maxLines = 1) },
                                    colors = settingsChipColors()
                                )
                            }
                        }
                        val endpoint = LlmEndpointValidator.endpointUrl(currentSettings())
                        val catalogMatches = provider == state.settings.provider &&
                            baseUrl == state.settings.baseUrl && protocol == state.settings.protocol
                        val listedModels = state.availableModels.takeIf { catalogMatches }.orEmpty()
                        val capabilities = listedModels.firstOrNull { it.modelId == model }
                        Surface(color = MarketTheme.colors.surface, shape = RoundedCornerShape(13.dp)) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("请求地址预览", color = Muted, fontSize = 9.sp)
                                Text(endpoint, color = Ink, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        OutlinedTextField(
                            value = baseUrl, onValueChange = { baseUrl = it.take(300) },
                            label = { Text("Base URL", fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth(),
                            singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                        )
                        OutlinedTextField(
                            value = model, onValueChange = {
                                model = it.take(120)
                                imageInputEnabled = false
                            },
                            label = { Text("模型名称", fontSize = 11.sp) }, modifier = Modifier.fillMaxWidth(),
                            singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                        )
                        if (provider.popularModels.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                provider.popularModels.take(4).forEach { option ->
                                    FilterChip(
                                        selected = model == option,
                                        onClick = {
                                            model = option
                                            imageInputEnabled = false
                                        },
                                        label = { Text(option, fontSize = 9.sp, maxLines = 1) },
                                        colors = settingsChipColors()
                                    )
                                }
                            }
                        }
                        if (listedModels.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("此密钥可用的模型", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    listedModels.forEach { option ->
                                        FilterChip(
                                            selected = model == option.modelId,
                                            onClick = {
                                                model = option.modelId
                                                imageInputEnabled = option.supportsImageInput == true
                                                maxTokens = option.maxOutputTokens?.let { maxTokens.coerceAtMost(it) } ?: maxTokens
                                                if (reasoningEffort !in listOf("auto", "none") &&
                                                    option.reasoningEfforts?.contains(reasoningEffort) == false
                                                ) reasoningEffort = "auto"
                                            },
                                            label = { Text(option.displayName ?: option.modelId, fontSize = 9.sp, maxLines = 1) },
                                            colors = settingsChipColors()
                                        )
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = apiKey, onValueChange = { apiKey = it.take(300) },
                            label = {
                                Text(when {
                                    apiKey.isNotBlank() -> "API Key · 保存时加密"
                                    state.hasApiKey && provider == state.settings.provider -> "API Key · 已保存，留空不修改"
                                    else -> "API Key"
                                }, fontSize = 11.sp)
                            },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                        )
                        if (provider == LlmProvider.DEEPSEEK) {
                            Text("DeepSeek 密钥通常以 sk- 开头。", color = Muted, fontSize = 9.sp)
                        }
                        Surface(color = MarketTheme.colors.surface, shape = RoundedCornerShape(14.dp)) {
                            Column(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("模型能力", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            capabilities?.let { "当前密钥可用的模型能力" } ?: "读取模型目录并发现当前密钥可用模型",
                                            color = Muted, fontSize = 9.sp
                                        )
                                    }
                                    TextButton(
                                        enabled = !state.checkingModelCapabilities,
                                        onClick = { saveSettings(); state.readAvailableModels() },
                                        contentPadding = PaddingValues(horizontal = 6.dp)
                                    ) {
                                        Text(if (state.checkingModelCapabilities) "获取中…" else "获取模型", color = Blue, fontSize = 10.sp)
                                    }
                                }
                                val contextWindow = capabilities?.contextWindow
                                val maxInputTokens = capabilities?.maxInputTokens
                                val inputLimit = when {
                                    contextWindow != null -> "上下文：${contextWindow.formatTokenCount()}"
                                    maxInputTokens != null -> "最大输入：${maxInputTokens.formatTokenCount()}"
                                    else -> "上下文/最大输入：未提供"
                                }
                                Text(
                                    "$inputLimit   ·   输出上限：${capabilities?.maxOutputTokens?.formatTokenCount() ?: "未提供"}",
                                    color = Ink, fontSize = 9.sp
                                )
                                Text(
                                    "输入：${capabilities?.inputModalities?.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "服务未声明"}   ·   思考：${capabilities?.reasoningEfforts?.takeIf { it.isNotEmpty() }?.joinToString(" / ") ?: "服务未声明"}",
                                    color = Muted, fontSize = 9.sp
                                )
                                state.modelCapabilitiesMessage?.takeIf { capabilities != null || !it.startsWith("已读取") }?.let { message ->
                                    Text(message, color = if (message.startsWith("已读取")) AssistantDown else AssistantUp, fontSize = 9.sp)
                                }
                            }
                        }
                        AssistantSettingSwitch(
                            title = "图片输入",
                            detail = when (capabilities?.supportsImageInput) {
                                true -> "服务商目录确认支持图片；开启后图片会随消息发送"
                                false -> "服务商目录确认此模型只接收文本"
                                null -> if (imageInputEnabled) "手动启用，尚未由服务商目录验证" else "读取模型能力后确认是否支持图片"
                            },
                            checked = imageInputEnabled,
                            onCheckedChange = { imageInputEnabled = it },
                            enabled = capabilities?.supportsImageInput != false
                        )
                    }
                    AssistantSettingsTab.REPLY -> {
                        SettingsGroupTitle("回答方式", "选择思考深度、temperature 与单次输出上限")
                        val capabilities = state.availableModels.firstOrNull {
                            it.modelId == model && provider == state.settings.provider &&
                                baseUrl == state.settings.baseUrl && protocol == state.settings.protocol
                        }
                        val declaredEfforts = capabilities?.reasoningEfforts
                        val declaredThinkingTypes = capabilities?.thinkingTypes
                        val supportedEfforts = when {
                            declaredEfforts != null -> declaredEfforts
                            provider == LlmProvider.DEEPSEEK -> listOf("low", "high", "max")
                            provider == LlmProvider.ANTHROPIC -> if (declaredThinkingTypes?.isNotEmpty() == true) {
                                listOf("low", "medium", "high", "max")
                            } else emptyList()
                            else -> listOf("low", "medium", "high", "max")
                        }
                        Text("思考程度", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf("auto", "none").plus(supportedEfforts).distinct().forEach { effort ->
                                FilterChip(
                                    selected = reasoningEffort == effort,
                                    onClick = { reasoningEffort = effort },
                                    label = { Text(effortLabel(effort), fontSize = 9.sp) },
                                    colors = settingsChipColors()
                                )
                            }
                        }
                        Text(
                            when {
                                reasoningEffort == "auto" && provider == LlmProvider.DEEPSEEK -> "自动使用模型默认思考设置。DeepSeek 默认思考开启，此时 temperature 不生效；选择“关闭”后才使用温度值。"
                                reasoningEffort == "auto" && provider == LlmProvider.ANTHROPIC -> "自动遵从所选 Claude 模型的默认思考方式；为避免与 thinking 冲突，temperature 不发送。"
                                reasoningEffort == "auto" -> "自动使用模型默认思考设置。读取模型能力后，思考档位会按该模型公开的选项显示。"
                                provider == LlmProvider.DEEPSEEK && reasoningEffort != "none" -> "DeepSeek 思考模式下 temperature 不生效；思考级别按模型目录映射。"
                                provider == LlmProvider.ANTHROPIC && reasoningEffort != "none" -> "Claude 会按模型能力使用 adaptive thinking 或 token budget；读取能力后才能验证档位。"
                                else -> "关闭思考；temperature 按所选协议发送。"
                            }, color = Muted, fontSize = 9.sp, lineHeight = 13.sp
                        )
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistantPromptPresets.all.forEach { preset ->
                                FilterChip(
                                    selected = systemPrompt == preset.prompt,
                                    onClick = { systemPrompt = preset.prompt; state.applyPromptPreset(preset.id) },
                                    label = { Text(preset.label, fontSize = 10.sp) },
                                    colors = settingsChipColors()
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("创造度", color = Ink, fontSize = 11.sp, modifier = Modifier.width(64.dp))
                            androidx.compose.material3.Slider(
                                value = temperature.toFloat(),
                                onValueChange = { temperature = it.toDouble() },
                                valueRange = 0f..(if (provider == LlmProvider.ANTHROPIC) 1f else 2f),
                                modifier = Modifier.weight(1f)
                            )
                            Text(String.format(java.util.Locale.US, "%.1f", temperature), color = Muted, fontSize = 10.sp)
                        }
                        OutlinedTextField(
                            value = maxTokens.toString(),
                            onValueChange = { value ->
                                maxTokens = value.filter(Char::isDigit).take(6).toIntOrNull()?.coerceIn(1, 393_216) ?: maxTokens
                            },
                            label = { Text("单次输出上限（tokens）", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            )
                        )
                        Text("系统指令", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = systemPrompt, onValueChange = { systemPrompt = it.take(4_000) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, lineHeight = 16.sp)
                        )
                    }
                    AssistantSettingsTab.DATA -> {
                        SettingsGroupTitle("数据权限", "可随时调整；设置保存在本机")
                        AssistantSettingSwitch(
                            title = "允许发送持仓明细",
                            detail = if (sharePrivate) "单只持仓数据会发给当前模型" else "仅发送汇总事实与公开行情",
                            checked = sharePrivate,
                            onCheckedChange = { sharePrivate = it }
                        )
                        AssistantSettingSwitch(
                            title = "允许使用本地工具",
                            detail = "读取基金、自选、持仓和本地记录；修改需你确认",
                            checked = state.enabledToolIds.isNotEmpty(),
                            onCheckedChange = { enabled ->
                                state.toolDescriptors.forEach { descriptor -> state.setToolEnabled(descriptor.id, enabled) }
                            }
                        )
                        AssistantSettingSwitch(
                            title = "允许修改本机数据",
                            detail = if (allowLocalChanges) {
                                "助手可以提议记账、改自选、调风险参数或新建技能；每次仍需你点确认"
                            } else {
                                "关闭时助手只能读数据；任何写入类工具都不会出现在它的可用工具里"
                            },
                            checked = allowLocalChanges,
                            onCheckedChange = {
                                allowLocalChanges = it
                                state.updateSettings(currentSettings(), apiKey)
                            }
                        )
                        SettingsGroupTitle("逐项管理", "关闭某项后，助手不会调用它")
                        state.toolDescriptors.forEach { descriptor ->
                            val shortTitle = assistantToolLabel(descriptor.id)
                            val needsPermission = descriptor.id in state.localChangeToolIds
                            Row(
                                Modifier.fillMaxWidth().background(MarketTheme.colors.surface, RoundedCornerShape(13.dp))
                                    .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(shortTitle, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (needsPermission && !allowLocalChanges) {
                                            "需要先打开「允许修改本机数据」才会出现在助手的工具里"
                                        } else {
                                            descriptor.description
                                        },
                                        color = Muted, fontSize = 9.sp, lineHeight = 12.sp,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Switch(
                                    checked = descriptor.id in state.enabledToolIds,
                                    enabled = !needsPermission || allowLocalChanges,
                                    onCheckedChange = { state.setToolEnabled(descriptor.id, it) }
                                )
                            }
                        }
                    }
                }
            }
        }

        state.connectionCheck?.takeIf { selectedTab == AssistantSettingsTab.MODEL }?.let { message ->
            Text(message, color = if (message.startsWith("连接成功")) AssistantDown else AssistantUp,
                fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(
            Modifier.fillMaxWidth().background(MarketTheme.colors.surface, RoundedCornerShape(16.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { saveSettings() }, modifier = Modifier.weight(1f)) {
                Text("保存", color = Ink, fontSize = 12.sp)
            }
            if (selectedTab == AssistantSettingsTab.MODEL) {
                Surface(
                    modifier = Modifier.weight(1.3f).clickable(enabled = !state.checkingConnection) {
                        saveSettings()
                        state.testConnection()
                    },
                    color = Ink, shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (state.checkingConnection) "正在测试…" else "保存并测试连接",
                        color = White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 11.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            if (selectedTab == AssistantSettingsTab.MODEL && state.hasApiKey) {
                TextButton(onClick = { state.clearApiKey() }) { Text("清除密钥", color = AssistantUp, fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun settingsChipColors() = androidx.compose.material3.FilterChipDefaults.filterChipColors(
    selectedContainerColor = Ink,
    selectedLabelColor = White,
    containerColor = MarketTheme.colors.surface,
    labelColor = Muted
)

@Composable
private fun SettingsGroupTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Muted, fontSize = 9.sp)
    }
}

/** User-facing tool names; the tool id stays stable and is what the registry matches on. */
internal fun assistantToolLabel(id: String): String = when (id) {
    "market.quote" -> "行情查询"
    "market.chart" -> "走势图"
    "portfolio.positions" -> "持仓明细"
    "portfolio.period_report" -> "周期报告"
    "portfolio.risk" -> "组合风险"
    "portfolio.propose_change" -> "本机更改提议"
    "fund.search" -> "基金目录"
    "alerts.summary" -> "提醒汇总"
    "web.fetch" -> "公开网页抓取"
    else -> id.substringAfterLast('.').replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun AssistantSettingSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        Modifier.fillMaxWidth().background(MarketTheme.colors.surface, RoundedCornerShape(14.dp))
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = Muted, fontSize = 9.sp, lineHeight = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
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

    if (state.recipes.isNotEmpty()) {
        Text("我的配方", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "配方 = 可重复执行的工具序列（数据，不是代码）；助手可用 recipe.run 运行，也可以按需写成报告。",
            color = Muted, fontSize = 10.sp, lineHeight = 14.sp
        )
        state.recipes.forEach { recipe ->
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = White)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${recipe.name}  /recipe ${recipe.id}", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${recipe.steps.size} 步 · 工具 ${recipe.toolIds.joinToString("、").ifBlank { "无" }}",
                        color = Muted, fontSize = 9.sp, lineHeight = 12.sp
                    )
                    recipe.summary.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            onClick = { state.runRecipe(recipe.id) },
                            enabled = !state.recipeRunInProgress,
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Text(
                                if (state.recipeRunInProgress) "运行中…" else "运行",
                                color = if (state.recipeRunInProgress) Muted else Blue,
                                fontSize = 10.sp
                            )
                        }
                        TextButton(
                            onClick = { state.deleteRecipe(recipe.id) },
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) { Text("删除", color = AssistantDown, fontSize = 10.sp) }
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
    onExportWorkflow: (AssistantWorkflow) -> Unit,
    onCopyWorkflow: (AssistantWorkflow) -> Unit
) {
    var showArchived by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("会话工作流", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("自动保存 · 可继续、复制、导出或归档", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
    Row(Modifier.fillMaxWidth().background(MarketTheme.colors.surface, RoundedCornerShape(14.dp)).padding(4.dp)) {
        listOf(false to "最近", true to "已归档").forEach { (archived, label) ->
            Surface(
                Modifier.weight(1f).clickable { showArchived = archived },
                color = if (showArchived == archived) Ink else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(label, color = if (showArchived == archived) White else Muted, fontSize = 10.sp,
                    modifier = Modifier.padding(vertical = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
    val visibleWorkflows = state.workflows.filter { (it.status == WorkflowStatus.ARCHIVED) == showArchived }
    if (visibleWorkflows.isEmpty()) {
        Text(if (showArchived) "还没有归档会话。" else "还没有历史会话。", color = Muted, fontSize = 11.sp)
    }
    visibleWorkflows.forEach { workflow: AssistantWorkflow ->
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = White)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(workflow.title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${workflow.turnCount} 轮 · ${if (showArchived) "已归档" else "最近更新"} ${java.time.Instant.ofEpochMilli(workflow.updatedAtMillis).toString().take(16)}",
                    color = Muted, fontSize = 9.sp
                )
                workflow.lastTurn?.let { last ->
                    Text("最近：${last.question.take(40)}", color = Muted, fontSize = 10.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { state.resumeWorkflow(workflow.id); onDismiss() }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("继续", color = Blue, fontSize = 9.sp)
                    }
                    TextButton(onClick = { onCopyWorkflow(workflow) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("复制对话", color = Blue, fontSize = 9.sp)
                    }
                    TextButton(onClick = { onExportWorkflow(workflow) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("导出", color = Blue, fontSize = 9.sp)
                    }
                    TextButton(
                        onClick = { state.archiveWorkflow(workflow.id, archived = !showArchived) },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text(if (showArchived) "移出归档" else "归档", color = Muted, fontSize = 9.sp)
                    }
                    TextButton(onClick = { state.deleteWorkflow(workflow.id) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("删除", color = AssistantDown, fontSize = 9.sp)
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

internal fun Int.formatTokenCount(): String = java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(this)

private fun effortLabel(value: String): String = when (value) {
    "auto" -> "自动"
    "none" -> "关闭"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "max" -> "最大"
    else -> value
}
