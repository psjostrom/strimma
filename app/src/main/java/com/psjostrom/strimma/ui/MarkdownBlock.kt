package com.psjostrom.strimma.ui

internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class CodeBlock(val text: String) : MarkdownBlock
    data object Rule : MarkdownBlock
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

    var i = 0
    while (i < lines.size) {
        val line = lines[i].trim()

        if (FENCE_REGEX.matchEntire(line) != null) {
            flushParagraph()
            val (codeBlock, next) = consumeCodeBlock(lines, i + 1)
            blocks += codeBlock
            i = next
            continue
        }

        val classified = classifyLine(line)
        when (classified) {
            LineKind.Blank -> flushParagraph()
            LineKind.Paragraph -> paragraphLines += line
            LineKind.Blockquote -> paragraphLines += BLOCKQUOTE_REGEX.matchEntire(line)!!.groupValues[1].trim()
            else -> {
                flushParagraph()
                blocks += blockFor(classified, line)
            }
        }
        i += 1
    }
    flushParagraph()
    return blocks
}

private enum class LineKind { Blank, Rule, Heading, Bullet, Numbered, Blockquote, Paragraph }

private fun classifyLine(line: String): LineKind = when {
    line.isEmpty() -> LineKind.Blank
    RULE_REGEX.matchEntire(line) != null -> LineKind.Rule
    HEADING_REGEX.matchEntire(line) != null -> LineKind.Heading
    BULLET_REGEX.matchEntire(line) != null -> LineKind.Bullet
    NUMBERED_REGEX.matchEntire(line) != null -> LineKind.Numbered
    BLOCKQUOTE_REGEX.matchEntire(line) != null -> LineKind.Blockquote
    else -> LineKind.Paragraph
}

private fun blockFor(kind: LineKind, line: String): MarkdownBlock = when (kind) {
    LineKind.Rule -> MarkdownBlock.Rule
    LineKind.Heading -> {
        val match = HEADING_REGEX.matchEntire(line)!!
        MarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2].trim())
    }
    LineKind.Bullet -> MarkdownBlock.Bullet(BULLET_REGEX.matchEntire(line)!!.groupValues[1].trim())
    LineKind.Numbered -> MarkdownBlock.Bullet(NUMBERED_REGEX.matchEntire(line)!!.groupValues[1].trim())
    else -> error("blockFor must not be called for $kind")
}

private fun consumeCodeBlock(lines: List<String>, start: Int): Pair<MarkdownBlock.CodeBlock, Int> {
    val code = StringBuilder()
    var i = start
    while (i < lines.size && FENCE_REGEX.matchEntire(lines[i].trim()) == null) {
        if (code.isNotEmpty()) code.append('\n')
        code.append(lines[i])
        i += 1
    }
    val next = if (i < lines.size) i + 1 else i
    return MarkdownBlock.CodeBlock(code.toString()) to next
}

private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET_REGEX = Regex("""^[-*]\s+(.*)$""")
private val NUMBERED_REGEX = Regex("""^\d+\.\s+(.*)$""")
private val BLOCKQUOTE_REGEX = Regex("""^>\s*(.*)$""")
private val FENCE_REGEX = Regex("""^```\w*$""")
private val RULE_REGEX = Regex("""^(-{3,}|\*{3,}|_{3,})$""")
