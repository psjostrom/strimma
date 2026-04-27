package com.psjostrom.strimma.ui

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {

    // --- Block parsing ---

    @Test
    fun `parses heading levels by hash count`() {
        val blocks = parseMarkdownBlocks(
            """
            # H1
            ## H2
            ### H3
            #### H4
            """.trimIndent()
        )

        assertEquals(4, blocks.size)
        assertEquals(MarkdownBlock.Heading(1, "H1"), blocks[0])
        assertEquals(MarkdownBlock.Heading(2, "H2"), blocks[1])
        assertEquals(MarkdownBlock.Heading(3, "H3"), blocks[2])
        assertEquals(MarkdownBlock.Heading(4, "H4"), blocks[3])
    }

    @Test
    fun `parses bullet lines starting with dash or asterisk`() {
        val blocks = parseMarkdownBlocks(
            """
            - first
            * second
            """.trimIndent()
        )

        assertEquals(
            listOf(
                MarkdownBlock.Bullet("first"),
                MarkdownBlock.Bullet("second"),
            ),
            blocks,
        )
    }

    @Test
    fun `numbered list items render as bullets`() {
        val blocks = parseMarkdownBlocks(
            """
            1. one
            2. two
            10. ten
            """.trimIndent()
        )

        assertEquals(
            listOf(
                MarkdownBlock.Bullet("one"),
                MarkdownBlock.Bullet("two"),
                MarkdownBlock.Bullet("ten"),
            ),
            blocks,
        )
    }

    @Test
    fun `joins consecutive non-blank text lines into one paragraph`() {
        val blocks = parseMarkdownBlocks(
            """
            line one
            line two
            """.trimIndent()
        )

        assertEquals(
            listOf(MarkdownBlock.Paragraph("line one line two")),
            blocks,
        )
    }

    @Test
    fun `blank lines split paragraphs`() {
        val blocks = parseMarkdownBlocks(
            """
            first paragraph

            second paragraph
            """.trimIndent()
        )

        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("first paragraph"),
                MarkdownBlock.Paragraph("second paragraph"),
            ),
            blocks,
        )
    }

    @Test
    fun `fenced code block captures verbatim lines between fences`() {
        val blocks = parseMarkdownBlocks(
            """
            before

            ```
            line one
            line two
            ```

            after
            """.trimIndent()
        )

        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlock.Paragraph("before"), blocks[0])
        assertEquals(MarkdownBlock.CodeBlock("line one\nline two"), blocks[1])
        assertEquals(MarkdownBlock.Paragraph("after"), blocks[2])
    }

    @Test
    fun `fenced code block with language hint still works`() {
        val blocks = parseMarkdownBlocks(
            """
            ```kotlin
            val x = 1
            ```
            """.trimIndent()
        )

        assertEquals(1, blocks.size)
        assertEquals(MarkdownBlock.CodeBlock("val x = 1"), blocks[0])
    }

    @Test
    fun `unterminated fenced code block consumes through end of input`() {
        val blocks = parseMarkdownBlocks(
            """
            ```
            never closed
            """.trimIndent()
        )

        assertEquals(listOf(MarkdownBlock.CodeBlock("never closed")), blocks)
    }

    @Test
    fun `horizontal rule renders as Rule block`() {
        val blocks = parseMarkdownBlocks(
            """
            above

            ---

            below
            """.trimIndent()
        )

        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlock.Paragraph("above"), blocks[0])
        assertEquals(MarkdownBlock.Rule, blocks[1])
        assertEquals(MarkdownBlock.Paragraph("below"), blocks[2])
    }

    @Test
    fun `blockquote prefix is stripped and content joins paragraph`() {
        val blocks = parseMarkdownBlocks(
            """
            > a quoted line
            > continuation
            """.trimIndent()
        )

        assertEquals(
            listOf(MarkdownBlock.Paragraph("a quoted line continuation")),
            blocks,
        )
    }

    @Test
    fun `release-note layout produces headings bullets and headings in the right order`() {
        val notes = """
            ## v1.2.0-rc.1 — first release candidate

            ### Reliability
            - **Nightscout follower** no longer stops backfilling
            - **Manual Nightscout history pulls** are fast again
        """.trimIndent()

        val blocks = parseMarkdownBlocks(notes)

        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Heading && (blocks[0] as MarkdownBlock.Heading).level == 2)
        assertTrue(blocks[1] is MarkdownBlock.Heading && (blocks[1] as MarkdownBlock.Heading).level == 3)
        assertEquals("Reliability", (blocks[1] as MarkdownBlock.Heading).text)
        assertEquals(
            "**Nightscout follower** no longer stops backfilling",
            (blocks[2] as MarkdownBlock.Bullet).text,
        )
    }

    @Test
    fun `empty input yields no blocks`() {
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdownBlocks(""))
    }

    @Test
    fun `paragraph then heading flushes correctly`() {
        val blocks = parseMarkdownBlocks(
            """
            intro line
            ## Section
            after
            """.trimIndent()
        )

        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("intro line"),
                MarkdownBlock.Heading(2, "Section"),
                MarkdownBlock.Paragraph("after"),
            ),
            blocks,
        )
    }

    // --- Inline parsing ---

    @Test
    fun `inline parser bolds paired double-asterisks`() {
        val rendered = renderInline("normal **bold** normal")

        assertEquals("normal bold normal", rendered.text)
        val bolds = rendered.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, bolds.size)
        assertEquals("bold", rendered.text.substring(bolds[0].start, bolds[0].end))
    }

    @Test
    fun `inline parser italicizes single-asterisk pairs`() {
        val rendered = renderInline("plain *emphasis* plain")

        assertEquals("plain emphasis plain", rendered.text)
        val italics = rendered.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        assertEquals(1, italics.size)
        assertEquals("emphasis", rendered.text.substring(italics[0].start, italics[0].end))
    }

    @Test
    fun `inline parser italicizes underscore pairs`() {
        val rendered = renderInline("plain _emphasis_ plain")

        assertEquals("plain emphasis plain", rendered.text)
        val italics = rendered.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        assertEquals(1, italics.size)
    }

    @Test
    fun `intra-word underscores do not become italic`() {
        val rendered = renderInline("Use snake_case_naming please")

        assertEquals("Use snake_case_naming please", rendered.text)
        assertTrue(rendered.spanStyles.none { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `bold takes priority over italic when both are present`() {
        val rendered = renderInline("**bold *with italic* inside**")

        assertEquals("bold with italic inside", rendered.text)
        val bolds = rendered.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        val italics = rendered.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        assertEquals(1, bolds.size)
        assertEquals(1, italics.size)
        assertEquals("bold with italic inside", rendered.text.substring(bolds[0].start, bolds[0].end))
        assertEquals("with italic", rendered.text.substring(italics[0].start, italics[0].end))
    }

    @Test
    fun `inline parser monospaces backtick-wrapped code`() {
        val rendered = renderInline("server-provided `direction` and `delta` are preserved")

        assertEquals("server-provided direction and delta are preserved", rendered.text)
        val mono = rendered.spanStyles.filter { it.item.fontFamily == FontFamily.Monospace }
        assertEquals(2, mono.size)
        assertEquals("direction", rendered.text.substring(mono[0].start, mono[0].end))
        assertEquals("delta", rendered.text.substring(mono[1].start, mono[1].end))
    }

    @Test
    fun `inline parser strikes tilde-tilde wrapped text`() {
        val rendered = renderInline("this is ~~outdated~~ now")

        assertEquals("this is outdated now", rendered.text)
        val strikes = rendered.spanStyles.filter { it.item.textDecoration == TextDecoration.LineThrough }
        assertEquals(1, strikes.size)
        assertEquals("outdated", rendered.text.substring(strikes[0].start, strikes[0].end))
    }

    @Test
    fun `inline parser produces a clickable link annotation for bracket paren syntax`() {
        val rendered = renderInline("see [PR #201](https://github.com/x/strimma/pull/201) for details")

        assertEquals("see PR #201 for details", rendered.text)
        val links = rendered.getLinkAnnotations(0, rendered.length)
        assertEquals(1, links.size)
        val annotation = links[0].item as? LinkAnnotation.Url
        assertNotNull(annotation)
        assertEquals("https://github.com/x/strimma/pull/201", annotation!!.url)
        assertEquals("PR #201", rendered.text.substring(links[0].start, links[0].end))
    }

    @Test
    fun `link with bold inside renders both bold and link annotation`() {
        val rendered = renderInline("[**bold link**](https://example.com)")

        assertEquals("bold link", rendered.text)
        assertEquals(1, rendered.getLinkAnnotations(0, rendered.length).size)
        assertTrue(rendered.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `unmatched asterisks and backticks are emitted as plain characters`() {
        val rendered = renderInline("here is a stray * and an unclosed `code")

        assertEquals("here is a stray * and an unclosed `code", rendered.text)
    }

    @Test
    fun `unmatched bracket without paren is left literal`() {
        val rendered = renderInline("see [ref] without url")

        assertEquals("see [ref] without url", rendered.text)
        assertTrue(rendered.getLinkAnnotations(0, rendered.length).isEmpty())
    }

    @Test
    fun `bare html tags are stripped without losing surrounding text`() {
        val rendered = renderInline("first<br>second<details>hidden</details>third")

        assertEquals("firstsecondhiddenthird", rendered.text)
    }

    @Test
    fun `comparison operators are not mistaken for html tags`() {
        val rendered = renderInline("if a < b and c > d then x")

        assertEquals("if a < b and c > d then x", rendered.text)
    }

    @Test
    fun `em dash and other unicode chars pass through untouched`() {
        val rendered = renderInline("v1.2.0 — first release candidate")
        assertEquals("v1.2.0 — first release candidate", rendered.text)
    }

    // --- Guard rail: no raw markdown punctuation ever shows for supported syntax ---

    @Test
    fun `every supported element strips its markup characters from rendered text`() {
        val source = """
            ## Heading

            ### Sub

            - **bold bullet** with `code` and *italic*
            - 1. a numbered-style line
            - [link](https://example.com)
            - ~~struck~~ text
            - <br> tag should vanish
            - > quoted-looking but inline

            ```
            code block stays verbatim
            ```
        """.trimIndent()

        val blocks = parseMarkdownBlocks(source)
        val visible = buildString {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Heading -> append(renderInline(block.text).text)
                    is MarkdownBlock.Paragraph -> append(renderInline(block.text).text)
                    is MarkdownBlock.Bullet -> append(renderInline(block.text).text)
                    is MarkdownBlock.CodeBlock -> append(block.text)
                    MarkdownBlock.Rule -> Unit
                }
                append('\n')
            }
        }

        // Code blocks intentionally keep their content verbatim — exempt from the
        // "no markdown punctuation" rule. Strip them from the inspected string.
        val withoutCodeBlocks = visible.lineSequence()
            .filter { it != "code block stays verbatim" }
            .joinToString("\n")

        assertTrue("no `**` should leak: $withoutCodeBlocks", "**" !in withoutCodeBlocks)
        assertTrue("no `~~` should leak: $withoutCodeBlocks", "~~" !in withoutCodeBlocks)
        assertTrue("no `<br>` should leak: $withoutCodeBlocks", "<br>" !in withoutCodeBlocks)
        assertTrue(
            "no link bracket syntax should leak: $withoutCodeBlocks",
            "](" !in withoutCodeBlocks
        )
    }
}
