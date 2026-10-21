package com.zillit.desktop.core.common

/**
 * One slice of a plain-text body: words, or an address worth lighting up.
 *
 * Lives here because more than one surface shows text somebody wrote and
 * wants the addresses in it clickable — CNC's bubbles, the chat that runs for
 * the length of a call — and the two must agree on what counts as a link. A
 * second regex elsewhere would mean one surface lighting up an address the
 * other left dead.
 *
 * What counts is whatever the phones light up with `android:autoLink` on the
 * bubble (`chat_text_layout.xml:113`): a web address, with or without its
 * scheme, and an email address. Phone numbers are left alone — a desktop has
 * nothing to dial them with.
 */
sealed interface LinkSpan {
    data class Words(val text: String) : LinkSpan

    /** [text] as it was typed; [url] is what opening it means, scheme and all. */
    data class Link(val url: String, val text: String) : LinkSpan
}

/** A scheme-led address, a bare `www.` one, or an email address. */
private val LINK = Regex(
    """https?://[^\s<>"']+|www\.[^\s<>"']+|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""",
    RegexOption.IGNORE_CASE,
)

/** Sentence punctuation that ends up glued to an address is not part of it. */
private const val LINK_TRAILING_PUNCTUATION = ".,;:!?)"

/** [text] split around every address in it; one plain span when there is none. */
fun linkSpans(text: String): List<LinkSpan> {
    if (!text.mayHoldLink()) return listOf(LinkSpan.Words(text))
    val spans = mutableListOf<LinkSpan>()
    var consumed = 0
    LINK.findAll(text).forEach { match ->
        val found = match.value.trimEnd { it in LINK_TRAILING_PUNCTUATION }
        if (found.isEmpty()) return@forEach
        if (match.range.first > consumed) spans += LinkSpan.Words(text.substring(consumed, match.range.first))
        spans += LinkSpan.Link(url = openable(found), text = found)
        consumed = match.range.first + found.length
    }
    if (consumed < text.length) spans += LinkSpan.Words(text.substring(consumed))
    return spans.ifEmpty { listOf(LinkSpan.Words(text)) }
}

/** A cheap pre-check, so the common message never meets the regex. */
private fun String.mayHoldLink(): Boolean =
    contains("://") || contains("www.", ignoreCase = true) || contains('@')

/** The address a browser or mail client can open: a scheme in front of what was typed. */
private fun openable(text: String): String = when {
    text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) -> text
    text.startsWith("www.", ignoreCase = true) -> "https://$text"
    else -> "mailto:$text"
}
