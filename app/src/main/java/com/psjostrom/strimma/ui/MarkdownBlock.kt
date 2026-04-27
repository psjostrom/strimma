package com.psjostrom.strimma.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
}

internal fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val lines = markdown.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraphLines = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraphLines.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraphLines.joinToString(" "))
            paragraphLines.clear()
        }
    }

    for (raw in lines) {
        val line = raw.trim()
        when {
            line.isEmpty() -> flushParagraph()

            HEADING_REGEX.matchEntire(line) != null -> {
                flushParagraph()
                val match = HEADING_REGEX.matchEntire(line)!!
                val level = match.groupValues[1].length
                val text = match.groupValues[2].trim()
                blocks += MarkdownBlock.Heading(level, text)
            }

            BULLET_REGEX.matchEntire(line) != null -> {
                flushParagraph()
                val match = BULLET_REGEX.matchEntire(line)!!
                blocks += MarkdownBlock.Bullet(match.groupValues[1].trim())
            }

            else -> paragraphLines += line
        }
    }
    flushParagraph()
    return blocks
}

private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET_REGEX = Regex("""^[-*]\s+(.*)$""")

/**
 * Inline parser. Handles `**bold**` and `` `code` `` spans. Stray `*` or backticks
 * pass through unchanged so we never lose user-visible characters.
 */
internal fun renderInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val length = text.length
    while (i < length) {
        when {
            i + 1 < length && text[i] == '*' && text[i + 1] == '*' -> {
                val end = text.indexOf("**", startIndex = i + 2)
                if (end == -1) {
                    append(text[i])
                    i += 1
                } else {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text, i + 2, end)
                    pop()
                    i = end + 2
                }
            }

            text[i] == '`' -> {
                val end = text.indexOf('`', startIndex = i + 1)
                if (end == -1) {
                    append(text[i])
                    i += 1
                } else {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
                    append(text, i + 1, end)
                    pop()
                    i = end + 1
                }
            }

            else -> {
                append(text[i])
                i += 1
            }
        }
    }
}
