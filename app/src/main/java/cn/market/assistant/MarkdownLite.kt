package cn.market.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Small Markdown renderer for assistant answers: headings, bullets, numbered lists, bold, inline code
 * and {@code ---} separators. Deliberately not a full Markdown engine — the assistant is asked for
 * short, factual answers, so a predictable subset keeps rendering fast and safe.
 */
@Composable
internal fun MarkdownLite(
    text: String,
    modifier: Modifier = Modifier,
    bodyColor: androidx.compose.ui.graphics.Color = Ink,
    mutedColor: androidx.compose.ui.graphics.Color = Muted
) {
    val blocks = remember(text) { parseMarkdownLite(text) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    inline(block.text, bodyColor),
                    fontSize = if (block.level == 1) 16.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp
                )
                is MarkdownBlock.Bullet -> Text(
                    inline("· ${block.text}", bodyColor),
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
                is MarkdownBlock.Numbered -> Text(
                    inline("${block.index}. ${block.text}", bodyColor),
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
                is MarkdownBlock.Quote -> Text(
                    inline(block.text, mutedColor),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
                is MarkdownBlock.Code -> Text(
                    block.text,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = bodyColor,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
                is MarkdownBlock.Divider -> Text("———", color = mutedColor, fontSize = 10.sp)
                is MarkdownBlock.Paragraph -> Text(
                    inline(block.text, bodyColor),
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
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
    data class Code(val text: String) : MarkdownBlock
    data object Divider : MarkdownBlock
}

private val NUMBERED = Regex("^(\\d+)[.、)]\\s+(.*)$")

internal fun parseMarkdownLite(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()
    val code = StringBuilder()
    var inCode = false

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += MarkdownBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }

    text.replace("\r\n", "\n").lines().forEach { rawLine ->
        val line = rawLine.trimEnd()
        when {
            line.trimStart().startsWith("```") -> {
                if (inCode) {
                    blocks += MarkdownBlock.Code(code.toString().trimEnd())
                    code.clear()
                } else {
                    flushParagraph()
                }
                inCode = !inCode
            }
            inCode -> code.appendLine(line)
            line.isBlank() -> flushParagraph()
            line.startsWith("---") || line.startsWith("***") -> {
                flushParagraph()
                blocks += MarkdownBlock.Divider
            }
            line.startsWith("### ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Heading(3, line.removePrefix("### "))
            }
            line.startsWith("## ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Heading(2, line.removePrefix("## "))
            }
            line.startsWith("# ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Heading(1, line.removePrefix("# "))
            }
            line.trimStart().startsWith("> ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Quote(line.trimStart().removePrefix("> "))
            }
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Bullet(line.trimStart().substring(2))
            }
            NUMBERED.matches(line.trimStart()) -> {
                flushParagraph()
                val match = NUMBERED.find(line.trimStart())!!
                blocks += MarkdownBlock.Numbered(match.groupValues[1].toIntOrNull() ?: 0, match.groupValues[2])
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line.trim())
            }
        }
    }
    if (inCode && code.isNotEmpty()) blocks += MarkdownBlock.Code(code.toString().trimEnd())
    flushParagraph()
    return blocks
}

/** Renders `**bold**` and `` `code` `` inside a single line. */
private fun inline(text: String, bodyColor: androidx.compose.ui.graphics.Color) = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        val boldStart = text.indexOf("**", index)
        val codeStart = text.indexOf('`', index)
        val next = listOf(boldStart, codeStart).filter { it >= 0 }.minOrNull()
        if (next == null) {
            append(text.substring(index))
            break
        }
        if (next > index) append(text.substring(index, next))
        if (next == boldStart) {
            val end = text.indexOf("**", next + 2)
            if (end < 0) {
                append(text.substring(next))
                break
            }
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = bodyColor)) {
                append(text.substring(next + 2, end))
            }
            index = end + 2
        } else {
            val end = text.indexOf('`', next + 1)
            if (end < 0) {
                append(text.substring(next))
                break
            }
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                append(text.substring(next + 1, end))
            }
            index = end + 1
        }
    }
}
