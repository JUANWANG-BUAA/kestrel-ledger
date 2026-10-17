package cn.market.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.foundation.lazy.LazyListLayoutInfo
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.domain.assistant.AssistantSkill
import cn.market.assistant.domain.assistant.ChatUsage
import cn.market.assistant.domain.assistant.AssistantWorkflow
import cn.market.assistant.domain.assistant.WorkflowExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val MAX_TEXT_ATTACHMENT_BYTES = 256 * 1_024
private const val MAX_IMAGE_ATTACHMENT_BYTES = 3 * 1_024 * 1_024

private data class AssistantQuickAction(
    val icon: String,
    val title: String,
    val detail: String,
    val prompt: String
)


/**
 * dsh-style assistant surface: a session timeline with folded tool processes, a draft-attachment rail
 * above the composer, a model seat, and panels for models, skills, workflows and habit memory.
 */
@Composable
internal fun AssistantScreen(
    state: AssistantFeatureState,
    onBack: () -> Unit,
    onOpenMarket: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val inputFocusRequester = remember { FocusRequester() }
    var followLatest by remember { mutableStateOf(true) }
    var programmaticScroll by remember { mutableStateOf(false) }
    var lastScrolledTurnCount by remember { mutableStateOf(state.turns.size) }
    var draft by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf(emptyList<AssistantAttachmentUi>()) }
    var sheet by remember { mutableStateOf<AssistantSheet?>(null) }
    var editingSkill by remember { mutableStateOf<AssistantSkill?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var attachmentMenuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var attachmentError by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun usePrompt(prompt: String) {
        draft = prompt
        scope.launch {
            delay(80)
            inputFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

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

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.layoutInfo.isAtBottom() }
            .collect { (scrolling, atBottom) ->
                if (scrolling && !programmaticScroll) followLatest = atBottom
            }
    }
    LaunchedEffect(
        state.turns.size,
        state.turns.lastOrNull()?.answer?.length,
        state.turns.lastOrNull()?.steps?.size,
        followLatest
    ) {
        if (state.turns.isNotEmpty() && followLatest) {
            programmaticScroll = true
            try {
                if (lastScrolledTurnCount != state.turns.size) {
                    listState.animateScrollToItem(state.turns.size)
                } else {
                    listState.scrollToItem(state.turns.size)
                }
            } finally {
                programmaticScroll = false
            }
        }
        lastScrolledTurnCount = state.turns.size
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
            onCopyCurrentWorkflow = {
                state.workflowMarkdown()?.let { markdown ->
                    copyToClipboard(context, markdown)
                    Toast.makeText(context, "已复制完整对话", Toast.LENGTH_SHORT).show()
                }
            },
            onArchiveCurrentWorkflow = { state.currentWorkflow?.let { state.archiveWorkflow(it.id) } },
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
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.turns.isEmpty()) {
                    item { AssistantOnboarding(state = state, onOpenSheet = { sheet = it }, onUsePrompt = ::usePrompt) }
                }
                items(state.turns, key = { it.id }) { turn -> AssistantTurnCard(turn, state, onOpenMarket) }
                item { Spacer(Modifier.height(4.dp)) }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedVisibility(
                    visible = state.turns.isNotEmpty() && !followLatest,
                    enter = fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.92f),
                    exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.92f)
                ) {
                    Surface(
                        modifier = Modifier.clickable {
                            followLatest = true
                            scope.launch {
                                programmaticScroll = true
                                try {
                                    listState.animateScrollToItem(state.turns.size)
                                } finally {
                                    programmaticScroll = false
                                }
                            }
                        },
                        color = Ink,
                        shape = CircleShape,
                        shadowElevation = 5.dp
                    ) {
                        Text("↓  最新消息", color = White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                    }
                }
            }
        }
        attachmentError?.let { Text(it, color = AssistantDown, fontSize = 10.sp) }
        if (attachments.isNotEmpty()) {
            AssistantAttachmentRail(attachments, onRemove = { index -> attachments = attachments.filterIndexed { i, _ -> i != index } })
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
            inputFocusRequester = inputFocusRequester,
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
        UsageBar(
            state.turns.lastOrNull()?.usage,
            state.modelCapabilities?.contextWindow,
            state.modelCapabilities?.maxInputTokens
        )
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
        onCopyWorkflow = { workflow ->
            copyToClipboard(context, WorkflowExport.toMarkdown(workflow, System.currentTimeMillis()))
            Toast.makeText(context, "已复制完整对话", Toast.LENGTH_SHORT).show()
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
    onCopyCurrentWorkflow: () -> Unit,
    onArchiveCurrentWorkflow: () -> Unit,
    onOpenSheet: (AssistantSheet) -> Unit
) {
    val imageMode = state.isLive && state.settings.imageInputEnabled
    val palette = MarketTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        AssistantIconButton("menu", "会话历史") { onOpenSheet(AssistantSheet.WORKFLOWS) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onRename() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    state.currentWorkflow?.title ?: "AI 助手",
                    color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                MarketIcon("edit", size = 13.dp, tint = Muted)
            }
            Surface(
                modifier = Modifier.clickable { onOpenSheet(AssistantSheet.SETTINGS) },
                color = palette.surfaceAlt,
                shape = CircleShape
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (state.isLive) AssistantDown else Muted))
                    Text(
                        if (state.isLive) state.settings.model else "连接模型",
                        color = if (state.isLive) Ink else Blue,
                        fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (imageMode) MarketIcon("image", size = 12.dp, tint = Blue)
                }
            }
        }
        AssistantIconButton("edit", "新建会话", onNewWorkflow)
        Box {
            AssistantIconButton("more", "助手菜单") { onMenuOpenChange(true) }
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
                if (state.currentWorkflow != null) {
                    DropdownMenuItem(text = { Text("复制当前对话", fontSize = 12.sp) }, onClick = {
                        onMenuOpenChange(false); onCopyCurrentWorkflow()
                    })
                    DropdownMenuItem(text = { Text("归档当前会话", fontSize = 12.sp) }, onClick = {
                        onMenuOpenChange(false); onArchiveCurrentWorkflow()
                    })
                }
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
    val actions = listOf(
        AssistantQuickAction("assets", "分析持仓", "结构 · 收益 · 风险", "分析我的持仓结构"),
        AssistantQuickAction("search", "查找基金", "代码 · 名称 · 拼音", "帮我搜索基金："),
        AssistantQuickAction("coin", "黄金行情", "报价 · 来源 · 分时", "总结今天黄金行情的变化，并标注来源和时点"),
        AssistantQuickAction("spark", "记住方法", "保存我的分析偏好", "记住我的分析方法：")
    )
    val palette = MarketTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(Modifier.size(42.dp), color = SoftBlue, shape = RoundedCornerShape(14.dp)) {
                Box(contentAlignment = Alignment.Center) { MarketIcon("spark", tint = Blue, size = 22.dp) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("今天要分析什么？", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.isLive) "行情、基金目录与本机持仓可一起分析" else "本机数据可用 · 连接模型解锁完整对话",
                    color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions.chunked(2).forEach { rowActions ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                rowActions.forEach { action ->
                    Surface(
                        modifier = Modifier.weight(1f).heightIn(min = 88.dp).clickable { onUsePrompt(action.prompt) },
                        color = palette.surface,
                        shape = RoundedCornerShape(17.dp),
                        border = BorderStroke(1.dp, palette.line)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(11.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(Modifier.size(28.dp), color = SoftBlue, shape = CircleShape) {
                                    Box(contentAlignment = Alignment.Center) { MarketIcon(action.icon, tint = Blue, size = 15.dp) }
                                }
                                Spacer(Modifier.weight(1f))
                                MarketIcon("forward", tint = Muted, size = 13.dp)
                            }
                            Text(action.title, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(action.detail, color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (state.skills.any { it.enabled }) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.skills.filter { it.enabled }.take(4).forEach { skill ->
                    Surface(
                        modifier = Modifier.clickable { onUsePrompt("/${skill.id} ") },
                        color = palette.surfaceAlt,
                        shape = CircleShape
                    ) {
                        Text("✳ ${skill.name}", color = Ink, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { onOpenSheet(AssistantSheet.SETTINGS) }, contentPadding = PaddingValues(horizontal = 2.dp)) {
                Text("回复偏好", color = Blue, fontSize = 10.sp)
            }
            Text("·", color = Muted, fontSize = 10.sp)
            TextButton(onClick = { onOpenSheet(AssistantSheet.HABITS) }, contentPadding = PaddingValues(horizontal = 2.dp)) {
                Text(if (state.habits.isEmpty) "记忆与习惯" else "记忆 ${state.habits.totalEvents} 条", color = Blue, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun AssistantIconButton(icon: String, label: String, onClick: () -> Unit) {
    val palette = MarketTheme.colors
    Surface(
        modifier = Modifier.size(40.dp).clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button },
        color = Color.Transparent,
        shape = CircleShape
    ) {
        Box(contentAlignment = Alignment.Center) { MarketIcon(icon, tint = palette.ink, size = 20.dp) }
    }
}

@Composable
private fun AssistantTurnCard(turn: AssistantTurnUi, state: AssistantFeatureState, onOpenMarket: (String) -> Unit) {
    val context = LocalContext.current
    val palette = MarketTheme.colors
    var processExpanded by remember(turn.id) { mutableStateOf(false) }
    var citationsExpanded by remember(turn.id) { mutableStateOf(false) }
    var copied by remember(turn.id) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_300)
            copied = false
        }
    }

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
                AssistantStatusDot(turn.status)
                AnimatedContent(
                    targetState = turn.status to turn.steps.any { it.status == AssistantStepStatus.RUNNING },
                    transitionSpec = { fadeIn(tween(100)) togetherWith fadeOut(tween(70)) },
                    label = "assistant_turn_status"
                ) { (status, readingData) -> Text(
                    when (status) {
                        AssistantTurnStatus.RUNNING -> if (readingData) "正在读取数据" else "正在生成"
                        AssistantTurnStatus.DONE -> if (turn.offlineFallback) "本机事实模式" else "助手"
                        AssistantTurnStatus.FAILED -> "未完成"
                    },
                    color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium
                ) }
                turn.skillName?.let { Text("· $it", color = Blue, fontSize = 10.sp) }
                Spacer(Modifier.weight(1f))
                turn.modelLabel?.takeIf { turn.status == AssistantTurnStatus.RUNNING }?.let {
                    Text(it, color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            AnimatedVisibility(
                visible = turn.answer.isNotBlank(),
                enter = fadeIn(tween(150)) + expandVertically(tween(180)),
                exit = fadeOut(tween(90)) + shrinkVertically(tween(120))
            ) {
                MarkdownLite(turn.answer, onOpenMarket = onOpenMarket)
            }
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
                    val marketId = when {
                        citation.factKey == "market.quote.instrument_id" -> citation.value
                        citation.factKey.startsWith("market.search.candidate") -> Regex("\\(([^)]+)\\)")
                            .find(citation.value.orEmpty())?.groupValues?.getOrNull(1)
                        else -> null
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "· ${citationLabel(citation.factKey)} · ${citation.value.orEmpty()} · ${citation.source ?: "—"} · ${citation.observedAt ?: "—"}",
                            color = Muted, fontSize = 9.sp, modifier = Modifier.weight(1f)
                        )
                        marketId?.let { id ->
                            Text(
                                "打开行情",
                                color = Blue,
                                fontSize = 9.sp,
                                modifier = Modifier.clickable { onOpenMarket(id) }
                            )
                        }
                    }
                }
            }
            turn.errorMessage?.let { Text(it, color = AssistantUp, fontSize = 11.sp, lineHeight = 15.sp) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (turn.answer.isNotBlank()) {
                    TextButton(
                        onClick = {
                            copyToClipboard(context, turn.answer)
                            copied = true
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        AnimatedContent(copied, label = "copy_feedback") { isCopied ->
                            Text(if (isCopied) "已复制 ✓" else "复制", color = if (isCopied) Blue else Muted, fontSize = 10.sp)
                        }
                    }
                }
                if (turn.status != AssistantTurnStatus.RUNNING && turn.pendingAction == null) {
                    TextButton(
                        onClick = { state.retry(turn.id) },
                        enabled = !state.busy,
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text(if (turn.status == AssistantTurnStatus.DONE) "重新生成" else "重试", color = Muted, fontSize = 10.sp)
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
    inputFocusRequester: FocusRequester,
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
        Modifier.fillMaxWidth().animateContentSize(tween(180)).padding(top = 4.dp, bottom = 6.dp)
            .border(1.dp, palette.line, RoundedCornerShape(25.dp))
            .background(palette.surface, RoundedCornerShape(25.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { onDraftChange(it.take(2_000)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 132.dp).focusRequester(inputFocusRequester),
            enabled = !locked,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (!locked && (draft.isNotBlank() || attachmentCount > 0)) onSend() }),
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
                    MarketIcon("plus", tint = Ink, size = 21.dp)
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
                        locked -> "待确认本机更改"
                        busy -> "正在生成 · 可继续输入"
                        !live -> "离线模式"
                        imageInputAvailable -> "$modelLabel · 图片已启用"
                        else -> "$modelLabel · 文本"
                    },
                    color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (attachmentCount > 0) Text("已添加 $attachmentCount 个附件", color = Blue, fontSize = 9.sp)
            }
            val canSend = !locked && (draft.isNotBlank() || attachmentCount > 0)
            val buttonColor by animateColorAsState(
                targetValue = when {
                    busy -> AssistantUp
                    canSend -> Ink
                    else -> palette.surfaceAlt
                },
                animationSpec = tween(160),
                label = "assistant_send_button_color"
            )
            TextButton(
                onClick = { if (busy) onStop() else onSend() },
                enabled = busy || canSend,
                modifier = Modifier.size(42.dp).clip(CircleShape).background(buttonColor),
                contentPadding = PaddingValues(0.dp)
            ) {
                AnimatedContent(
                    targetState = busy,
                    transitionSpec = {
                        (fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.78f)) togetherWith
                            (fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.78f))
                    },
                    label = "assistant_send_stop_icon"
                ) { isBusy ->
                    MarketIcon(if (isBusy) "stop" else "send", tint = if (isBusy || canSend) palette.surface else Muted, size = 19.dp)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenModel).padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when {
                    !live -> "离线 · 数据与隐私设置"
                    imageInputAvailable -> "图片与文本发送至当前模型 · 数据权限"
                    else -> "发送至当前模型 · 数据权限"
                },
                color = Muted, fontSize = 9.sp, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("非投资建议", color = Muted, fontSize = 8.sp)
        }
    }
}

@Composable
private fun UsageBar(usage: ChatUsage?, contextWindow: Int?, maxInputTokens: Int?) {
    val current = usage ?: return
    val total = current.totalTokens
    if (total <= 0) return
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("本轮输入 ${current.promptTokens} · 输出 ${current.completionTokens} tokens", color = Muted, fontSize = 8.sp)
        current.reasoningTokens?.let { Text("思考 $it", color = Muted, fontSize = 8.sp) }
        Text(
            when {
                contextWindow != null -> "上下文 ${contextWindow.formatTokenCount()}"
                maxInputTokens != null -> "最大输入 ${maxInputTokens.formatTokenCount()}"
                else -> "上下文容量未提供"
            },
            color = Muted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

private fun citationLabel(key: String): String = when (key) {
    "market.quote.instrument_id" -> "行情标的"
    "market.quote.instrument_name" -> "标的名称"
    "market.quote.price" -> "现价"
    "market.quote.change" -> "涨跌额"
    "market.quote.change_pct" -> "涨跌幅"
    "market.quote.age_seconds" -> "数据延迟"
    "market.quote.fresh_within_72h" -> "时效检查"
    else -> when {
        key.startsWith("market.search.candidate") -> "行情目录候选"
        key.startsWith("fund.search") -> "基金目录"
        key.startsWith("portfolio") -> "本机持仓"
        key.startsWith("market") -> "行情数据"
        else -> key.substringAfterLast('.').replace('_', ' ')
    }
}

@Composable
private fun AssistantStatusDot(status: AssistantTurnStatus) {
    val running = status == AssistantTurnStatus.RUNNING
    val pulse = rememberInfiniteTransition(label = "assistant_status_pulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.42f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(720), repeatMode = RepeatMode.Reverse),
        label = "assistant_status_alpha"
    )
    val color = when (status) {
        AssistantTurnStatus.RUNNING -> Blue
        AssistantTurnStatus.DONE -> AssistantDown
        AssistantTurnStatus.FAILED -> AssistantUp
    }
    Box(Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = if (running) alpha else 1f)))
}

private fun LazyListLayoutInfo.isAtBottom(): Boolean {
    if (totalItemsCount == 0) return true
    val lastVisible = visibleItemsInfo.lastOrNull() ?: return false
    return lastVisible.index == totalItemsCount - 1 &&
        lastVisible.offset + lastVisible.size <= viewportEndOffset + 16
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
