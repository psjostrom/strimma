package com.psjostrom.strimma.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Lightweight Compose renderer for the small subset of CommonMark used in our
 * GitHub release notes: ATX headings (`##`, `###`), bullet lists (`-` or `*`),
 * inline `**bold**`, and inline `` `code` ``. Anything else falls through as plain text.
 *
 * Parsing is intentionally tolerant — release-note authors aren't writing
 * spec-perfect markdown. The goal is to render the common cases well, never crash.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val typography = MaterialTheme.typography

    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(modifier = Modifier.height(blockSpacing(blocks[index - 1], block)))
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = renderInline(block.text),
                    style = headingStyle(block.level, typography),
                    color = color,
                )

                is MarkdownBlock.Paragraph -> Text(
                    text = renderInline(block.text),
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
                        text = renderInline(block.text),
                        style = typography.bodyMedium,
                        color = color,
                    )
                }
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
