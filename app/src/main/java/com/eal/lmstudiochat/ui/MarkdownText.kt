package com.eal.lmstudiochat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.eal.lmstudiochat.ui.theme.Muted

@Composable
fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { markdownBlocks(text) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inlineMarkdown(block.text),
                    color = color,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                )
                is MdBlock.Bullet -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text(if (block.ordered) "${block.marker} " else "• ", color = color)
                    Text(
                        text = inlineMarkdown(block.text),
                        color = color,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                is MdBlock.Quote -> Text(
                    text = inlineMarkdown(block.text),
                    color = Muted,
                    style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                    modifier = Modifier.padding(start = 10.dp),
                )
                is MdBlock.Code -> Surface(
                    color = Color(0xFF161914),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = block.text,
                        color = color,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                is MdBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text),
                    color = color,
                    style = MaterialTheme.typography.bodyLarge,
                )
                is MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                is MdBlock.Table -> MarkdownTable(block.rows, color)
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val ordered: Boolean, val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

@Composable
private fun MarkdownTable(rows: List<List<String>>, color: Color) {
    val columns = rows.maxOf { it.size }
    Surface(
        color = Color(0xFF161914),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            rows.forEachIndexed { rowIndex, rawCells ->
                val cells = List(columns) { index -> rawCells.getOrElse(index) { "" } }
                if (rowIndex > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                }
                Row(
                    modifier = if (rowIndex == 0) {
                        Modifier.background(Color(0xFF22261E))
                    } else {
                        Modifier
                    },
                ) {
                    cells.forEach { cell ->
                        Text(
                            text = inlineMarkdown(cell),
                            color = color,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.Start,
                            modifier = Modifier
                                .width(176.dp)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun markdownBlocks(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    var index = 0

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) blocks += MdBlock.Paragraph(text)
        paragraph.clear()
    }

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            flushParagraph()
            val code = StringBuilder()
            index += 1
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                if (code.isNotEmpty()) code.append('\n')
                code.append(lines[index])
                index += 1
            }
            blocks += MdBlock.Code(code.toString())
            index += 1
            continue
        }
        if (trimmed.isEmpty()) {
            flushParagraph()
            index += 1
            continue
        }
        val heading = Regex("^(#{1,6})\\s+(.+)$").find(trimmed)
        if (heading != null) {
            flushParagraph()
            blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            index += 1
            continue
        }
        val bullet = Regex("^([-*+])\\s+(.+)$").find(trimmed)
        if (bullet != null) {
            flushParagraph()
            blocks += MdBlock.Bullet(ordered = false, marker = "", text = bullet.groupValues[2])
            index += 1
            continue
        }
        val numbered = Regex("^(\\d+)[.)]\\s+(.+)$").find(trimmed)
        if (numbered != null) {
            flushParagraph()
            blocks += MdBlock.Bullet(
                ordered = true,
                marker = "${numbered.groupValues[1]}.",
                text = numbered.groupValues[2],
            )
            index += 1
            continue
        }
        if (trimmed.startsWith(">")) {
            flushParagraph()
            blocks += MdBlock.Quote(trimmed.removePrefix(">").trim())
            index += 1
            continue
        }
        if (isHorizontalRule(trimmed)) {
            flushParagraph()
            blocks += MdBlock.Rule
            index += 1
            continue
        }
        if (isTableHeader(lines, index)) {
            flushParagraph()
            val rows = mutableListOf(splitCells(trimmed))
            index += 2
            while (index < lines.size && isTableRow(lines[index])) {
                rows += splitCells(lines[index].trim())
                index += 1
            }
            blocks += MdBlock.Table(rows)
            continue
        }
        if (paragraph.isNotEmpty()) paragraph.append(' ')
        paragraph.append(trimmed)
        index += 1
    }
    flushParagraph()
    return blocks.ifEmpty { listOf(MdBlock.Paragraph(source)) }
}

private fun isHorizontalRule(line: String): Boolean = line.matches(Regex("^([-*_])\\1{2,}$"))

private fun isTableHeader(lines: List<String>, index: Int): Boolean {
    if (index + 1 >= lines.size) return false
    val header = lines[index].trim()
    if (!header.contains('|')) return false
    return isSeparatorLine(lines[index + 1].trim())
}

private fun isTableRow(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.contains('|') && !isSeparatorLine(trimmed)
}

private fun isSeparatorLine(line: String): Boolean {
    if (!line.contains('|')) return false
    val cells = splitCells(line)
    return cells.isNotEmpty() && cells.all { separatorCell.matches(it) }
}

private fun splitCells(line: String): List<String> {
    var text = line.trim()
    if (text.startsWith("|")) text = text.removePrefix("|")
    if (text.endsWith("|")) text = text.removeSuffix("|")
    if (text.isEmpty()) return emptyList()
    return text.split('|').map { it.trim() }
}

private val separatorCell = Regex("^:?-{3,}:?$")

private fun inlineMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < source.length) {
        val marker = markerAt(source, index)
        if (marker == null) {
            append(source[index])
            index += 1
            continue
        }
        val close = source.indexOf(marker, startIndex = index + marker.length)
        if (close < 0) {
            append(source[index])
            index += 1
            continue
        }
        val body = source.substring(index + marker.length, close)
        if (body.isEmpty() || body.contains('\n')) {
            append(source[index])
            index += 1
            continue
        }
        when (marker) {
            "`" -> {
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0xFF161914),
                    ),
                )
                append(body)
                pop()
            }
            "**", "__" -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(inlineMarkdown(body))
                pop()
            }
            "*", "_" -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(inlineMarkdown(body))
                pop()
            }
            "~~" -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(inlineMarkdown(body))
                pop()
            }
        }
        index = close + marker.length
    }
}

private fun markerAt(source: String, index: Int): String? {
    val rest = source.substring(index)
    return when {
        rest.startsWith("**") -> "**"
        rest.startsWith("__") -> "__"
        rest.startsWith("~~") -> "~~"
        rest.startsWith("`") -> "`"
        rest.startsWith("*") -> "*"
        rest.startsWith("_") && (index == 0 || source[index - 1].isWhitespace()) -> "_"
        else -> null
    }
}
