package cn.market.assistant

import androidx.compose.runtime.Stable
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.market.assistant.data.assistant.LlmChatModelFactory
import cn.market.assistant.data.assistant.LlmModelCatalog
import cn.market.assistant.data.assistant.LlmSecretStore
import cn.market.assistant.domain.assistant.AgentEvent
import cn.market.assistant.domain.assistant.AgentLoop
import cn.market.assistant.domain.assistant.AgentToolAuthorization
import cn.market.assistant.domain.assistant.AgentToolRegistry
import cn.market.assistant.domain.assistant.AssistantActionKind
import cn.market.assistant.domain.assistant.AssistantActionProposal
import cn.market.assistant.domain.assistant.AssistantChart
import cn.market.assistant.domain.assistant.AssistantCitation
import cn.market.assistant.domain.assistant.AssistantEngine
import cn.market.assistant.domain.assistant.AssistantFact
import cn.market.assistant.domain.assistant.AssistantMemoryRanker
import cn.market.assistant.domain.assistant.AssistantMemoryStore
import cn.market.assistant.domain.assistant.AssistantSessionState
import cn.market.assistant.domain.assistant.AssistantSkill
import cn.market.assistant.domain.assistant.AssistantSkillMatcher
import cn.market.assistant.domain.assistant.AssistantSkillStore
import cn.market.assistant.domain.assistant.AssistantWorkflow
import cn.market.assistant.domain.assistant.AssistantWorkflowStore
import cn.market.assistant.domain.assistant.ChatAttachment
import cn.market.assistant.domain.assistant.ChatMessage
import cn.market.assistant.domain.assistant.ChatRole
import cn.market.assistant.domain.assistant.ChatUsage
import cn.market.assistant.domain.assistant.HabitSummary
import cn.market.assistant.domain.assistant.LlmProvider
import cn.market.assistant.domain.assistant.LlmModelCapabilities
import cn.market.assistant.domain.assistant.LlmSettings
import cn.market.assistant.domain.assistant.LlmEndpointCheck
import cn.market.assistant.domain.assistant.LlmEndpointValidator
import cn.market.assistant.domain.assistant.MemoryEvent
import cn.market.assistant.domain.assistant.MemoryKind
import cn.market.assistant.domain.portfolio.TradeSide
import java.math.BigDecimal
import cn.market.assistant.domain.assistant.WorkflowExport
import cn.market.assistant.domain.assistant.WorkflowStatus
import cn.market.assistant.domain.assistant.WorkflowTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal enum class AssistantTurnStatus { RUNNING, DONE, FAILED }

internal enum class AssistantStepStatus { RUNNING, DONE, FAILED }

internal data class AssistantToolStepUi(
    val toolId: String,
    val argumentSummary: String,
    val status: AssistantStepStatus,
    val factCount: Int = 0,
    val startedAtMillis: Long = 0L,
    val durationMillis: Long? = null
)

internal data class AssistantAttachmentUi(
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val text: String? = null,
    val base64Image: String? = null,
    val localUri: String? = null,
    val thumbnail: Bitmap? = null
) {
    val isImage: Boolean get() = base64Image != null
    val sizeLabel: String get() = when {
        sizeBytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", sizeBytes / 1_048_576.0)
        sizeBytes >= 1_024 -> String.format(java.util.Locale.US, "%.0f KB", sizeBytes / 1_024.0)
        else -> "$sizeBytes B"
    }

    val extension: String get() = fileName.substringAfterLast('.', "").uppercase().take(4)
}

internal data class AssistantTurnUi(
    val id: String,
    val question: String,
    val attachments: List<AssistantAttachmentUi> = emptyList(),
    val skillId: String? = null,
    val skillName: String? = null,
    val status: AssistantTurnStatus = AssistantTurnStatus.RUNNING,
    val steps: List<AssistantToolStepUi> = emptyList(),
    val answer: String = "",
    val citations: List<AssistantCitation> = emptyList(),
    val usage: ChatUsage = ChatUsage(),
    val modelLabel: String? = null,
    val startedAtMillis: Long = 0L,
    val finishedAtMillis: Long? = null,
    val offlineFallback: Boolean = false,
    val errorMessage: String? = null,
    val pendingAction: AssistantActionProposal? = null,
    val actionInProgress: Boolean = false,
    val actionResult: String? = null,
    val charts: List<AssistantChart> = emptyList()
) {
    val elapsedMillis: Long? get() = finishedAtMillis?.let { it - startedAtMillis }
}

/**
 * Assistant state: model settings, custom skills, persisted workflows and the habit memory, plus the
 * live turn list. Every network call goes through [AgentLoop]; when no model is configured or the
 * provider fails, the offline fact engine answers instead and the turn is marked as offline.
 */
@Stable
internal class AssistantFeatureState(
    private val skillStore: AssistantSkillStore,
    private val memoryStore: AssistantMemoryStore,
    private val workflowStore: AssistantWorkflowStore,
    private val registry: AgentToolRegistry,
    private val settingsStore: AssistantSettingsStore,
    private val secretStore: LlmSecretStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val offlineFacts: () -> List<AssistantFact>,
    private val ledger: Ledger,
    private val riskFeatureState: PortfolioRiskFeatureState,
    private val onPortfolioChanged: () -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    var settings by mutableStateOf(settingsStore.load())
        private set
    var hasApiKey by mutableStateOf(false)
        private set
    var enabledToolIds by mutableStateOf(emptySet<String>())
        private set
    var skills by mutableStateOf(emptyList<AssistantSkill>())
        private set
    var workflows by mutableStateOf(emptyList<AssistantWorkflow>())
        private set
    var habits by mutableStateOf(HabitSummary())
        private set
    var turns by mutableStateOf(emptyList<AssistantTurnUi>())
        private set
    var currentWorkflow by mutableStateOf<AssistantWorkflow?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var connectionCheck by mutableStateOf<String?>(null)
        private set
    var checkingConnection by mutableStateOf(false)
        private set
    var modelCapabilities by mutableStateOf<LlmModelCapabilities?>(null)
        private set
    var availableModels by mutableStateOf(emptyList<LlmModelCapabilities>())
        private set
    var checkingModelCapabilities by mutableStateOf(false)
        private set
    var modelCapabilitiesMessage by mutableStateOf<String?>(null)
        private set

    private var runJob: Job? = null
    private var turnCounter = 0L
    private var modelCatalogKey: String? = null

    val modelLabel: String
        get() = if (isLive) "${settings.provider.label} · ${settings.model}" else "离线事实模式"

    val isLive: Boolean
        get() = settings.isConfigured &&
            (settings.provider == LlmProvider.OLLAMA || hasApiKey)

    /** All registered tools with labels, for the settings extension list. */
    val toolDescriptors get() = registry.descriptors(registry.toolIds)

    /** Tools that only work after the "allow local changes" switch is on. */
    val localChangeToolIds: Set<String> get() = LOCAL_CHANGE_TOOLS
    val hasPendingAction: Boolean get() = turns.any { it.pendingAction != null || it.actionInProgress }

    fun refresh() {
        scope.launch {
            val (loadedSkills, loadedWorkflows, events) = withContext(ioDispatcher) {
                Triple(skillStore.skills(), workflowStore.workflows(limit = 200), memoryStore.memoryEvents())
            }
            skills = loadedSkills
            workflows = loadedWorkflows
            lazyMemoryEvents = events
            habits = AssistantMemoryRanker.summarize(events)
            refreshSecrets()
        }
    }

    private fun refreshSecrets() {
        hasApiKey = secretStore.hasApiKey(settings.provider.name.lowercase())
        enabledToolIds = (settingsStore.enabledToolIds() ?: registry.toolIds) +
            (if (settingsStore.enabledToolIds() != null) setOf("portfolio.propose_change") else emptySet())
    }

    private fun catalogKey(value: LlmSettings): String =
        "${value.provider.name}|${value.protocol.name}|${value.baseUrl.trimEnd('/')}"

    private fun catalogSummary(
        modelCount: Int,
        modelId: String,
        capabilities: LlmModelCapabilities?
    ): String {
        if (capabilities == null) {
            return if (modelId.isBlank()) {
                "已读取 $modelCount 个可用模型；请选择模型完成配置。"
            } else {
                "已读取 $modelCount 个可用模型；当前模型「$modelId」不在目录中，请从列表选择。"
            }
        }
        val image = when (capabilities.supportsImageInput) {
            true -> "支持图片"
            false -> "仅文本"
            null -> "图片能力未公开"
        }
        val contextWindow = capabilities.contextWindow
        val maxInputTokens = capabilities.maxInputTokens
        val context = when {
            contextWindow != null -> "上下文 ${contextWindow.formatTokenCount()}"
            maxInputTokens != null -> "最大输入 ${maxInputTokens.formatTokenCount()}"
            else -> "上下文未公开"
        }
        val output = capabilities.maxOutputTokens?.let { "输出上限 ${it.formatTokenCount()}" } ?: "输出上限未公开"
        return "已读取 $modelCount 个可用模型；当前模型：$image · $context · $output"
    }

    fun updateSettings(next: LlmSettings, apiKey: String?) {
        val normalized = next.normalized()
        val providedApiKey = apiKey?.takeIf(String::isNotBlank)
        val savedApiKey = runCatching { secretStore.loadApiKey(settings.provider.name.lowercase()) }.getOrNull()
        val catalogCredentialsChanged = providedApiKey != null && providedApiKey != savedApiKey && modelCatalogKey != null
        val endpointChanged = modelCatalogKey != null &&
            (modelCatalogKey != catalogKey(normalized) || catalogCredentialsChanged)
        if (endpointChanged) {
            availableModels = emptyList()
            modelCatalogKey = null
            modelCapabilities = null
            modelCapabilitiesMessage = null
        }
        modelCapabilities = availableModels.firstOrNull { it.modelId == normalized.model }
        settings = normalized.copy(
            modelCapabilities = modelCapabilities,
            imageInputEnabled = when (modelCapabilities?.supportsImageInput) {
                true -> normalized.imageInputEnabled
                false -> false
                null -> normalized.imageInputEnabled
            }
        )
        if (availableModels.isNotEmpty()) {
            modelCapabilitiesMessage = catalogSummary(availableModels.size, normalized.model, modelCapabilities)
        }
        settingsStore.save(settings.copy(modelCapabilities = null))
        apiKey?.takeIf { it.isNotBlank() }?.let { key ->
            runCatching { secretStore.saveApiKey(settings.provider.name.lowercase(), key) }
                .onFailure { statusMessage = it.userFacingMessage("API Key 保存失败") }
            // A new key replaces the old one, so a previous "key cleared" notice is no longer true.
            if (statusMessage?.contains("API Key") == true) statusMessage = null
        }
        refreshSecrets()
        connectionCheck = null
    }

    fun readAvailableModels() {
        if (checkingModelCapabilities) return
        scope.launch {
            checkingModelCapabilities = true
            modelCapabilitiesMessage = null
            val validation = LlmEndpointValidator.validateModelCatalog(settings)
            if (validation is LlmEndpointCheck.Invalid) {
                modelCapabilitiesMessage = validation.reason
                checkingModelCapabilities = false
                return@launch
            }
            try {
                val apiKey = secretStore.loadApiKey(settings.provider.name.lowercase())
                val models = LlmModelCatalog(ioDispatcher = ioDispatcher).readAvailableModels(settings, apiKey)
                modelCatalogKey = catalogKey(settings)
                availableModels = models
                val capabilities = models.firstOrNull { it.modelId == settings.model }
                modelCapabilities = capabilities
                settings = settings.copy(modelCapabilities = capabilities)
                val imageCapability = capabilities?.supportsImageInput
                if (imageCapability != null) {
                    settings = settings.copy(imageInputEnabled = imageCapability)
                }
                modelCapabilitiesMessage = catalogSummary(models.size, settings.model, capabilities)
                if (capabilities == null) {
                    checkingModelCapabilities = false
                    return@launch
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                modelCapabilities = null
                availableModels = emptyList()
                modelCatalogKey = null
                modelCapabilitiesMessage = "读取失败：${error.userFacingMessage("服务未提供模型目录")}".take(240)
            } finally {
                checkingModelCapabilities = false
            }
        }
    }

    fun clearApiKey() {
        secretStore.clearApiKey(settings.provider.name.lowercase())
        refreshSecrets()
        connectionCheck = null
        modelCapabilities = null
        availableModels = emptyList()
        modelCatalogKey = null
        modelCapabilitiesMessage = null
        statusMessage = "已清除本机保存的 API Key"
    }

    fun setToolEnabled(toolId: String, enabled: Boolean) {
        val next = if (enabled) enabledToolIds + toolId else enabledToolIds - toolId
        enabledToolIds = next
        settingsStore.saveEnabledToolIds(next)
    }

    fun testConnection() {
        if (checkingConnection) return
        scope.launch {
            checkingConnection = true
            connectionCheck = null
            val validation = LlmEndpointValidator.validate(settings)
            if (validation is LlmEndpointCheck.Invalid) {
                connectionCheck = validation.reason
                checkingConnection = false
                return@launch
            }
            val model = LlmChatModelFactory.create(
                settings.copy(modelCapabilities = modelCapabilities?.takeIf { it.modelId == settings.model }),
                secretStore.loadApiKey(settings.provider.name.lowercase()),
                ioDispatcher
            )
            if (model == null) {
                connectionCheck = "缺少 API Key，或接口地址/模型未填写"
                checkingConnection = false
                return@launch
            }
            connectionCheck = try {
                val response = model.complete(
                    cn.market.assistant.domain.assistant.ChatRequest(
                        messages = listOf(
                            ChatMessage(ChatRole.SYSTEM, "你是连通性测试，只回复 ok。"),
                            ChatMessage(ChatRole.USER, "ping")
                        ),
                        temperature = settings.temperature,
                        maxTokens = settings.maxTokens,
                        provider = settings.provider,
                        reasoningEffort = settings.reasoningEffort,
                        capabilities = modelCapabilities?.takeIf { it.modelId == settings.model }
                    )
                )
                response.message.content.take(28).ifBlank {
                    "服务已响应，但模型没有返回可见文字；请检查模型名和输出限制。"
                }.let { reply ->
                    if (reply.startsWith("服务已响应")) reply else
                        "连接成功 · ${settings.protocol.label} · ${settings.model}：$reply · 输入 ${response.usage.promptTokens} / 输出 ${response.usage.completionTokens} tokens"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                "连接失败：${error.userFacingMessage("服务未响应，请检查网络与模型配置")}"
            }
            checkingConnection = false
        }
    }

    fun newWorkflow(title: String = "新会话") {
        if (hasPendingAction) {
            statusMessage = "先确认或取消待处理的本地更改"
            return
        }
        val now = nowMillis()
        val workflow = AssistantWorkflow(
            id = UUID.randomUUID().toString().take(12),
            title = title,
            status = WorkflowStatus.ACTIVE,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        currentWorkflow = workflow
        turns = emptyList()
        scope.launch { withContext(ioDispatcher) { workflowStore.upsertWorkflow(workflow) } }
    }

    fun resumeWorkflow(id: String) {
        scope.launch {
            val stored = withContext(ioDispatcher) { workflowStore.workflow(id) } ?: return@launch
            val workflow = stored.copy(status = WorkflowStatus.ACTIVE)
            currentWorkflow = workflow
            if (stored.status == WorkflowStatus.ARCHIVED) withContext(ioDispatcher) { workflowStore.upsertWorkflow(workflow) }
            workflows = withContext(ioDispatcher) { workflowStore.workflows(limit = 200) }
            turns = workflow.turns.map { turn ->
                AssistantTurnUi(
                    id = "wf-${turn.atMillis}-${turn.question.hashCode()}",
                    question = turn.question,
                    skillId = turn.skillId,
                    status = if (turn.failed) AssistantTurnStatus.FAILED else AssistantTurnStatus.DONE,
                    answer = turn.answer,
                    usage = ChatUsage(),
                    startedAtMillis = turn.atMillis,
                    finishedAtMillis = turn.atMillis,
                    offlineFallback = false
                )
            }
        }
    }

    fun archiveWorkflow(id: String, archived: Boolean = true) {
        if (hasPendingAction) {
            statusMessage = "先确认或取消待处理的本地更改"
            return
        }
        scope.launch {
            val workflow = withContext(ioDispatcher) { workflowStore.workflow(id) } ?: return@launch
            val updated = workflow.copy(status = if (archived) WorkflowStatus.ARCHIVED else WorkflowStatus.ACTIVE)
            val loaded = withContext(ioDispatcher) {
                workflowStore.upsertWorkflow(updated)
                workflowStore.workflows(limit = 200)
            }
            workflows = loaded
            if (archived && currentWorkflow?.id == id) {
                currentWorkflow = null
                turns = emptyList()
            } else if (!archived && currentWorkflow?.id == id) {
                currentWorkflow = updated
            }
            statusMessage = if (archived) "会话已归档" else "会话已移出归档"
        }
    }

    fun renameCurrentWorkflow(title: String) {
        val workflow = currentWorkflow ?: return
        val updated = workflow.copy(title = title.take(120).ifBlank { workflow.title })
        currentWorkflow = updated
        scope.launch { withContext(ioDispatcher) { workflowStore.upsertWorkflow(updated) } }
    }

    fun deleteWorkflow(id: String) {
        scope.launch {
            val remaining = withContext(ioDispatcher) {
                workflowStore.deleteWorkflow(id)
                workflowStore.workflows()
            }
            workflows = remaining
            if (currentWorkflow?.id == id) {
                currentWorkflow = null
                turns = emptyList()
            }
        }
    }

    fun clearAllWorkflows() {
        if (hasPendingAction) {
            statusMessage = "先确认或取消待处理的本地更改"
            return
        }
        scope.launch {
            withContext(ioDispatcher) { workflowStore.clearWorkflows() }
            workflows = emptyList()
            currentWorkflow = null
            turns = emptyList()
        }
    }

    fun saveSkill(skill: AssistantSkill) {
        scope.launch {
            val (stored, loaded) = withContext(ioDispatcher) {
                skillStore.upsertSkill(skill) to skillStore.skills()
            }
            skills = loaded
            statusMessage = "已保存技能「${stored.name}」"
        }
    }

    fun deleteSkill(id: String) {
        scope.launch {
            skills = withContext(ioDispatcher) {
                skillStore.deleteSkill(id)
                skillStore.skills()
            }
        }
    }

    fun clearMemory() {
        scope.launch {
            withContext(ioDispatcher) { memoryStore.clearMemory() }
            lazyMemoryEvents = emptyList()
            habits = HabitSummary()
            statusMessage = "已清空本机使用习惯"
        }
    }

    fun applyPromptPreset(presetId: String) {
        val preset = AssistantPromptPresets.all.firstOrNull { it.id == presetId } ?: return
        settings = settings.copy(systemPrompt = preset.prompt)
        settingsStore.save(settings)
        statusMessage = "已应用提示词预设「${preset.label}」"
    }

    fun dismissStatus() {
        statusMessage = null
    }

    fun workflowMarkdown(): String? {
        val workflow = currentWorkflow ?: return null
        return WorkflowExport.toMarkdown(workflow, nowMillis())
    }

    fun memoryMarkdown(): String = buildString {
        appendLine("# 使用习惯与分析方法")
        appendLine()
        appendLine("导出时间：${java.time.Instant.ofEpochMilli(nowMillis())}")
        appendLine("以下内容来自本机加密存储，不会随导出自动发送给模型服务。")
        appendLine()
        appendLine("## 明确记住的方法与偏好")
        val preferences = lazyMemoryEvents.asSequence()
            .filter { it.kind == MemoryKind.PREFERENCE }
            .sortedByDescending { it.atMillis }
            .distinctBy { it.key }
            .take(100)
            .toList()
        if (preferences.isEmpty()) appendLine("暂无") else preferences.forEach { appendLine("- ${it.key}") }
        appendLine()
        appendLine("## 常看标的")
        habits.topInstruments.forEach { appendLine("- ${it.key}：${it.count} 次") }
        appendLine()
        appendLine("## 自定义技能")
        if (skills.isEmpty()) appendLine("暂无") else skills.forEach { skill ->
            appendLine("### ${skill.name}（/${skill.id}）")
            appendLine(skill.description)
            appendLine()
            appendLine(skill.instructions)
            appendLine()
        }
        appendLine("---")
        appendLine("记录只用于复盘与个性化数据分析，不构成投资建议。")
    }

    fun stop() {
        runJob?.cancel()
        runJob = null
        busy = false
        val running = turns.firstOrNull { it.status == AssistantTurnStatus.RUNNING } ?: return
        turns = turns.map { turn ->
            if (turn.id == running.id) {
                turn.copy(
                    status = AssistantTurnStatus.FAILED,
                    errorMessage = "已手动停止",
                    answer = turn.answer.ifBlank { "本轮已停止。" },
                    finishedAtMillis = nowMillis()
                )
            } else turn
        }
    }

    fun retry(turnId: String) {
        val turn = turns.firstOrNull { it.id == turnId } ?: return
        turns = turns.filterNot { it.id == turnId }
        send(turn.question, turn.attachments)
    }

    fun send(question: String, attachments: List<AssistantAttachmentUi>) {
        if (busy) return
        if (hasPendingAction) {
            statusMessage = "先确认或取消待处理的本地更改"
            return
        }
        val trimmed = question.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        val effectiveQuestion = trimmed.ifEmpty { "请分析附件内容" }
        val skill = AssistantSkillMatcher.match(effectiveQuestion, skills)
        val habitPrompt = AssistantMemoryRanker.habitPrompt(
            habits,
            AssistantMemoryRanker.retrieve(effectiveQuestion, lazyMemoryEvents, limit = 4, nowMillis = nowMillis())
        )
        val systemPrompt = buildString {
            append(AssistantSkillMatcher.composeSystemPrompt(settings.systemPrompt, skill))
            habitPrompt?.let { append("\n\n").append(it) }
        }
        val turnId = "turn-${turnCounter++}-${nowMillis()}"
        val startedAt = nowMillis()
        turns = turns + AssistantTurnUi(
            id = turnId,
            question = effectiveQuestion,
            attachments = attachments,
            skillId = skill?.id,
            skillName = skill?.name,
            startedAtMillis = startedAt,
            modelLabel = if (isLive) "${settings.provider.label} · ${settings.model}" else "离线事实模式"
        )
        recordMemory(MemoryKind.QUESTION, effectiveQuestion.take(60), startedAt)
        effectiveQuestion.extractInstrumentHints().forEach { recordMemory(MemoryKind.INSTRUMENT, it, startedAt) }
        recordMemory(MemoryKind.MODEL, settings.model.ifBlank { settings.provider.label }, startedAt)
        skill?.let { recordMemory(MemoryKind.SKILL_USE, it.name, startedAt) }

        val rememberedPreference = effectiveQuestion.explicitRememberPreference()
        if (rememberedPreference != null) {
            recordMemory(MemoryKind.PREFERENCE, rememberedPreference, startedAt)
            turns = turns.map { turn ->
                if (turn.id != turnId) turn else turn.copy(
                    status = AssistantTurnStatus.DONE,
                    answer = "好，我已在本机记住这条分析方法：\n\n$rememberedPreference",
                    offlineFallback = true,
                    finishedAtMillis = nowMillis()
                )
            }
            scope.launch { persistTurn(turnId) }
            return
        }

        busy = true
        runJob = scope.launch {
            try {
                val model = if (isLive) {
                    LlmChatModelFactory.create(
                        settings.copy(modelCapabilities = modelCapabilities?.takeIf { it.modelId == settings.model }),
                        secretStore.loadApiKey(settings.provider.name.lowercase()),
                        ioDispatcher
                    )
                } else {
                    null
                }
                if (model == null) {
                    answerOffline(turnId, effectiveQuestion, attachments, "未配置模型，使用离线事实回答")
                    return@launch
                }
                val history = turns.toChatHistory(turnId)
                val loop = AgentLoop(model, registry, maxToolRounds = AgentLoop.DEFAULT_MAX_TOOL_ROUNDS, nowMillis = nowMillis)
                val allowed = (skill?.toolIds?.takeIf { it.isNotEmpty() }?.toSet() ?: enabledToolIds)
                    .intersect(enabledToolIds.ifEmpty { registry.toolIds })
                    .let { ids ->
                        // The master switch is separate from the per-tool list: turning it off removes
                        // every tool that can propose a local write, even if the tool stayed enabled.
                        if (settings.allowLocalChanges) ids else ids - LOCAL_CHANGE_TOOLS
                    }
                val authorization = AgentToolAuthorization(
                    allowedToolIds = allowed,
                    sharePrivateDetails = settings.sharePrivateDetails
                )
                val result = loop.run(
                    question = effectiveQuestion,
                    history = history,
                    attachments = attachments.map { it.toChatAttachment() },
                    authorization = authorization,
                    settings = settings.copy(systemPrompt = systemPrompt)
                ) { event -> onAgentEvent(turnId, event) }
                if (result.failureReason != null) {
                    answerOffline(
                        turnId,
                        effectiveQuestion,
                        attachments,
                        "模型不可用（${result.failureReason}），已回退离线事实回答",
                        keepSteps = true,
                        classificationHint = skill?.name
                    )
                    return@launch
                }
                if (skill != null) skillStore.recordSkillUse(skill.id, nowMillis())
                result.toolCallIds.forEach { toolId -> recordMemory(MemoryKind.TOOL_USE, toolId, nowMillis()) }
                turns = turns.map { turn ->
                    if (turn.id != turnId) turn else turn.copy(
                        status = AssistantTurnStatus.DONE,
                        answer = result.reply.text,
                        citations = result.reply.citations,
                        usage = result.usage,
                        pendingAction = result.pendingAction,
                        steps = turn.steps.map { step ->
                            if (step.status == AssistantStepStatus.RUNNING) {
                                step.copy(status = AssistantStepStatus.DONE, durationMillis = 0L)
                            } else step
                        },
                        finishedAtMillis = nowMillis()
                    )
                }
                if (result.pendingAction == null) persistTurn(turnId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                answerOffline(
                    turnId,
                    effectiveQuestion,
                    attachments,
                    "调用失败（${error.userFacingMessage("模型未返回可用结果")}），已回退离线事实回答",
                    keepSteps = true,
                    classificationHint = skill?.name
                )
            } finally {
                busy = false
            }
        }
    }

    fun confirmAction(turnId: String) {
        val turn = turns.firstOrNull { it.id == turnId } ?: return
        val action = turn.pendingAction ?: return
        if (turn.actionInProgress) return
        turns = turns.map { if (it.id == turnId) it.copy(actionInProgress = true) else it }
        scope.launch {
            val applied = runCatching {
                withContext(ioDispatcher) { applyLocalAction(action) }
            }
            applied.onSuccess { message ->
                memoryStore.recordMemory(MemoryEvent(MemoryKind.ACTION, action.summary.take(200), nowMillis()))
                lazyMemoryEvents = memoryStore.memoryEvents(limit = 200)
                habits = AssistantMemoryRanker.summarize(lazyMemoryEvents)
                turns = turns.map { current ->
                    if (current.id != turnId) current else current.copy(
                        pendingAction = null,
                        actionInProgress = false,
                        actionResult = message,
                        answer = current.answer
                    )
                }
                if (action.kind in setOf(
                        AssistantActionKind.ADD_FUND_WATCHLIST,
                        AssistantActionKind.REMOVE_FUND_WATCHLIST,
                        AssistantActionKind.RECORD_FUND_BUY,
                        AssistantActionKind.RECORD_FUND_SELL
                    )
                ) onPortfolioChanged()
                persistTurn(turnId)
            }.onFailure { error ->
                turns = turns.map { current ->
                    if (current.id != turnId) current else current.copy(
                        actionInProgress = false,
                        errorMessage = error.userFacingMessage("本地更改没有保存")
                    )
                }
            }
        }
    }

    fun cancelAction(turnId: String) {
        val turn = turns.firstOrNull { it.id == turnId } ?: return
        if (turn.pendingAction == null || turn.actionInProgress) return
        turns = turns.map { current ->
            if (current.id != turnId) current else current.copy(
                pendingAction = null,
                actionResult = "已取消，没有修改本机数据。",
                answer = current.answer + "\n\n已取消，没有修改本机数据。"
            )
        }
        scope.launch { persistTurn(turnId) }
    }

    private suspend fun applyLocalAction(action: AssistantActionProposal): String = when (action.kind) {
        AssistantActionKind.ADD_FUND_WATCHLIST -> {
            ledger.setFundWatched(
                requireNotNull(action.fundCode), requireNotNull(action.fundName), true, action.fundCategory
            )
            "已将 ${action.fundName}（${action.fundCode}）加入基金自选。"
        }
        AssistantActionKind.REMOVE_FUND_WATCHLIST -> {
            ledger.setFundWatched(
                requireNotNull(action.fundCode), requireNotNull(action.fundName), false, action.fundCategory
            )
            "已将 ${action.fundName}（${action.fundCode}）从基金自选移除；账本流水与持仓记录保留。"
        }
        AssistantActionKind.RECORD_FUND_BUY, AssistantActionKind.RECORD_FUND_SELL -> {
            val code = requireNotNull(action.fundCode)
            val name = requireNotNull(action.fundName)
            val asset = ledger.findAsset(code) ?: ledger.addFundAsset(code, name, action.fundCategory)
            ledger.addTrade(
                asset = asset,
                side = if (action.kind == AssistantActionKind.RECORD_FUND_BUY) TradeSide.BUY else TradeSide.SELL,
                amount = requireNotNull(action.amount),
                units = requireNotNull(action.units),
                price = requireNotNull(action.unitPrice),
                fee = action.fee ?: BigDecimal.ZERO
            )
            val label = if (action.kind == AssistantActionKind.RECORD_FUND_BUY) "买入" else "卖出"
            "已在本机账本记录 $label ${action.fundName}（${action.fundCode}）；没有提交任何交易订单。"
        }
        AssistantActionKind.UPDATE_RISK_PARAMETERS -> {
            riskFeatureState.update(
                requireNotNull(action.riskFreeAnnualRate),
                requireNotNull(action.minimumAcceptableAnnualReturn)
            )
            "风险分析参数已保存，重新打开分析页后会按新参数重算。"
        }
        AssistantActionKind.SAVE_PREFERENCE -> {
            val preference = requireNotNull(action.preference)
            require(preference.length in 4..500) { "分析偏好长度无效" }
            memoryStore.recordMemory(MemoryEvent(MemoryKind.PREFERENCE, preference, nowMillis()))
            lazyMemoryEvents = memoryStore.memoryEvents(limit = 200)
            habits = AssistantMemoryRanker.summarize(lazyMemoryEvents)
            "已在本机记住这条分析方法，后续回答会优先参考。"
        }
        AssistantActionKind.SAVE_SKILL -> {
            val name = requireNotNull(action.skillName)
            val instructions = requireNotNull(action.skillInstruction)
            val existing = skills.firstOrNull { it.name == name }
            val skill = AssistantSkill(
                id = existing?.id ?: newSkillId(),
                name = name,
                description = action.skillTrigger?.takeIf { it.isNotBlank() }?.let { "触发词：$it" } ?: "由助手创建的技能",
                instructions = instructions,
                triggerWords = action.skillTrigger.orEmpty()
                    .split('、', ',', '，', ' ')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(6),
                createdAtMillis = existing?.createdAtMillis ?: nowMillis(),
                updatedAtMillis = nowMillis()
            )
            val stored = skillStore.upsertSkill(skill)
            skills = skillStore.skills()
            "已保存技能「${stored.name}」，可以用 /技能ID 或触发词调用。"
        }
    }

    private fun newSkillId(): String {
        val existing = skills.map { it.id }.toSet()
        var index = skills.size + 1
        while ("skill$index" in existing) index++
        return "skill$index"
    }

    private companion object {
        /** Tools that can propose a local change and therefore require the permission switch. */
        val LOCAL_CHANGE_TOOLS = setOf("portfolio.propose_change")
    }

    private var lazyMemoryEvents: List<MemoryEvent> = emptyList()

    private suspend fun onAgentEvent(turnId: String, event: AgentEvent) {
        val now = nowMillis()
        turns = turns.map { turn ->
            if (turn.id != turnId) return@map turn
            when (event) {
                is AgentEvent.ModelCallStarted -> turn
                is AgentEvent.ToolCallStarted -> turn.copy(
                    steps = turn.steps + AssistantToolStepUi(
                        toolId = event.toolId,
                        argumentSummary = event.argumentSummary,
                        status = AssistantStepStatus.RUNNING,
                        startedAtMillis = now
                    )
                )
                is AgentEvent.ToolCallFinished -> turn.copy(
                    steps = turn.steps.map { step ->
                        if (step.toolId == event.toolId && step.status == AssistantStepStatus.RUNNING) {
                            step.copy(
                                status = if (event.succeeded) AssistantStepStatus.DONE else AssistantStepStatus.FAILED,
                                factCount = event.factCount,
                                durationMillis = (now - step.startedAtMillis).coerceAtLeast(0)
                            )
                        } else step
                    }
                )
                is AgentEvent.Answer -> turn.copy(answer = event.text)
                is AgentEvent.ChartReady -> if (turn.charts.any { it.title == event.chart.title }) {
                    turn
                } else {
                    turn.copy(charts = (turn.charts + event.chart).take(3))
                }
                is AgentEvent.Failed -> turn.copy(errorMessage = event.reason)
            }
        }
    }

    private suspend fun answerOffline(
        turnId: String,
        question: String,
        attachments: List<AssistantAttachmentUi>,
        note: String,
        keepSteps: Boolean = false,
        classificationHint: String? = null
    ) {
        val attachmentText = attachments.filter { it.text != null }
            .joinToString("\n\n") { "【附件：${it.fileName}】\n${it.text?.take(4_000)}" }
        val classificationText = listOfNotNull(classificationHint?.takeIf { it.isNotBlank() }, question)
            .joinToString(" ")
        val offlineQuestion = if (attachmentText.isBlank()) {
            classificationText
        } else {
            "$classificationText\n\n$attachmentText"
        }
        val (_, reply) = AssistantEngine.ask(
            AssistantSessionState(
                conversationId = currentWorkflow?.id ?: "local",
                consent = if (settings.sharePrivateDetails) {
                    cn.market.assistant.domain.assistant.AssistantDataSharingConsent.INCLUDE_PRIVATE_DETAILS
                } else {
                    cn.market.assistant.domain.assistant.AssistantDataSharingConsent.PUBLIC_AND_AGGREGATE
                }
            ),
            offlineQuestion,
            offlineFacts()
        )
        turns = turns.map { turn ->
            if (turn.id != turnId) turn else turn.copy(
                status = AssistantTurnStatus.DONE,
                answer = reply.text,
                citations = reply.citations,
                offlineFallback = true,
                errorMessage = note,
                steps = if (keepSteps) turn.steps else emptyList(),
                finishedAtMillis = nowMillis()
            )
        }
        persistTurn(turnId)
    }

    private suspend fun persistTurn(turnId: String) {
        val turn = turns.firstOrNull { it.id == turnId } ?: return
        val workflow = currentWorkflow ?: AssistantWorkflow(
            id = UUID.randomUUID().toString().take(12),
            title = turn.question.take(24).ifBlank { "新会话" },
            createdAtMillis = turn.startedAtMillis,
            updatedAtMillis = turn.startedAtMillis
        ).also { currentWorkflow = it }
        val storedTurn = WorkflowTurn(
            question = turn.question,
            answer = turn.answer,
            skillId = turn.skillId,
            toolIds = turn.steps.map { it.toolId }.distinct(),
            atMillis = turn.startedAtMillis,
            failed = turn.status == AssistantTurnStatus.FAILED,
            attachmentNames = turn.attachments.map { it.fileName }
        )
        val updated = workflow.copy(
            status = WorkflowStatus.ACTIVE,
            turns = workflow.turns.upsertTurn(storedTurn)
        )
        currentWorkflow = updated
        val loadedWorkflows = withContext(ioDispatcher) {
            workflowStore.upsertWorkflow(updated)
            workflowStore.workflows(limit = 200)
        }
        workflows = loadedWorkflows
    }

    private fun recordMemory(kind: MemoryKind, key: String, atMillis: Long) {
        scope.launch {
            val events = withContext(ioDispatcher) {
                memoryStore.recordMemory(MemoryEvent(kind, key, atMillis))
                memoryStore.memoryEvents(limit = 200)
            }
            lazyMemoryEvents = events
            habits = AssistantMemoryRanker.summarize(lazyMemoryEvents)
        }
    }

    private fun List<WorkflowTurn>.upsertTurn(turn: WorkflowTurn): List<WorkflowTurn> {
        val index = indexOfLast { it.atMillis == turn.atMillis && it.question == turn.question }
        return if (index < 0) this + turn else toMutableList().also { it[index] = turn }
    }

    private fun List<AssistantTurnUi>.toChatHistory(excludeTurnId: String): List<ChatMessage> = asSequence()
        .filter { it.id != excludeTurnId && it.answer.isNotBlank() }
        .toList()
        .takeLast(6)
        .flatMap { turn ->
            listOf(
                ChatMessage(ChatRole.USER, turn.question),
                ChatMessage(ChatRole.ASSISTANT, turn.answer)
            )
        }
}

internal fun AssistantAttachmentUi.toChatAttachment(): ChatAttachment = ChatAttachment(
    fileName = fileName,
    mimeType = mimeType,
    text = text,
    base64Image = base64Image
)

/** Lightweight instrument hints recorded into the habit memory. */
internal fun String.extractInstrumentHints(): List<String> {
    val hints = mutableListOf<String>()
    Regex("\\d{6}").findAll(this).forEach { hints += it.value }
    listOf("黄金", "沪深300", "中证500", "创业板", "科创50", "纳指", "标普", "恒生", "日经", "半导体", "人工智能", "医药", "白酒", "新能源")
        .filter { contains(it) }
        .forEach { hints += it }
    return hints.distinct().take(4)
}

/** Explicit memory commands are honored locally, even when no external model is configured. */
internal fun String.explicitRememberPreference(): String? {
    val trimmed = trim()
    val prefix = listOf(
        "请记住我的分析方法", "记住我的分析方法", "请记住", "记住", "请记一下", "记一下",
        "以后分析时请", "今后分析时请", "以后请按", "我习惯"
    ).firstOrNull { trimmed.startsWith(it) } ?: return null
    val preference = trimmed.removePrefix(prefix).trimStart('：', ':', '，', ',', ' ', '\n', '、')
        .takeIf { it.length in 4..500 } ?: return null
    return preference
}

/** Creates the state wired to the encrypted ledger stores. */
@androidx.compose.runtime.Composable
internal fun rememberAssistantFeatureState(
    ledger: cn.market.assistant.Ledger,
    registry: AgentToolRegistry,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    offlineFacts: () -> List<AssistantFact>,
    riskFeatureState: PortfolioRiskFeatureState,
    onPortfolioChanged: () -> Unit
): AssistantFeatureState {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = androidx.compose.runtime.remember(ledger, registry) {
        AssistantFeatureState(
            skillStore = ledger,
            memoryStore = ledger,
            workflowStore = ledger,
            registry = registry,
            settingsStore = AssistantSettingsStore(context),
            secretStore = LlmSecretStore(context),
            scope = scope,
            ioDispatcher = ioDispatcher,
            offlineFacts = offlineFacts,
            ledger = ledger,
            riskFeatureState = riskFeatureState,
            onPortfolioChanged = onPortfolioChanged
        ).also { it.refresh() }
    }
    return state
}
