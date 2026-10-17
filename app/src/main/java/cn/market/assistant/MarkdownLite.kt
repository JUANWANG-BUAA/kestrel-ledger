package cn.market.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val URL_TAG = "assistant-url"
private val NUMBERED = Regex("^(\\d+)[.、)]\\s+(.*)$")
private val TABLE_SEPARATOR = Regex(":?-{3,}:?")
private val MARKDOWN_LINK = Regex("\\[([^]]+)]\\((https?://[^\\s)]+|market://[^\\s)]+)\\)")

/** A small, predictable Markdown subset with real links, tables, code cards, and numeric charts. */
@Composable
internal fun MarkdownLite(
    text: String,
    modifier: Modifier = Modifier,
    bodyColor: androidx.compose.ui.graphics.Color = Ink,
    mutedColor: androidx.compose.ui.graphics.Color = Muted,
    onOpenMarket: (String) -> Unit = {}
) {
    val blocks = remember(text) { parseMarkdownLite(text) }
    SelectionContainer {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Heading -> MarkdownInlineText(
                        block.text, bodyColor, if (block.level == 1) 17.sp else 15.sp, 23.sp,
                        fontWeight = FontWeight.SemiBold, onOpenMarket = onOpenMarket
                    )
                    is MarkdownBlock.Bullet -> MarkdownInlineText(
                        "· ${block.text}", bodyColor, 13.5.sp, 21.sp, onOpenMarket = onOpenMarket
                    )
                    is MarkdownBlock.Numbered -> MarkdownInlineText(
                        "${block.index}. ${block.text}", bodyColor, 13.5.sp, 21.sp, onOpenMarket = onOpenMarket
                    )
                    is MarkdownBlock.Quote -> Surface(
                        color = MarketTheme.colors.surfaceAlt,
                        shape = RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)
                    ) {
                        MarkdownInlineText(
                            block.text, mutedColor, 13.sp, 19.sp,
                            Modifier.fillMaxWidth().padding(start = 10.dp, end = 9.dp, top = 6.dp, bottom = 6.dp),
                            onOpenMarket = onOpenMarket
                        )
                    }
                    is MarkdownBlock.Code -> CodeCard(block)
                    is MarkdownBlock.Table -> MarkdownTable(block, bodyColor, onOpenMarket)
                    is MarkdownBlock.Chart -> MarkdownChartCard(block)
                    is MarkdownBlock.Divider -> Text("————————", color = mutedColor, fontSize = 10.sp)
                    is MarkdownBlock.Paragraph -> MarkdownInlineText(
                        block.text, bodyColor, 14.sp, 22.sp, onOpenMarket = onOpenMarket
                    )
                }
            }
        }
    }
}

internal sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class Numbered(val index: Int, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val text: String, val language: String? = null) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data class Chart(val title: String, val unit: String, val points: List<ChartPoint>) : MarkdownBlock
    data object Divider : MarkdownBlock
}

internal data class ChartPoint(val label: String, val value: Double)

internal fun parseMarkdownLite(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()
    val code = StringBuilder()
    val lines = text.replace("\r\n", "\n").lines()
    var inCode = false
    var codeLanguage: String? = null
    var index = 0

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += MarkdownBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }

    fun flushCode() {
        val raw = code.toString().trimEnd()
        if (codeLanguage.equals("chart", ignoreCase = true)) {
            blocks += parseChart(raw) ?: MarkdownBlock.Code(raw, codeLanguage)
        } else {
            blocks += MarkdownBlock.Code(raw, codeLanguage)
        }
        code.clear()
        codeLanguage = null
    }

    while (index < lines.size) {
        val rawLine = lines[index]
        val line = rawLine.trimEnd()
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            if (inCode) {
                flushCode()
                inCode = false
            } else {
                flushParagraph()
                codeLanguage = trimmed.removePrefix("```").trim().substringBefore(' ').takeIf { it.isNotBlank() }
                inCode = true
            }
            index++
            continue
        }
        if (inCode) {
            code.appendLine(line)
            index++
            continue
        }
        val next = lines.getOrNull(index + 1)?.trimEnd()
        if (isPipeRow(line) && next != null && isTableSeparator(next)) {
            flushParagraph()
            val headers = tableCells(line)
            index += 2
            val rows = mutableListOf<List<String>>()
            while (index < lines.size && isPipeRow(lines[index].trimEnd())) {
                rows += tableCells(lines[index].trimEnd())
                index++
            }
            if (headers.isNotEmpty()) blocks += MarkdownBlock.Table(headers, rows)
            continue
        }
        when {
            line.isBlank() -> flushParagraph()
            trimmed.startsWith("---") || trimmed.startsWith("***") -> {
                flushParagraph()
                blocks += MarkdownBlock.Divider
            }
            trimmed.startsWith("### ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Heading(3, trimmed.removePrefix("### "))
            }
            trimmed.startsWith("## ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Heading(2, trimmed.removePrefix("## "))
            }
            trimmed.startsWith("# ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Heading(1, trimmed.removePrefix("# "))
            }
            trimmed.startsWith("> ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Quote(trimmed.removePrefix("> "))
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Bullet(trimmed.substring(2))
            }
            NUMBERED.matches(trimmed) -> {
                flushParagraph()
                val match = NUMBERED.find(trimmed)!!
                blocks += MarkdownBlock.Numbered(match.groupValues[1].toIntOrNull() ?: 0, match.groupValues[2])
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
            }
        }
        index++
    }
    if (inCode && code.isNotEmpty()) flushCode()
    flushParagraph()
    return blocks
}

private fun isPipeRow(line: String): Boolean = line.trim().startsWith('|') && line.contains('|', ignoreCase = false)

private fun isTableSeparator(line: String): Boolean = isPipeRow(line) &&
    tableCells(line).isNotEmpty() && tableCells(line).all { TABLE_SEPARATOR.matches(it.trim()) }

private fun tableCells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|")
    .split('|').map { it.trim() }

private fun parseChart(text: String): MarkdownBlock.Chart? {
    val lines = text.lines().map(String::trim).filter(String::isNotBlank)
    val title = lines.firstOrNull { it.startsWith("title=") }?.substringAfter('=').orEmpty().ifBlank { "数据变化" }
    val unit = lines.firstOrNull { it.startsWith("unit=") }?.substringAfter('=').orEmpty()
    val points = lines.mapNotNull { line ->
        if (line.startsWith("title=") || line.startsWith("unit=")) return@mapNotNull null
        val separator = line.lastIndexOf(',').takeIf { it > 0 } ?: return@mapNotNull null
        val value = line.substring(separator + 1).trim().toDoubleOrNull()?.takeIf(Double::isFinite) ?: return@mapNotNull null
        ChartPoint(line.substring(0, separator).trim(), value)
    }
    return if (points.size >= 2) MarkdownBlock.Chart(title, unit, points.take(80)) else null
}

@Composable
private fun MarkdownInlineText(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    onOpenMarket: (String) -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val accentColor = MarketTheme.colors.accent
    val annotated = remember(text, color, accentColor) { inline(text, color, accentColor) }
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = fontSize, lineHeight = lineHeight, fontWeight = fontWeight),
        onClick = { offset ->
            annotated.getStringAnnotations(URL_TAG, offset, offset).firstOrNull()?.item?.let { target ->
                if (target.startsWith("market://")) {
                    target.removePrefix("market://").takeIf { Regex("^[A-Za-z0-9._-]{1,80}$").matches(it) }
                        ?.let(onOpenMarket)
                } else if (target.startsWith("https://") || target.startsWith("http://")) {
                    runCatching { uriHandler.openUri(target) }
                }
            }
        }
    )
}

@Composable
private fun CodeCard(block: MarkdownBlock.Code) {
    val context = LocalContext.current
    Surface(color = MarketTheme.colors.surfaceAlt, shape = RoundedCornerShape(13.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(block.language?.uppercase() ?: "CODE", color = Muted, fontSize = 8.sp, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("assistant code", block.text))
                    },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) { Text("复制代码", color = Blue, fontSize = 9.sp) }
            }
            Text(
                block.text, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                color = Ink, lineHeight = 15.sp, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun MarkdownTable(
    table: MarkdownBlock.Table,
    bodyColor: androidx.compose.ui.graphics.Color,
    onOpenMarket: (String) -> Unit
) {
    val palette = MarketTheme.colors
    Surface(color = palette.surface, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, palette.line)) {
        Column(Modifier.fillMaxWidth()) {
            TableRow(
                table.headers, bodyColor, header = true, onOpenMarket = onOpenMarket,
                modifier = Modifier.background(palette.accentSoft)
            )
            table.rows.take(30).forEachIndexed { index, row ->
                TableRow(row, bodyColor, header = false, onOpenMarket = onOpenMarket)
                if (index < table.rows.take(30).lastIndex) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line.copy(alpha = 0.7f)))
                }
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    bodyColor: androidx.compose.ui.graphics.Color,
    header: Boolean,
    onOpenMarket: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = if (header) 7.dp else 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        cells.forEachIndexed { index, cell ->
            val numeric = isNumericTableCell(cell) || (header && isNumericHeader(cell))
            Box(
                Modifier.weight(if (index == 0) 1.6f else 1f),
                contentAlignment = if (numeric) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                MarkdownInlineText(
                    cell, bodyColor, 11.sp, 15.sp,
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                    onOpenMarket = onOpenMarket
                )
            }
        }
    }
}

private fun isNumericTableCell(text: String): Boolean {
    val normalized = text.trim().removeSuffix("%").removePrefix("+")
        .replace(",", "").replace("¥", "").replace("−", "-")
    return normalized.toDoubleOrNull() != null
}

private fun isNumericHeader(text: String): Boolean {
    val value = text.trim()
    return listOf("值", "额", "占比", "盈亏", "收益", "涨跌", "数量", "权重", "净值", "价格").any(value::contains)
}

@Composable
private fun MarkdownChartCard(chart: MarkdownBlock.Chart) {
    val palette = MarketTheme.colors
    val minValue = chart.points.minOf { it.value }
    val maxValue = chart.points.maxOf { it.value }
    Surface(color = palette.surfaceAlt, shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chart.title, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(chart.unit, color = Muted, fontSize = 10.sp)
            }
            Canvas(Modifier.fillMaxWidth().height(124.dp)) {
                val left = 5.dp.toPx()
                val right = size.width - left
                val top = 6.dp.toPx()
                val bottom = size.height - 6.dp.toPx()
                val range = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
                repeat(4) { line ->
                    val y = top + (bottom - top) * line / 3f
                    drawLine(palette.line, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
                }
                val path = Path()
                chart.points.forEachIndexed { index, point ->
                    val x = left + (right - left) * index / (chart.points.size - 1).coerceAtLeast(1)
                    val y = bottom - ((point.value - minValue) / range).toFloat() * (bottom - top)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, palette.accent, style = Stroke(width = 2.3.dp.toPx(), cap = StrokeCap.Round))
                chart.points.forEachIndexed { index, point ->
                    val x = left + (right - left) * index / (chart.points.size - 1).coerceAtLeast(1)
                    val y = bottom - ((point.value - minValue) / range).toFloat() * (bottom - top)
                    drawCircle(palette.accent, radius = 2.2.dp.toPx(), center = Offset(x, y))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(chart.points.first().label, color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1)
                Text("${chart.points.last().value}${chart.unit.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Text(chart.points.last().label, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
            }
        }
    }
}

/** Inline `**bold**`, `` `code` ``, and `[label](https://...)` / `[行情](market://id)`. */
private fun inline(
    text: String,
    bodyColor: androidx.compose.ui.graphics.Color,
    accentColor: androidx.compose.ui.graphics.Color
): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        val boldStart = text.indexOf("**", index)
        val codeStart = text.indexOf('`', index)
        val linkMatch = MARKDOWN_LINK.findAll(text, index).firstOrNull()
        val linkStart = linkMatch?.range?.first ?: -1
        val next = listOf(boldStart, codeStart, linkStart).filter { it >= 0 }.minOrNull()
        if (next == null) {
            append(text.substring(index))
            break
        }
        if (next > index) append(text.substring(index, next))
        when (next) {
            boldStart -> {
                val end = text.indexOf("**", next + 2)
                if (end < 0) { append(text.substring(next)); break }
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = bodyColor)) { append(text.substring(next + 2, end)) }
                index = end + 2
            }
            codeStart -> {
                val end = text.indexOf('`', next + 1)
                if (end < 0) { append(text.substring(next)); break }
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(next + 1, end)) }
                index = end + 1
            }
            else -> {
                val match = linkMatch
                if (match == null) {
                    append(text.substring(next))
                    break
                }
                val start = length
                withStyle(SpanStyle(color = accentColor, textDecoration = TextDecoration.Underline)) { append(match.groupValues[1]) }
                addStringAnnotation(URL_TAG, match.groupValues[2], start, length)
                index = match.range.last + 1
            }
        }
    }
}
