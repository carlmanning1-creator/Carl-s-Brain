package com.carlmanning.carlsbrain.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val bodyStyle = MaterialTheme.typography.bodyLarge
    val lines = text.lines()

    Column(modifier = modifier) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("### ") -> {
                    val annotated = buildInlineAnnotated(line.drop(4), linkColor)
                    ClickableText(
                        text = annotated,
                        style = MaterialTheme.typography.titleMedium,
                        onClick = { offset -> annotated.urlAt(offset)?.let { uriHandler.openUri(it) } }
                    )
                }
                line.startsWith("## ") -> {
                    val annotated = buildInlineAnnotated(line.drop(3), linkColor)
                    ClickableText(
                        text = annotated,
                        style = MaterialTheme.typography.titleLarge,
                        onClick = { offset -> annotated.urlAt(offset)?.let { uriHandler.openUri(it) } }
                    )
                }
                line.startsWith("# ") -> {
                    val annotated = buildInlineAnnotated(line.drop(2), linkColor)
                    ClickableText(
                        text = annotated,
                        style = MaterialTheme.typography.headlineSmall,
                        onClick = { offset -> annotated.urlAt(offset)?.let { uriHandler.openUri(it) } }
                    )
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    val annotated = buildInlineAnnotated("• " + line.drop(2), linkColor)
                    ClickableText(
                        text = annotated,
                        style = bodyStyle,
                        onClick = { offset -> annotated.urlAt(offset)?.let { uriHandler.openUri(it) } }
                    )
                }
                line.matches(Regex("""^\d+\. .*""")) -> {
                    val annotated = buildInlineAnnotated(line, linkColor)
                    ClickableText(
                        text = annotated,
                        style = bodyStyle,
                        onClick = { offset -> annotated.urlAt(offset)?.let { uriHandler.openUri(it) } }
                    )
                }
                line.isBlank() -> {
                    Spacer(modifier = Modifier.height(8.dp))
                }
                else -> {
                    val annotated = buildInlineAnnotated(line, linkColor)
                    ClickableText(
                        text = annotated,
                        style = bodyStyle,
                        onClick = { offset -> annotated.urlAt(offset)?.let { uriHandler.openUri(it) } }
                    )
                }
            }
            i++
        }
    }
}

private fun androidx.compose.ui.text.AnnotatedString.urlAt(offset: Int): String? =
    getStringAnnotations(tag = "URL", start = offset, end = offset).firstOrNull()?.item

private fun buildInlineAnnotated(
    text: String,
    linkColor: androidx.compose.ui.graphics.Color
): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var remaining = text
        var cursor = 0

        // Process inline patterns in order of precedence
        val patterns = listOf(
            Pair(Regex("""\*\*(.+?)\*\*"""), "bold"),
            Pair(Regex("""\*(.+?)\*"""), "italic"),
            Pair(Regex("""`(.+?)`"""), "code"),
            Pair(Regex("""\[([^\]]+)\]\((https?://[^\)]+)\)"""), "link"),
            Pair(Regex("""https?://[^\s<>"]+"""), "rawurl")
        )

        // Build a list of matches sorted by position
        data class InlineMatch(val start: Int, val end: Int, val type: String, val display: String, val url: String = "")

        val allMatches = mutableListOf<InlineMatch>()
        patterns.forEach { (regex, type) ->
            regex.findAll(text).forEach { match ->
                val display = when (type) {
                    "bold" -> match.groupValues[1]
                    "italic" -> match.groupValues[1]
                    "code" -> match.groupValues[1]
                    "link" -> match.groupValues[1]
                    else -> match.value
                }
                val url = if (type == "link") match.groupValues[2] else if (type == "rawurl") match.value else ""
                allMatches.add(InlineMatch(match.range.first, match.range.last + 1, type, display, url))
            }
        }

        // Sort and deduplicate (keep first match at each position)
        val sorted = allMatches.sortedBy { it.start }
        val nonOverlapping = mutableListOf<InlineMatch>()
        var lastEnd = 0
        sorted.forEach { m ->
            if (m.start >= lastEnd) {
                nonOverlapping.add(m)
                lastEnd = m.end
            }
        }

        var pos = 0
        nonOverlapping.forEach { m ->
            if (pos < m.start) append(text.substring(pos, m.start))
            when (m.type) {
                "bold" -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(m.display)
                    pop()
                }
                "italic" -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(m.display)
                    pop()
                }
                "code" -> {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
                    append(m.display)
                    pop()
                }
                "link", "rawurl" -> {
                    val urlStart = length
                    pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    append(m.display)
                    pop()
                    addStringAnnotation("URL", m.url, urlStart, length)
                }
            }
            pos = m.end
        }
        if (pos < text.length) append(text.substring(pos))
    }
}
