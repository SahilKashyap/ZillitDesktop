package com.zillit.desktop.feature.chat.domain

/**
 * One slice of a message body: plain words, or a tagged crew member.
 *
 * The wire writes a tag as `@{{userId}}` (web `cncUtil.js:44` — the `@` is
 * optional in the regex because some writers drop it). The reader never sees
 * the id: known ids render as `@Full Name`, clickable; an id the crew list
 * cannot place stays as raw text rather than becoming a dead link.
 */
sealed interface MentionSpan {
    data class Words(val text: String) : MentionSpan

    data class Mention(val userId: String, val name: String) : MentionSpan
}

private val MENTION = Regex("@?\\{\\{([a-zA-Z0-9]+)\\}\\}")

fun mentionSpans(body: String, nameOf: (String) -> String?): List<MentionSpan> {
    if ("{{" !in body) return listOf(MentionSpan.Words(body))

    val spans = mutableListOf<MentionSpan>()
    var consumed = 0
    MENTION.findAll(body).forEach { match ->
        val name = nameOf(match.groupValues[1]) ?: return@forEach
        if (match.range.first > consumed) {
            spans += MentionSpan.Words(body.substring(consumed, match.range.first))
        }
        spans += MentionSpan.Mention(match.groupValues[1], name)
        consumed = match.range.last + 1
    }
    if (consumed < body.length) spans += MentionSpan.Words(body.substring(consumed))
    return spans.ifEmpty { listOf(MentionSpan.Words(body)) }
}

/** The body as plain text with tags spelt `@Name` — for previews and copies. */
fun flattenMentions(body: String, nameOf: (String) -> String?): String =
    mentionSpans(body, nameOf).joinToString("") { span ->
        when (span) {
            is MentionSpan.Words -> span.text
            is MentionSpan.Mention -> "@${span.name}"
        }
    }
