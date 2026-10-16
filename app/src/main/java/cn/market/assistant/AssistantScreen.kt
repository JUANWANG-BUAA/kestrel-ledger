package cn.market.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.market.assistant.domain.assistant.AssistantSkill
import cn.market.assistant.domain.assistant.ChatUsage
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
    val geometry = MarketTheme.geometry
    val palette = MarketTheme.colors
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf(emptyList<AssistantAttachmentUi>()) }
    var sheet by remember { mutableStateOf<AssistantSheet?>(null) }
    var editingSkill by remember { mutableStateOf<AssistantSkill?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var attachmentError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val attachment = withContext(Dispatchers.IO) { readAttachment(context, uri) }
            if (attachment == null) {
                attachmentError = "无法读取该文件（超过大小限制或格式不支持）"
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

    Column(modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        AssistantHeader(
            state = state,
            menuOpen = menuOpen,
            onMenuOpenChange = { menuOpen = it },
            onBack = onBack,
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
            contentPadding = PaddingValues(bottom = MarketNavBarClearance),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.turns.isEmpty()) {
                item { AssistantOnboarding(state = state, onOpenSheet = { sheet = it }, onUseSkill = { skill -> draft = "/${skill.id} " }) }
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
            modelLabel = state.modelLabel,
            live = state.isLive,
            attachmentCount = attachments.size,
            onDraftChange = { draft = it },
            onAttach = { picker.launch(arrayOf("text/*", "application/json", "text/csv", "image/*")) },
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
        onEditSkill = { skill -> editingSkill = skill; sheet = AssistantSheet.SKILL_EDITOR }
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
    onRename: () -> Unit,
    onOpenSheet: (AssistantSheet) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("‹ 总览", color = Blue) }
        Column(Modifier.weight(1f).clickable { onRename() }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                state.currentWorkflow?.title ?: "AI 助手",
                color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "本机账本 + 公开数据 · 不回传持仓 · 不做投资建议",
                color = Muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onOpenSheet(AssistantSheet.SETTINGS) }.padding(horizontal = 4.dp)
        ) {
            Box(
                Modifier.size(7.dp).clip(CircleShape)
                    .background(if (state.isLive) AssistantDown else Muted)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                if (state.isLive) state.settings.model else "离线模式",
                color = Ink, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Box {
            TextButton(onClick = { onMenuOpenChange(true) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("⋮", color = Ink, fontSize = 16.sp)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                DropdownMenuItem(text = { Text("新建会话", fontSize = 12.sp) }, onClick = {
                    onMenuOpenChange(false); state.newWorkflow()
                })
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
            }
        }
    }
}

@Composable
private fun AssistantOnboarding(
    state: AssistantFeatureState,
    onOpenSheet: (AssistantSheet) -> Unit,
    onUseSkill: (AssistantSkill) -> Unit
) {
    val geometry = MarketTheme.geometry
    val palette = MarketTheme.colors
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(geometry.panel),
        colors = CardDefaults.cardColors(containerColor = palette.accentSoft)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("可以做这些事", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            listOf(
                "读本机账本：持仓、现金、收益、快照与报告事实",
                "查公开行情：指数、板块成分、黄金与基金估值",
                "分析附件：把流水 CSV、交割单文本或行情截图发给模型",
                "复用技能：把常用问法固化成 /技能，自动带上指令与工具范围",
                "保留工作流：每轮问答落盘，可恢复、可导出 Markdown"
            ).forEach { Text("· $it", color = Ink, fontSize = 11.5.sp, lineHeight = 16.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { onOpenSheet(AssistantSheet.SETTINGS) }) { Text("接入模型", color = Blue, fontSize = 11.sp) }
                TextButton(onClick = { onOpenSheet(AssistantSheet.SKILLS) }) { Text("管理技能", color = Blue, fontSize = 11.sp) }
            }
            if (state.skills.isNotEmpty()) {
                Text("直接点一个技能开始：", color = Muted, fontSize = 10.sp)
                state.skills.filter { it.enabled }.take(4).forEach { skill ->
                    Text(
                        "· ${skill.name}：${skill.description}",
                        color = Blue, fontSize = 11.sp, modifier = Modifier.clickable { onUseSkill(skill) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantTurnCard(turn: AssistantTurnUi, state: AssistantFeatureState) {
    val context = LocalContext.current
    val geometry = MarketTheme.geometry
    val palette = MarketTheme.colors
    var processExpanded by remember(turn.id) { mutableStateOf(false) }
    var citationsExpanded by remember(turn.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    turn.question,
                    color = palette.paper, fontSize = 13.sp, lineHeight = 19.sp,
                    modifier = Modifier
                        .background(palette.ink, RoundedCornerShape(topStart = geometry.card, topEnd = geometry.xs, bottomStart = geometry.card, bottomEnd = geometry.card))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                turn.attachments.takeIf { it.isNotEmpty() }?.let { attachmentList ->
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        attachmentList.forEach { attachment -> AssistantAttachmentChip(attachment) }
                    }
                }
            }
        }
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = palette.surface),
            border = BorderStroke(1.dp, palette.line)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(7.dp).clip(CircleShape).background(
                            when (turn.status) {
                                AssistantTurnStatus.RUNNING -> Blue
                                AssistantTurnStatus.DONE -> AssistantDown
                                AssistantTurnStatus.FAILED -> AssistantUp
                            }
                        )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (turn.status) {
                            AssistantTurnStatus.RUNNING -> if (turn.steps.any { it.status == AssistantStepStatus.RUNNING }) "调用工具中" else "生成中"
                            AssistantTurnStatus.DONE -> if (turn.offlineFallback) "离线事实回答" else "完成"
                            AssistantTurnStatus.FAILED -> "失败"
                        },
                        color = Ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                    )
                    turn.skillName?.let { skill ->
                        Spacer(Modifier.width(6.dp))
                        Text("技能 $skill", color = Blue, fontSize = 9.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    turn.modelLabel?.let { label -> Text(label, color = Muted, fontSize = 9.sp) }
                }
                if (turn.steps.isNotEmpty()) {
                    TextButton(
                        onClick = { processExpanded = !processExpanded },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                    ) {
                        Text(
                            (if (processExpanded) "收起过程 ⌃" else "过程 · ${turn.steps.size} 步 ⌄"),
                            color = Blue, fontSize = 10.sp
                        )
                    }
                    AnimatedVisibility(
                        visible = processExpanded,
                        enter = fadeIn(tween(120)) + expandVertically(tween(180)),
                        exit = fadeOut(tween(100)) + shrinkVertically(tween(150))
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            turn.steps.forEach { step -> AssistantStepRow(step) }
                        }
                    }
                }
                if (turn.answer.isNotBlank()) {
                    MarkdownLite(turn.answer)
                }
                if (turn.citations.isNotEmpty()) {
                    Text(
                        if (citationsExpanded) "收起引用 ⌃" else "引用 ${turn.citations.size} 条 ⌄",
                        color = Blue, fontSize = 10.sp,
                        modifier = Modifier.clickable { citationsExpanded = !citationsExpanded }
                    )
                    if (citationsExpanded) {
                        turn.citations.forEach { citation ->
                            Text(
                                "· ${citation.factKey} · ${citation.source ?: "—"} · ${citation.observedAt ?: "—"}",
                                color = Muted, fontSize = 9.sp, lineHeight = 13.sp
                            )
                        }
                    }
                }
                turn.errorMessage?.let { Text(it, color = AssistantUp, fontSize = 10.sp, lineHeight = 14.sp) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (turn.answer.isNotBlank()) {
                        TextButton(onClick = { copyToClipboard(context, turn.answer) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text("复制", color = Muted, fontSize = 10.sp)
                        }
                    }
                    if (turn.status != AssistantTurnStatus.RUNNING) {
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
            when (step.status) {
                AssistantStepStatus.RUNNING -> "运行中"
                AssistantStepStatus.DONE -> "${step.factCount} 条事实" + (step.durationMillis?.let { " · ${it}ms" } ?: "")
                AssistantStepStatus.FAILED -> "失败"
            },
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
                Text(
                    if (attachment.isImage) "IMG" else attachment.extension.ifBlank { "FILE" },
                    color = Blue, fontSize = 9.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(6.dp))
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
    Row(
        Modifier.background(SoftBlue, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (attachment.isImage) "IMG" else attachment.extension.ifBlank { "FILE" },
            color = Blue, fontSize = 8.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(4.dp))
        Text(attachment.fileName.take(18), color = Ink, fontSize = 9.sp)
    }
}

@Composable
private fun AssistantComposer(
    draft: String,
    busy: Boolean,
    modelLabel: String,
    live: Boolean,
    attachmentCount: Int,
    onDraftChange: (String) -> Unit,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenModel: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            TextButton(onClick = onAttach, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("＋文件", color = Blue, fontSize = 11.sp)
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { onDraftChange(it.take(2_000)) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 140.dp),
                placeholder = { Text("问持仓、周报、估值、板块…也可以直接发文件", fontSize = 11.sp, color = Muted) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp)
            )
            Spacer(Modifier.width(6.dp))
            if (busy) {
                TextButton(onClick = onStop, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text("停止", color = AssistantUp, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                TextButton(onClick = onSend, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text("发送", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clickable { onOpenModel() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (live) modelLabel else "离线 · 点此接入模型", color = Muted, fontSize = 9.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            if (attachmentCount > 0) Text("$attachmentCount 个附件 · ", color = Muted, fontSize = 9.sp)
            Text("估算非官方 · 不构成投资建议", color = Muted, fontSize = 9.sp)
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
private fun readAttachment(context: Context, uri: Uri): AssistantAttachmentUi? {
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
            localUri = uri.toString()
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
