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
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
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
import androidx.compose.runtime.mutableIntStateOf
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
                items(state.turns, key = { it.id }) { turn ->
                    AssistantTurnCard(
                        turn,
                        state,
                        onOpenMarket,
                        Modifier.animateItem(
                            fadeInSpec = tween(180),
                            placementSpec = spring(dampingRatio = 0.84f, stiffness = 520f),
                            fadeOutSpec = tween(100)
                        )
                    )
                }
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
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    AnimatedVisibility(
        visible = entered,
        enter = fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 18 }
    ) {
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
}

@Composable
internal fun AssistantIconButton(icon: String, label: String, onClick: () -> Unit) {
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


private fun LazyListLayoutInfo.isAtBottom(): Boolean {
    if (totalItemsCount == 0) return true
    val lastVisible = visibleItemsInfo.lastOrNull() ?: return false
    return lastVisible.index == totalItemsCount - 1 &&
        lastVisible.offset + lastVisible.size <= viewportEndOffset + 16
}

internal fun copyToClipboard(context: Context, text: String) {
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
        readUtf8Limited(input, MAX_TEXT_ATTACHMENT_BYTES)
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
