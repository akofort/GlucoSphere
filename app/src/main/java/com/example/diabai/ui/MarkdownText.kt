package com.example.diabai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Minimal Markdown rendering for model output: headings (#/##/###), **bold**, *italic*,
 * `inline code`, "- "/"* " bullet lines, and GFM-style pipe tables. Not a full CommonMark
 * implementation -- covers what chat-style LLM answers (including raw MCP tool responses)
 * typically use, without pulling in a third-party dependency.
 */
@Composable
fun MarkdownText(markdown: String) {
    val lines = markdown.split("\n")
    Column {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (isTableRow(line) && i + 1 < lines.size && isTableSeparatorRow(lines[i + 1])) {
                val headers = parseTableRow(line)
                var j = i + 2
                val rows = mutableListOf<List<String>>()
                while (j < lines.size && isTableRow(lines[j])) {
                    rows.add(parseTableRow(lines[j]))
                    j++
                }
                MarkdownTable(headers, rows)
                i = j
            } else {
                MarkdownLine(line)
                i++
            }
        }
    }
}

@Composable
private fun MarkdownLine(line: String) {
    when {
        line.startsWith("### ") -> Text(line.removePrefix("### "), style = MaterialTheme.typography.titleSmall)
        line.startsWith("## ") -> Text(line.removePrefix("## "), style = MaterialTheme.typography.titleMedium)
        line.startsWith("# ") -> Text(line.removePrefix("# "), style = MaterialTheme.typography.titleLarge)
        line.startsWith("- ") || line.startsWith("* ") -> Text(
            text = buildInlineAnnotatedString("• " + line.substring(2)),
            style = MaterialTheme.typography.bodyMedium,
        )
        line.isBlank() -> Text("")
        else -> Text(text = buildInlineAnnotatedString(line), style = MaterialTheme.typography.bodyMedium)
    }
}

/** A GFM pipe-table row: `| a | b | c |` (a leading/trailing pipe is the common convention, but
 * not required by the spec -- at least one interior pipe is what actually makes it a row). */
private fun isTableRow(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    return trimmed.count { it == '|' } >= 1 && trimmed.contains('|')
}

/** The `|---|:---:|---:|` line under a table's header row. */
private fun isTableSeparatorRow(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.contains('|')) return false
    val cells = trimmed.trim('|').split("|")
    if (cells.isEmpty()) return false
    return cells.all { it.trim().matches(Regex(":?-{1,}:?")) }
}

private fun parseTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

@Composable
private fun MarkdownTable(headers: List<String>, rows: List<List<String>>) {
    Surface(
        shape = MaterialTheme.shapes.small,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            TableRow(headers, isHeader = true)
            HorizontalDivider()
            rows.forEach { row -> TableRow(row, isHeader = false) }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, isHeader: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        cells.forEach { cell ->
            Text(
                text = buildInlineAnnotatedString(cell),
                style = if (isHeader) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
        }
    }
}

private fun buildInlineAnnotatedString(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end == -1) {
                    append(text.substring(i))
                    i = text.length
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                }
            }
            text.startsWith("`", i) -> {
                val end = text.indexOf("`", i + 1)
                if (end == -1) {
                    append(text.substring(i))
                    i = text.length
                } else {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                }
            }
            text.startsWith("*", i) -> {
                val end = text.indexOf("*", i + 1)
                if (end == -1) {
                    append(text.substring(i))
                    i = text.length
                } else {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                }
            }
            else -> {
                append(text[i])
                i++
            }
        }
    }
}
