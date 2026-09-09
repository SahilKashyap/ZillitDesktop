package com.zillit.desktop.feature.chat.domain

/**
 * One slice of a message body: plain words, a tagged crew member, or a link.
 *
 * The wire writes a tag as `@{{userId}}` (web `cncUtil.js:44` — the `@` is
 * optional in the regex because some writers drop it). The reader never sees
 * the id: known ids render as `@Full Name`, clickable; an id the crew list
 * cannot place stays as raw text rather than becoming a dead link.
 *
 * Links are whatever the phones light up with `android:autoLink` on the
 * bubble (`chat_text_layout.xml:113`): a web address, with or without its
 * scheme, and an email address. Phone numbers are left alone — a desktop has
 * nothing to dial them with.
 */
sealed interface MentionSpan {
    data class Words(val text: String) : MentionSpan

    data class Mention(val userId: String, val name: String) : MentionSpan

    /** [text] as it was typed; [url] is what opening it means, scheme and all. */
    data class Link(val url: String, val text: String) : MentionSpan
}

private val MENTION = Regex("@?\\{\\{([a-zA-Z0-9]+)\\}\\}")

/** A scheme-led address, a bare `www.` one, or an email address. */
private val LINK = Regex(
    """https?://[^\s<>"']+|www\.[^\s<>"']+|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""",
    RegexOption.IGNORE_CASE,
)

/** Sentence punctuation that ends up glued to an address is not part of it. */
private const val LINK_TRAILING_PUNCTUATION = ".,;:!?)"

fun mentionSpans(body: String, nameOf: (String) -> String?): List<MentionSpan> {
    if ("{{" !in body) return linkSpans(body)

    val spans = mutableListOf<MentionSpan>()
    var consumed = 0
    MENTION.findAll(body).forEach { match ->
        val name = nameOf(match.groupValues[1]) ?: return@forEach
        if (match.range.first > consumed) {
            spans += linkSpans(body.substring(consumed, match.range.first))
        }
        spans += MentionSpan.Mention(match.groupValues[1], name)
        consumed = match.range.last + 1
    }
    if (consumed < body.length) spans += linkSpans(body.substring(consumed))
    return spans.ifEmpty { listOf(MentionSpan.Words(body)) }
}

/** [words] split around every address in it; one plain span when there is none. */
private fun linkSpans(words: String): List<MentionSpan> {
    if (!words.mayHoldLink()) return listOf(MentionSpan.Words(words))
    val spans = mutableListOf<MentionSpan>()
    var consumed = 0
    LINK.findAll(words).forEach { match ->
        val text = match.value.trimEnd { it in LINK_TRAILING_PUNCTUATION }
        if (text.isEmpty()) return@forEach
        if (match.range.first > consumed) spans += MentionSpan.Words(words.substring(consumed, match.range.first))
        spans += MentionSpan.Link(url = openable(text), text = text)
        consumed = match.range.first + text.length
    }
    if (consumed < words.length) spans += MentionSpan.Words(words.substring(consumed))
    return spans.ifEmpty { listOf(MentionSpan.Words(words)) }
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

/** The body as plain text with tags spelt `@Name` — for previews and copies. */
fun flattenMentions(body: String, nameOf: (String) -> String?): String =
    mentionSpans(body, nameOf).joinToString("") { span ->
        when (span) {
            is MentionSpan.Words -> span.text
            is MentionSpan.Mention -> "@${span.name}"
            is MentionSpan.Link -> span.text
        }
    }
