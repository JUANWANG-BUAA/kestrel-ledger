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

@Composable
internal fun AssistantTurnCard(
    turn: AssistantTurnUi,
    state: AssistantFeatureState,
    onOpenMarket: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val palette = MarketTheme.colors
    var processExpanded by remember(turn.id) { mutableStateOf(false) }
    var citationsExpanded by remember(turn.id) { mutableStateOf(false) }
    var copied by remember(turn.id) { mutableStateOf(false) }
    // Flowing reveal for a freshly finished answer; reopening an old turn shows it instantly.
    val animateAnswer = remember(turn.id) {
        val finished = turn.finishedAtMillis ?: 0L
        (System.currentTimeMillis() - finished) in 0..2_500
    }
    var revealed by remember(turn.id) { mutableIntStateOf(if (animateAnswer) 0 else turn.answer.length) }
    LaunchedEffect(turn.id, turn.answer, animateAnswer) {
        if (!animateAnswer) {
            revealed = turn.answer.length
            return@LaunchedEffect
        }
        val total = turn.answer.length
        if (total == 0) return@LaunchedEffect
        val step = (total / 90).coerceAtLeast(1)
        revealed = 0
        while (revealed < total) {
            revealed = (revealed + step).coerceAtMost(total)
            delay(16)
        }
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_300)
            copied = false
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (turn.attachments.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.End) {
                    turn.attachments.forEach { attachment -> AssistantAttachmentChip(attachment) }
                }
            }
            Surface(
                modifier = Modifier.widthIn(max = 340.dp),
                color = palette.surfaceAlt,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)
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
            if (turn.status == AssistantTurnStatus.RUNNING && turn.answer.isBlank() &&
                turn.steps.none { it.status == AssistantStepStatus.RUNNING }
            ) {
                AssistantTypingDots(Modifier.padding(start = 2.dp, top = 2.dp, bottom = 2.dp))
            }
            val structuredFacts = turn.steps.asSequence()
                .flatMap { it.facts.asSequence() }
                .distinctBy { it.key to it.observedAt }
                .toList()
            if (structuredFacts.isNotEmpty()) {
                AssistantFactsPanel(structuredFacts)
            }
            AnimatedVisibility(
                visible = turn.answer.isNotBlank(),
                enter = fadeIn(tween(170)) + expandVertically(tween(220)),
                exit = fadeOut(tween(90)) + shrinkVertically(tween(120))
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().animateContentSize(tween(220)),
                    color = palette.surface,
                    shape = RoundedCornerShape(topStart = 5.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp),
                    border = BorderStroke(1.dp, palette.line),
                    shadowElevation = 1.dp
                ) {
                    MarkdownLite(
                        if (revealed >= turn.answer.length) turn.answer else turn.answer.take(revealed) + "▍",
                        onOpenMarket = onOpenMarket,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp)
                    )
                }
            }
            turn.charts.forEach { chart ->
                AssistantChartCard(chart)
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
internal fun AssistantActionPreviewCard(
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
internal fun AssistantStepRow(step: AssistantToolStepUi) {
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
internal fun AssistantAttachmentRail(
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
internal fun AssistantAttachmentChip(attachment: AssistantAttachmentUi) {
    if (attachment.isImage) {
        AssistantAttachmentThumbnail(attachment, Modifier.size(width = 128.dp, height = 96.dp))
    } else {
        Surface(color = SoftBlue, shape = RoundedCornerShape(12.dp)) {
            Text("${attachment.extension.ifBlank { "文件" }} · ${attachment.fileName.take(18)}", color = Ink, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
internal fun AssistantAttachmentThumbnail(attachment: AssistantAttachmentUi, modifier: Modifier = Modifier) {
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
internal fun AssistantComposer(
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
internal fun UsageBar(usage: ChatUsage?, contextWindow: Int?, maxInputTokens: Int?) {
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
internal fun AssistantStatusDot(status: AssistantTurnStatus) {
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
