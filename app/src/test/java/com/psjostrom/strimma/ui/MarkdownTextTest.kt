package com.psjostrom.strimma.ui

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {

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

        assertEquals(2, blocks.size)
        assertEquals(MarkdownBlock.Bullet("first"), blocks[0])
        assertEquals(MarkdownBlock.Bullet("second"), blocks[1])
    }

    @Test
    fun `joins consecutive non-blank text lines into one paragraph`() {
        val blocks = parseMarkdownBlocks(
            """
            line one
            line two
            line three
            """.trimIndent()
        )

        assertEquals(1, blocks.size)
        assertEquals(MarkdownBlock.Paragraph("line one line two line three"), blocks[0])
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
    fun `release-note layout produces heading bullets and headings in the right order`() {
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
        assertEquals(
            "**Manual Nightscout history pulls** are fast again",
            (blocks[3] as MarkdownBlock.Bullet).text,
        )
    }

    @Test
    fun `inline parser bolds paired double-asterisks`() {
        val rendered = renderInline("normal **bold** normal")

        assertEquals("normal bold normal", rendered.text)
        val bolds = rendered.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, bolds.size)
        assertEquals(7, bolds[0].start)
        assertEquals(11, bolds[0].end)
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
    fun `unmatched asterisks and backticks are emitted as plain characters`() {
        val rendered = renderInline("here is a stray * and an unclosed `code")

        assertEquals("here is a stray * and an unclosed `code", rendered.text)
        assertTrue(rendered.spanStyles.none { it.item.fontWeight == FontWeight.Bold })
        assertTrue(rendered.spanStyles.none { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `inline parser handles bold and code on the same line`() {
        val rendered = renderInline("**Tidepool** uploads call `forceUpload()` correctly")

        assertEquals("Tidepool uploads call forceUpload() correctly", rendered.text)
        val bolds = rendered.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        val mono = rendered.spanStyles.filter { it.item.fontFamily == FontFamily.Monospace }
        assertEquals(1, bolds.size)
        assertEquals(1, mono.size)
        assertEquals("Tidepool", rendered.text.substring(bolds[0].start, bolds[0].end))
        assertEquals("forceUpload()", rendered.text.substring(mono[0].start, mono[0].end))
    }

    @Test
    fun `em dash and other unicode chars pass through untouched`() {
        val rendered = renderInline("v1.2.0 — first release candidate")
        assertEquals("v1.2.0 — first release candidate", rendered.text)
    }

    @Test
    fun `empty string yields no blocks`() {
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdownBlocks(""))
    }

    @Test
    fun `pre-existing paragraph then heading flushes correctly`() {
        val blocks = parseMarkdownBlocks(
            """
            intro line
            ## Section
            after
            """.trimIndent()
        )

        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlock.Paragraph("intro line"), blocks[0])
        assertEquals(MarkdownBlock.Heading(2, "Section"), blocks[1])
        assertEquals(MarkdownBlock.Paragraph("after"), blocks[2])
    }
}
