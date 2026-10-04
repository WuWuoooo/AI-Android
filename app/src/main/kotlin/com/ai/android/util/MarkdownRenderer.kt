package com.ai.android.util

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Markdown 块 */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class CodeBlock(val code: String, val lang: String) : MdBlock()
    data class ListItem(val text: String, val ordered: Boolean, val index: Int) : MdBlock()
    data class Quote(val text: String) : MdBlock()
}

/** 轻量 Markdown 解析（无第三方依赖） */
fun parseMarkdown(md: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = md.lines()
    val para = StringBuilder()
    val codeBuf = StringBuilder()

    fun flushPara() {
        if (para.isNotBlank()) {
            blocks.add(MdBlock.Paragraph(para.toString().trim()))
            para.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val t = raw.trim()
        when {
            t.startsWith("```") -> {
                flushPara()
                val lang = t.removePrefix("```").trim()
                codeBuf.clear()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeBuf.append(lines[i]).append('\n')
                    i++
                }
                blocks.add(MdBlock.CodeBlock(codeBuf.toString().trimEnd('\n'), lang))
            }
            t.startsWith("#") -> {
                flushPara()
                val level = t.takeWhile { it == '#' }.length.coerceIn(1, 4)
                blocks.add(MdBlock.Heading(level, t.dropWhile { it == '#' }.trim()))
            }
            t.startsWith("> ") || t == ">" -> {
                flushPara()
                blocks.add(MdBlock.Quote(t.removePrefix(">").trim()))
            }
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> {
                flushPara()
                blocks.add(MdBlock.ListItem(t.drop(2).trim(), false, 0))
            }
            Regex("^\\d+\\.\\s").containsMatchIn(t) -> {
                flushPara()
                val num = t.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
                blocks.add(MdBlock.ListItem(t.substringAfter(' ').trim(), true, num))
            }
            t.isEmpty() -> flushPara()
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(t)
            }
        }
        i++
    }
    flushPara()
    return blocks
}

/** 行内格式：**粗体** 与 `代码` */
fun buildInline(text: String, boldStyle: SpanStyle, codeStyle: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        val re = Regex("(\\*\\*[^*]+\\*\\*)|(`[^`]+`)")
        var last = 0
        re.findAll(text).forEach { m ->
            if (m.range.first > last) append(text.substring(last, m.range.first))
            val g = m.value
            when {
                g.startsWith("**") -> withStyle(boldStyle) { append(g.removeSurrounding("**")) }
                g.startsWith("`") -> withStyle(codeStyle) { append(g.removeSurrounding("`")) }
                else -> append(g)
            }
            last = m.range.last + 1
        }
        if (last < text.length) append(text.substring(last))
    }

/**
 * Markdown 渲染 Composable。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = 15.sp,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val baseColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary

    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    val codeSpan = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = codeColor)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                blocks.forEach { b ->
                    when (b) {
                        is MdBlock.Heading -> Text(
                            text = buildInline(b.text, bold, codeSpan),
                            color = baseColor,
                            fontSize = when (b.level) {
                                1 -> (fontSize.value + 6).sp
                                2 -> (fontSize.value + 3).sp
                                else -> (fontSize.value + 1).sp
                            },
                            fontWeight = FontWeight.Bold,
                        )

                        is MdBlock.Paragraph -> Text(
                            text = buildInline(b.text, bold, codeSpan),
                            color = baseColor,
                            fontSize = fontSize,
                            lineHeight = (fontSize.value + 6).sp,
                        )

                        is MdBlock.CodeBlock -> Box(
                            Modifier
                                .fillMaxWidth()
                                .background(codeBg, MaterialTheme.shapes.small)
                                .padding(10.dp)
                                .horizontalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = b.code,
                                fontFamily = FontFamily.Monospace,
                                fontSize = (fontSize.value - 2).sp,
                                color = codeColor,
                                lineHeight = (fontSize.value + 3).sp,
                            )
                        }

                        is MdBlock.ListItem -> Row(Modifier.fillMaxWidth()) {
                            Text(
                                text = if (b.ordered) "${b.index}. " else "• ",
                                color = accent,
                                fontSize = fontSize,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = buildInline(b.text, bold, codeSpan),
                                color = baseColor,
                                fontSize = fontSize,
                                lineHeight = (fontSize.value + 6).sp,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        is MdBlock.Quote -> Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append("▎ ") }
                                append(buildInline(b.text, bold, codeSpan))
                            },
                            color = baseColor.copy(alpha = 0.85f),
                            fontSize = fontSize,
                            lineHeight = (fontSize.value + 6).sp,
                        )
                    }
                }
            }
        }
    }
}
