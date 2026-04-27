package com.psjostrom.strimma.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * Inline parser. Handles `**bold**`, `*italic*` / `_italic_`, `` `code` ``,
 * `[text](url)` links, `~~strike~~`, and strips bare HTML tags. Anything that
 * looks like markdown punctuation but doesn't match a complete pair passes
 * through as a literal character so we never lose user-visible content.
 *
 * `linkColor` is threaded through so links can adopt the active theme's
 * primary color without the parser depending on Compose theming.
 */
internal fun renderInline(text: String, linkColor: Color? = null): AnnotatedString =
    buildAnnotatedString { appendInline(text, linkColor) }

@Suppress("CyclomaticComplexMethod") // Single dispatch site for inline tokens
private fun AnnotatedString.Builder.appendInline(text: String, linkColor: Color?) {
    var i = 0
    val length = text.length
    while (i < length) {
        val c = text[i]
        i = when {
            c == '*' && i + 1 < length && text[i + 1] == '*' -> appendBold(text, i, linkColor)
            (c == '*' || c == '_') && isItalicOpen(text, i) -> appendItalic(text, i, c, linkColor)
            c == '~' && i + 1 < length && text[i + 1] == '~' -> appendStrike(text, i, linkColor)
            c == '`' -> appendCode(text, i)
            c == '[' -> appendLink(text, i, linkColor)
            c == '<' -> appendOrSkipHtml(text, i)
            else -> { append(c); i + 1 }
        }
    }
}

private fun AnnotatedString.Builder.appendBold(text: String, start: Int, linkColor: Color?): Int {
    val end = text.indexOf("**", startIndex = start + 2)
    if (end == -1) {
        append(text[start])
        return start + 1
    }
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
        appendInline(text.substring(start + 2, end), linkColor)
    }
    return end + 2
}

private fun AnnotatedString.Builder.appendItalic(text: String, start: Int, delimiter: Char, linkColor: Color?): Int {
    val end = findItalicClose(text, start + 1, delimiter)
    if (end == -1) {
        append(text[start])
        return start + 1
    }
    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
        appendInline(text.substring(start + 1, end), linkColor)
    }
    return end + 1
}

private fun AnnotatedString.Builder.appendStrike(text: String, start: Int, linkColor: Color?): Int {
    val end = text.indexOf("~~", startIndex = start + 2)
    if (end == -1) {
        append(text[start])
        return start + 1
    }
    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
        appendInline(text.substring(start + 2, end), linkColor)
    }
    return end + 2
}

private fun AnnotatedString.Builder.appendCode(text: String, start: Int): Int {
    val end = text.indexOf('`', startIndex = start + 1)
    if (end == -1) {
        append(text[start])
        return start + 1
    }
    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
        append(text, start + 1, end)
    }
    return end + 1
}

private fun AnnotatedString.Builder.appendLink(text: String, start: Int, linkColor: Color?): Int {
    val link = parseLink(text, start)
    if (link == null) {
        append(text[start])
        return start + 1
    }
    val (linkText, url, endIndex) = link
    val style = if (linkColor != null) {
        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
    } else {
        SpanStyle(textDecoration = TextDecoration.Underline)
    }
    withLink(LinkAnnotation.Url(url, TextLinkStyles(style = style))) {
        appendInline(linkText, linkColor)
    }
    return endIndex
}

private fun AnnotatedString.Builder.appendOrSkipHtml(text: String, start: Int): Int {
    val end = text.indexOf('>', startIndex = start + 1)
    if (end != -1 && looksLikeHtmlTag(text, start, end)) {
        return end + 1
    }
    append(text[start])
    return start + 1
}
