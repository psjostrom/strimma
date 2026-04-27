package com.psjostrom.strimma.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Lightweight Compose renderer for the subset of CommonMark used in our
 * GitHub release notes:
 *
 * Block-level: `##`/`###` headings, `-`/`*` bullets, `1.` numbered lists
 * (rendered as bullets), fenced ` ``` ` code blocks, blockquotes (rendered
 * as paragraphs without the `>`), horizontal rules.
 *
 * Inline: `**bold**`, `*italic*` / `_italic_`, `` `code` ``, `[text](url)`
 * links, `~~strike~~`. Bare HTML tags are stripped.
 *
 * Anything outside that subset renders as plain text rather than crashing
 * or showing raw markdown punctuation.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val typography = MaterialTheme.typography
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBlockBackground = MaterialTheme.colorScheme.surfaceVariant

    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(modifier = Modifier.height(blockSpacing(blocks[index - 1], block)))
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = renderInline(block.text, linkColor),
                    style = headingStyle(block.level, typography),
                    color = color,
                )

                is MarkdownBlock.Paragraph -> Text(
                    text = renderInline(block.text, linkColor),
                    style = typography.bodyMedium,
                    color = color,
                )

                is MarkdownBlock.Bullet -> Row(modifier = Modifier.padding(start = 4.dp)) {
                    Text(
                        text = "•",
                        style = typography.bodyMedium,
                        color = color,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(16.dp),
                    )
                    Text(
                        text = renderInline(block.text, linkColor),
                        style = typography.bodyMedium,
                        color = color,
                    )
                }

                is MarkdownBlock.CodeBlock -> Text(
                    text = block.text,
                    style = typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(codeBlockBackground)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )

                MarkdownBlock.Rule -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

private fun headingStyle(level: Int, typography: Typography): TextStyle {
    val base = when (level) {
        1 -> typography.titleLarge
        2 -> typography.titleMedium
        else -> typography.titleSmall
    }
    return base.copy(fontWeight = FontWeight.SemiBold)
}

private fun blockSpacing(previous: MarkdownBlock, current: MarkdownBlock): Dp {
    val bothBullets = previous is MarkdownBlock.Bullet && current is MarkdownBlock.Bullet
    return if (bothBullets) 4.dp else 8.dp
}
