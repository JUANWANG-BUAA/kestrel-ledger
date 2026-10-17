package cn.market.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.domain.assistant.AssistantSkill
import cn.market.assistant.domain.assistant.ChatUsage
import cn.market.assistant.domain.assistant.AssistantWorkflow
import cn.market.assistant.domain.assistant.WorkflowExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val MAX_TEXT_ATTACHMENT_BYTES = 256 * 1_024
private const val MAX_IMAGE_ATTACHMENT_BYTES = 3 * 1_024 * 1_024


/**
 * dsh-style assistant surface: a session timeline with folded tool processes, a draft-attachment rail
 * above the composer, a model seat, and panels for models, skills, workflows and habit memory.
 */
@Composable
internal fun AssistantScreen(
    state: AssistantFeatureState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf(emptyList<AssistantAttachmentUi>()) }
    var sheet by remember { mutableStateOf<AssistantSheet?>(null) }
    var editingSkill by remember { mutableStateOf<AssistantSkill?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var attachmentMenuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val export = pendingExport
        pendingExport = null
        if (uri != null && export != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri) ?: error("无法创建导出文件")
                    output.use { it.write(export.second.toByteArray(Charsets.UTF_8)) }
                }
            }.onFailure { attachmentError = it.message ?: "导出失败" }
        }
    }
    fun exportMarkdown(fileName: String, content: String) {
        pendingExport = fileName to content
        exportLauncher.launch(fileName)
    }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val attachment = withContext(Dispatchers.IO) { readAttachment(context, uri, allowImage = false) }
            if (attachment == null) {
                attachmentError = "无法读取该文件（超过大小限制或格式不支持）"
            } else {
                attachments = attachments + attachment
                attachmentError = null
            }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val attachment = withContext(Dispatchers.IO) { readAttachment(context, uri, allowImage = true) }
            if (attachment == null) {
                attachmentError = "图片无法读取或超过 3 MB"
            } else {
                attachments = attachments + attachment
                attachmentError = null
            }
        }
    }

    LaunchedEffect(state.turns.size, state.turns.lastOrNull()?.answer?.length, state.turns.lastOrNull()?.steps?.size) {
        if (state.turns.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(state.turns.size - 1) }
        }
    }

    Column(
        modifier.fillMaxSize().imePadding().padding(horizontal = 14.dp)
    ) {
        AssistantHeader(
            state = state,
            menuOpen = menuOpen,
            onMenuOpenChange = { menuOpen = it },
            onBack = onBack,
            onNewWorkflow = { state.newWorkflow() },
            onRename = { renaming = true },
            onOpenSheet = { sheet = it }
        )
        state.statusMessage?.let { message ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(message, color = Blue, fontSize = 10.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { state.dismissStatus() }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("知道了", color = Muted, fontSize = 9.sp)
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.turns.isEmpty()) {
                item { AssistantOnboarding(state = state, onOpenSheet = { sheet = it }, onUsePrompt = { draft = it }) }
            }
            items(state.turns, key = { it.id }) { turn -> AssistantTurnCard(turn, state) }
            item { Spacer(Modifier.height(4.dp)) }
        }
        attachmentError?.let { Text(it, color = AssistantDown, fontSize = 10.sp) }
        if (attachments.isNotEmpty()) {
            AssistantAttachmentRail(attachments, onRemove = { index -> attachments = attachments.filterIndexed { i, _ -> i != index } })
        }
        val quickSkills = state.skills.filter { it.enabled }.take(4)
        if (quickSkills.isNotEmpty() && state.turns.isEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                quickSkills.forEach { skill ->
                    TextButton(onClick = { draft = "/${skill.id} " }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("${skill.name}  /${skill.id}", color = Blue, fontSize = 10.sp)
                    }
                }
            }
        }
        AssistantComposer(
            draft = draft,
            busy = state.busy,
            locked = state.hasPendingAction,
            modelLabel = state.modelLabel,
            live = state.isLive,
            imageInputAvailable = state.isLive && state.settings.imageInputEnabled,
            attachmentCount = attachments.size,
            onDraftChange = { draft = it },
            attachmentMenuOpen = attachmentMenuOpen,
            onAttachmentMenuChange = { attachmentMenuOpen = it },
            onSelectFile = { documentPicker.launch(arrayOf("text/*", "application/json")) },
            onSelectImage = { imagePicker.launch(arrayOf("image/*")) },
            onSend = {
                state.send(draft, attachments)
                draft = ""
                attachments = emptyList()
            },
            onStop = { state.stop() },
            onOpenModel = { sheet = AssistantSheet.SETTINGS }
        )
        UsageBar(state.turns.lastOrNull()?.usage)
    }

    AssistantSheets(
        sheet = sheet,
        state = state,
        editingSkill = editingSkill,
        onDismiss = { sheet = null; editingSkill = null },
        onEditSkill = { skill -> editingSkill = skill; sheet = AssistantSheet.SKILL_EDITOR },
        onExportWorkflow = { workflow ->
            val name = workflow.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(36).ifBlank { "会话记录" }
            exportMarkdown("$name.md", WorkflowExport.toMarkdown(workflow, System.currentTimeMillis()))
        },
        onExportMemory = { exportMarkdown("使用习惯与分析方法.md", state.memoryMarkdown()) }
    )
    if (renaming) {
        AssistantRenameDialog(
            initial = state.currentWorkflow?.title ?: "新会话",
            onConfirm = { state.renameCurrentWorkflow(it) },
            onDismiss = { renaming = false }
        )
    }
}

@Composable
private fun AssistantHeader(
    state: AssistantFeatureState,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onNewWorkflow: () -> Unit,
    onRename: () -> Unit,
    onOpenSheet: (AssistantSheet) -> Unit
) {
    val imageMode = state.isLive && state.settings.imageInputEnabled
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        TextButton(onClick = { onOpenSheet(AssistantSheet.WORKFLOWS) }, modifier = Modifier.size(42.dp), contentPadding = PaddingValues(0.dp)) {
            Text("☰", color = Ink, fontSize = 20.sp)
        }
        Column(Modifier.weight(1f).clickable { onRename() }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                state.currentWorkflow?.title ?: "AI 助手",
                color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                when {
                    !state.isLive -> "离线 · 可读本机文字数据"
                    imageMode -> "${state.settings.model} · 图片输入已启用"
                    else -> "${state.settings.model} · 仅文字输入"
                },
                color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onNewWorkflow, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) {
            Text("＋", color = Ink, fontSize = 24.sp)
        }
        Box {
            TextButton(onClick = { onMenuOpenChange(true) }, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) {
                Text("⋯", color = Ink, fontSize = 22.sp)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                DropdownMenuItem(text = { Text("会话工作流", fontSize = 12.sp) }, onClick = {
                    onMenuOpenChange(false); onOpenSheet(AssistantSheet.WORKFLOWS)
                })
                DropdownMenuItem(text = { Text("我的技能", fontSize = 12.sp) }, onClick = {
                    onMenuOpenChange(false); onOpenSheet(AssistantSheet.SKILLS)
                })
                DropdownMenuItem(text = { Text("使用习惯", fontSize = 12.sp) }, onClick = {
                    onMenuOpenChange(false); onOpenSheet(AssistantSheet.HABITS)
                })
                DropdownMenuItem(text = { Text("模型与扩展", fontSize = 12.sp) }, onClick = {
                    onMenuOpenChange(false); onOpenSheet(AssistantSheet.SETTINGS)
                })
                DropdownMenuItem(text = { Text("返回行情", fontSize = 12.sp) }, onClick = {
                    onMenuOpenChange(false); onBack()
                })
            }
        }
    }
}

@Composable
private fun AssistantOnboarding(
    state: AssistantFeatureState,
    onOpenSheet: (AssistantSheet) -> Unit,
    onUsePrompt: (String) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 42.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Text("你好，今天想看什么？", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        Text(
            when {
                !state.isLive -> "可分析本机持仓与账本、搜索基金、查看行情；离线时可读取文本、CSV、JSON。连接视觉模型后可上传图片。"
                state.settings.imageInputEnabled -> "可分析本机持仓与账本、搜索全市场基金、查看行情，也可上传图片或文本文件。"
                else -> "可分析本机持仓与账本、搜索全市场基金、查看行情；当前模型支持文本与文本文件。"
            },
            color = Muted, fontSize = 14.sp, lineHeight = 21.sp
        )
        Text("试试这样问", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        listOf("分析我的持仓结构", "搜索东方基金", "解释今天的黄金分时", "记住我的分析方法：先看风险再看收益").forEach { prompt ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onUsePrompt(prompt) },
                color = MarketTheme.colors.surfaceAlt,
                shape = RoundedCornerShape(17.dp)
            ) {
                Text(prompt, modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp), color = Ink, fontSize = 13.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onOpenSheet(AssistantSheet.SETTINGS) }) {
                Text(if (state.isLive) "模型设置" else "连接模型", color = Blue, fontSize = 11.sp)
            }
            TextButton(onClick = { onOpenSheet(AssistantSheet.HABITS) }) {
                Text("我的方法与记忆", color = Blue, fontSize = 11.sp)
            }
            if (state.skills.isNotEmpty()) {
                TextButton(onClick = { onOpenSheet(AssistantSheet.SKILLS) }) {
                    Text("技能", color = Blue, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun AssistantTurnCard(turn: AssistantTurnUi, state: AssistantFeatureState) {
    val context = LocalContext.current
    val palette = MarketTheme.colors
    var processExpanded by remember(turn.id) { mutableStateOf(false) }
    var citationsExpanded by remember(turn.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (turn.attachments.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.End) {
                    turn.attachments.forEach { attachment -> AssistantAttachmentChip(attachment) }
                }
            }
            Surface(
                modifier = Modifier.widthIn(max = 340.dp),
                color = palette.surfaceAlt,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(turn.question, color = Ink, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp))
            }
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(
                    when (turn.status) {
                        AssistantTurnStatus.RUNNING -> Blue
                        AssistantTurnStatus.DONE -> AssistantDown
                        AssistantTurnStatus.FAILED -> AssistantUp
                    }
                ))
                Text(
                    when (turn.status) {
                        AssistantTurnStatus.RUNNING -> if (turn.steps.any { it.status == AssistantStepStatus.RUNNING }) "正在读取数据" else "正在生成"
                        AssistantTurnStatus.DONE -> if (turn.offlineFallback) "本机事实模式" else "助手"
                        AssistantTurnStatus.FAILED -> "未完成"
                    },
                    color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium
                )
                turn.skillName?.let { Text("· $it", color = Blue, fontSize = 10.sp) }
                Spacer(Modifier.weight(1f))
                turn.modelLabel?.takeIf { turn.status == AssistantTurnStatus.RUNNING }?.let {
                    Text(it, color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (turn.answer.isNotBlank()) MarkdownLite(turn.answer)
            if (turn.steps.isNotEmpty()) {
                TextButton(
                    onClick = { processExpanded = !processExpanded },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                ) {
                    Text(if (processExpanded) "收起数据过程 ⌃" else "查看数据过程 · ${turn.steps.size} 步 ⌄", color = Blue, fontSize = 11.sp)
                }
                AnimatedVisibility(
                    visible = processExpanded,
                    enter = fadeIn(tween(120)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(100)) + shrinkVertically(tween(150))
                ) {
                    Column(
                        Modifier.fillMaxWidth().background(palette.surfaceAlt, RoundedCornerShape(12.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) { turn.steps.forEach { step -> AssistantStepRow(step) } }
                }
            }
            turn.pendingAction?.let { proposal ->
                AssistantActionPreviewCard(
                    proposal = proposal,
                    busy = turn.actionInProgress,
                    onConfirm = { state.confirmAction(turn.id) },
                    onCancel = { state.cancelAction(turn.id) }
                )
            }
            turn.actionResult?.let { message ->
                Text(message, color = AssistantDown, fontSize = 11.sp, lineHeight = 16.sp)
            }
            if (turn.citations.isNotEmpty()) {
                Text(
                    if (citationsExpanded) "收起数据来源 ⌃" else "数据来源 ${turn.citations.size} 条 ⌄",
                    color = Blue, fontSize = 11.sp, modifier = Modifier.clickable { citationsExpanded = !citationsExpanded }
                )
                if (citationsExpanded) turn.citations.forEach { citation ->
                    Text("· ${citation.factKey} · ${citation.source ?: "—"} · ${citation.observedAt ?: "—"}", color = Muted, fontSize = 10.sp)
                }
            }
            turn.errorMessage?.let { Text(it, color = AssistantUp, fontSize = 11.sp, lineHeight = 15.sp) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (turn.answer.isNotBlank()) {
                    TextButton(onClick = { copyToClipboard(context, turn.answer) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("复制", color = Muted, fontSize = 10.sp)
                    }
                }
                if (turn.status != AssistantTurnStatus.RUNNING && turn.pendingAction == null) {
                    TextButton(onClick = { state.retry(turn.id) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("重试", color = Muted, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                val usageText = buildString {
                    if (turn.usage.promptTokens > 0) append("↑${turn.usage.promptTokens} ")
                    if (turn.usage.completionTokens > 0) append("↓${turn.usage.completionTokens} ")
                    turn.elapsedMillis?.takeIf { it > 0 }?.let { append("${it}ms") }
                }
                if (usageText.isNotBlank()) Text(usageText.trim(), color = Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun AssistantActionPreviewCard(
    proposal: cn.market.assistant.domain.assistant.AssistantActionProposal,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val palette = MarketTheme.colors
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, palette.line, RoundedCornerShape(16.dp))
            .background(palette.surfaceAlt, RoundedCornerShape(16.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("确认本机更改", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(proposal.summary, color = Ink, fontSize = 13.sp, lineHeight = 19.sp)
        if (proposal.kind == cn.market.assistant.domain.assistant.AssistantActionKind.RECORD_FUND_BUY ||
            proposal.kind == cn.market.assistant.domain.assistant.AssistantActionKind.RECORD_FUND_SELL
        ) Text("这只会写入本机账本，不会向基金公司或券商提交订单。", color = Muted, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(if (busy) "正在保存…" else "确认并保存", color = Blue, fontSize = 11.sp)
            }
            TextButton(onClick = onCancel, enabled = !busy) { Text("取消", color = Muted, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun AssistantStepRow(step: AssistantToolStepUi) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(6.dp).clip(CircleShape).background(
                when (step.status) {
                    AssistantStepStatus.RUNNING -> Blue
                    AssistantStepStatus.DONE -> AssistantDown
                    AssistantStepStatus.FAILED -> AssistantUp
                }
            )
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(step.toolId, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            if (step.argumentSummary.isNotBlank()) {
                Text(step.argumentSummary.take(120), color = Muted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
                    when {
                        step.status == AssistantStepStatus.RUNNING -> "运行中"
                        step.status == AssistantStepStatus.FAILED -> "失败"
                        step.toolId == "portfolio.propose_change" -> "等待确认"
                        step.factCount == 0 -> "完成"
                        else -> "${step.factCount} 条事实"
                    } + (step.durationMillis?.let { " · ${it}ms" } ?: ""),
            color = Muted, fontSize = 9.sp
        )
    }
}

@Composable
private fun AssistantAttachmentRail(
    attachments: List<AssistantAttachmentUi>,
    onRemove: (Int) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        attachments.forEachIndexed { index, attachment ->
            Row(
                Modifier
                    .background(White, RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (attachment.isImage) {
                    AssistantAttachmentThumbnail(attachment, Modifier.size(width = 54.dp, height = 44.dp))
                    Spacer(Modifier.width(8.dp))
                } else {
                    Text(attachment.extension.ifBlank { "FILE" }, color = Blue, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(attachment.fileName.take(24), color = Ink, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(attachment.sizeLabel, color = Muted, fontSize = 8.sp)
                }
                TextButton(onClick = { onRemove(index) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("×", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun AssistantAttachmentChip(attachment: AssistantAttachmentUi) {
    if (attachment.isImage) {
        AssistantAttachmentThumbnail(attachment, Modifier.size(width = 128.dp, height = 96.dp))
    } else {
        Surface(color = SoftBlue, shape = RoundedCornerShape(12.dp)) {
            Text("${attachment.extension.ifBlank { "文件" }} · ${attachment.fileName.take(18)}", color = Ink, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun AssistantAttachmentThumbnail(attachment: AssistantAttachmentUi, modifier: Modifier = Modifier) {
    val bitmap = attachment.thumbnail
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = attachment.fileName,
            modifier = modifier.clip(RoundedCornerShape(10.dp))
        )
    } else {
        Box(modifier.background(SoftBlue, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Text("图片", color = Blue, fontSize = 10.sp)
        }
    }
}

@Composable
private fun AssistantComposer(
    draft: String,
    busy: Boolean,
    locked: Boolean,
    modelLabel: String,
    live: Boolean,
    imageInputAvailable: Boolean,
    attachmentCount: Int,
    onDraftChange: (String) -> Unit,
    attachmentMenuOpen: Boolean,
    onAttachmentMenuChange: (Boolean) -> Unit,
    onSelectFile: () -> Unit,
    onSelectImage: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenModel: () -> Unit
) {
    val palette = MarketTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)
            .border(1.dp, palette.line, RoundedCornerShape(25.dp))
            .background(palette.surface, RoundedCornerShape(25.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { onDraftChange(it.take(2_000)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 132.dp),
            enabled = !locked,
            placeholder = { Text("向助手提问", fontSize = 15.sp, color = Muted) },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
            shape = RoundedCornerShape(18.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent
            )
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(
                    onClick = { onAttachmentMenuChange(true) },
                    enabled = !busy && !locked,
                    modifier = Modifier.size(42.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("＋", color = Ink, fontSize = 24.sp)
                }
                DropdownMenu(expanded = attachmentMenuOpen, onDismissRequest = { onAttachmentMenuChange(false) }) {
                    if (imageInputAvailable) {
                        DropdownMenuItem(
                            text = { Text("添加图片", fontSize = 13.sp) },
                            onClick = { onAttachmentMenuChange(false); onSelectImage() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("添加文本 / CSV / JSON", fontSize = 13.sp) },
                        onClick = { onAttachmentMenuChange(false); onSelectFile() }
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        locked -> "先确认或取消上面的本机更改"
                        !live -> "本机离线模式 · 连接模型后可继续对话"
                        imageInputAvailable -> "$modelLabel · 支持图片和文本"
                        else -> "$modelLabel · 仅支持文本"
                    },
                    color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (attachmentCount > 0) Text("已添加 $attachmentCount 个附件", color = Blue, fontSize = 9.sp)
            }
            if (busy) {
                TextButton(onClick = onStop, modifier = Modifier.size(42.dp), contentPadding = PaddingValues(0.dp)) {
                    Text("■", color = AssistantUp, fontSize = 17.sp)
                }
            } else {
                TextButton(
                    onClick = onSend,
                    enabled = !locked && (draft.isNotBlank() || attachmentCount > 0),
                    modifier = Modifier.size(42.dp).clip(CircleShape).background(if (draft.isNotBlank() || attachmentCount > 0) Ink else palette.surfaceAlt),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("↑", color = if (draft.isNotBlank() || attachmentCount > 0) palette.surface else Muted, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = onOpenModel, contentPadding = PaddingValues(0.dp)) {
                Text(
                    when {
                        !live -> "离线时只在本机读取文本附件；图片需连接视觉模型 · 不构成投资建议"
                        imageInputAvailable -> "消息与图片会发送到当前模型；持仓明细按设置控制 · 不构成投资建议"
                        else -> "消息会发送到当前模型；持仓明细按设置控制 · 不构成投资建议"
                    },
                    color = Muted, fontSize = 8.sp, maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun UsageBar(usage: ChatUsage?) {
    val palette = MarketTheme.colors
    val current = usage ?: return
    val total = current.totalTokens
    if (total <= 0) return
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LinearProgressIndicator(
            progress = { (total / 8_000f).coerceIn(0.02f, 1f) },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = Blue,
            trackColor = palette.line
        )
        Text("本轮 token：输入 ${current.promptTokens} · 输出 ${current.completionTokens}", color = Muted, fontSize = 8.sp)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Market 助手回答", text))
}

/** Reads a picked document into an attachment: bounded text, or a bounded base64 image. */
private fun readAttachment(context: Context, uri: Uri, allowImage: Boolean): AssistantAttachmentUi? {
    val resolver = context.contentResolver
    var name = "附件"
    var size = 0L
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
            if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
        }
    }
    val mimeType = resolver.getType(uri) ?: "application/octet-stream"
    if (mimeType.startsWith("image/")) {
        if (!allowImage) return null
        if (size > MAX_IMAGE_ATTACHMENT_BYTES) return null
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_IMAGE_ATTACHMENT_BYTES) return null
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: return null
        return AssistantAttachmentUi(
            fileName = name,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            base64Image = Base64.encodeToString(bytes, Base64.NO_WRAP),
            localUri = uri.toString(),
            thumbnail = decodeImageThumbnail(bytes)
        )
    }
    if (size > MAX_TEXT_ATTACHMENT_BYTES) return null
    val text = resolver.openInputStream(uri)?.use { input ->
        val bytes = input.readNBytes(MAX_TEXT_ATTACHMENT_BYTES + 1)
        if (bytes.size > MAX_TEXT_ATTACHMENT_BYTES) return null
        String(bytes, Charsets.UTF_8)
    } ?: return null
    if (text.isBlank()) return null
    return AssistantAttachmentUi(
        fileName = name,
        mimeType = mimeType,
        sizeBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
        text = text
    )
}

private fun decodeImageThumbnail(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 720 || bounds.outHeight / sample > 540) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }
    )
}
