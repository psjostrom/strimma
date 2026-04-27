package com.psjostrom.strimma.ui

internal fun isItalicOpen(text: String, index: Int): Boolean {
    if (index + 1 >= text.length) return false
    val next = text[index + 1]
    if (next.isWhitespace() || next == text[index]) return false
    val prev = if (index == 0) ' ' else text[index - 1]
    // Don't fire on intra-word underscores like `snake_case_name`.
    if (text[index] == '_' && prev.isLetterOrDigit()) return false
    return true
}

internal fun findItalicClose(text: String, start: Int, delimiter: Char): Int {
    var j = start
    while (j < text.length) {
        if (text[j] == delimiter && isItalicClose(text, j, delimiter)) {
            return j
        }
        j += 1
    }
    return -1
}

private fun isItalicClose(text: String, index: Int, delimiter: Char): Boolean {
    // Bold (**) takes priority — don't consume `*` of `**`.
    val nextIsSame = index + 1 < text.length && text[index + 1] == delimiter
    if (nextIsSame) return false

    val prev = if (index == 0) ' ' else text[index - 1]
    if (prev.isWhitespace() || prev == delimiter) return false

    // Don't fire on intra-word underscores (e.g. `snake_case`).
    if (delimiter == '_') {
        val after = if (index + 1 < text.length) text[index + 1] else ' '
        if (after.isLetterOrDigit()) return false
    }
    return true
}

/** Returns (linkText, url, endIndex) on a match, or null. */
internal fun parseLink(text: String, openBracket: Int): Triple<String, String, Int>? {
    val closeBracket = text.indexOf(']', startIndex = openBracket + 1)
    if (closeBracket == -1 || closeBracket + 1 >= text.length || text[closeBracket + 1] != '(') {
        return null
    }
    val closeParen = text.indexOf(')', startIndex = closeBracket + 2)
    if (closeParen == -1) return null
    val linkText = text.substring(openBracket + 1, closeBracket)
    val url = text.substring(closeBracket + 2, closeParen)
    if (url.isBlank()) return null
    return Triple(linkText, url, closeParen + 1)
}

internal fun looksLikeHtmlTag(text: String, openAngle: Int, closeAngle: Int): Boolean {
    // Be conservative — consume `<`...`>` only if it actually looks like an HTML tag.
    // This prevents stripping legitimate text like `1 < 2 > 0`.
    if (closeAngle - openAngle < 2) return false
    val first = text[openAngle + 1]
    return first.isLetter() || first == '/' || first == '!'
}
