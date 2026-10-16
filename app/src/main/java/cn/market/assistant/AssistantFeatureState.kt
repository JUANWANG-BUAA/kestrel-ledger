package cn.market.assistant

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.market.assistant.data.assistant.LlmChatModelFactory
import cn.market.assistant.data.assistant.LlmSecretStore
import cn.market.assistant.domain.assistant.AgentEvent
import cn.market.assistant.domain.assistant.AgentLoop
import cn.market.assistant.domain.assistant.AgentToolAuthorization
import cn.market.assistant.domain.assistant.AgentToolRegistry
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
import cn.market.assistant.domain.assistant.LlmSettings
import cn.market.assistant.domain.assistant.LlmEndpointCheck
import cn.market.assistant.domain.assistant.LlmEndpointValidator
import cn.market.assistant.domain.assistant.MemoryEvent
import cn.market.assistant.domain.assistant.MemoryKind
import cn.market.assistant.domain.assistant.WorkflowExport
import cn.market.assistant.domain.assistant.WorkflowStatus
import cn.market.assistant.domain.assistant.WorkflowTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    val localUri: String? = null
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
    val errorMessage: String? = null
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

    private var runJob: Job? = null
    private var turnCounter = 0L

    val modelLabel: String
        get() = if (isLive) "${settings.provider.label} · ${settings.model}" else "离线事实模式"

    val isLive: Boolean
        get() = settings.isConfigured &&
            (settings.provider == LlmProvider.OLLAMA || hasApiKey)

    /** All registered tools with labels, for the settings extension list. */
    val toolDescriptors get() = registry.descriptors(registry.toolIds)

    fun refresh() {
        scope.launch {
            skills = skillStore.skills()
            workflows = workflowStore.workflows()
            val events = memoryStore.memoryEvents()
            lazyMemoryEvents = events
            habits = AssistantMemoryRanker.summarize(events)
            refreshSecrets()
        }
    }

    private fun refreshSecrets() {
        hasApiKey = secretStore.hasApiKey(settings.provider.name.lowercase())
        enabledToolIds = settingsStore.enabledToolIds() ?: registry.toolIds
    }

    fun updateSettings(next: LlmSettings, apiKey: String?) {
        settings = next.normalized()
        settingsStore.save(settings)
        apiKey?.takeIf { it.isNotBlank() }?.let { key ->
            runCatching { secretStore.saveApiKey(settings.provider.name.lowercase(), key) }
                .onFailure { statusMessage = it.userFacingMessage("API Key 保存失败") }
        }
        refreshSecrets()
        connectionCheck = null
    }

    fun clearApiKey() {
        secretStore.clearApiKey(settings.provider.name.lowercase())
        refreshSecrets()
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
                settings,
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
                        maxTokens = 16
                    )
                )
                "连接成功：${response.message.content.take(40).ifBlank { "（空回复）" }}"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                "连接失败：${error.userFacingMessage("服务未响应，请检查网络与模型配置")}"
            }
            checkingConnection = false
        }
    }

    fun newWorkflow(title: String = "新会话") {
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
        scope.launch { workflowStore.upsertWorkflow(workflow) }
    }

    fun resumeWorkflow(id: String) {
        scope.launch {
            val workflow = workflowStore.workflow(id) ?: return@launch
            currentWorkflow = workflow
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

    fun renameCurrentWorkflow(title: String) {
        val workflow = currentWorkflow ?: return
        val updated = workflow.copy(title = title.take(120).ifBlank { workflow.title })
        currentWorkflow = updated
        scope.launch { workflowStore.upsertWorkflow(updated) }
    }

    fun deleteWorkflow(id: String) {
        scope.launch {
            workflowStore.deleteWorkflow(id)
            workflows = workflowStore.workflows()
            if (currentWorkflow?.id == id) {
                currentWorkflow = null
                turns = emptyList()
            }
        }
    }

    fun clearAllWorkflows() {
        scope.launch {
            workflowStore.clearWorkflows()
            workflows = emptyList()
            currentWorkflow = null
            turns = emptyList()
        }
    }

    fun saveSkill(skill: AssistantSkill) {
        scope.launch {
            val stored = skillStore.upsertSkill(skill)
            skills = skillStore.skills()
            statusMessage = "已保存技能「${stored.name}」"
        }
    }

    fun deleteSkill(id: String) {
        scope.launch {
            skillStore.deleteSkill(id)
            skills = skillStore.skills()
        }
    }

    fun clearMemory() {
        scope.launch {
            memoryStore.clearMemory()
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

        busy = true
        runJob = scope.launch {
            try {
                val model = if (isLive) {
                    LlmChatModelFactory.create(
                        settings,
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
                        steps = turn.steps.map { step ->
                            if (step.status == AssistantStepStatus.RUNNING) {
                                step.copy(status = AssistantStepStatus.DONE, durationMillis = 0L)
                            } else step
                        },
                        finishedAtMillis = nowMillis()
                    )
                }
                persistTurn(turnId)
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
        val updated = workflow.copy(
            status = WorkflowStatus.ACTIVE,
            turns = workflow.turns + WorkflowTurn(
                question = turn.question,
                answer = turn.answer,
                skillId = turn.skillId,
                toolIds = turn.steps.map { it.toolId }.distinct(),
                atMillis = turn.startedAtMillis,
                failed = turn.status == AssistantTurnStatus.FAILED
            )
        )
        currentWorkflow = updated
        workflowStore.upsertWorkflow(updated)
        workflows = workflowStore.workflows()
    }

    private fun recordMemory(kind: MemoryKind, key: String, atMillis: Long) {
        scope.launch {
            memoryStore.recordMemory(MemoryEvent(kind, key, atMillis))
            lazyMemoryEvents = memoryStore.memoryEvents(limit = 200)
            habits = AssistantMemoryRanker.summarize(lazyMemoryEvents)
        }
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

/** Creates the state wired to the encrypted ledger stores. */
@androidx.compose.runtime.Composable
internal fun rememberAssistantFeatureState(
    ledger: cn.market.assistant.Ledger,
    registry: AgentToolRegistry,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    offlineFacts: () -> List<AssistantFact>
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
            offlineFacts = offlineFacts
        ).also { it.refresh() }
    }
    return state
}
