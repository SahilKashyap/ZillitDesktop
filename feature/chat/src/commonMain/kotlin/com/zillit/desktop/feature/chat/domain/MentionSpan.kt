package com.zillit.desktop.feature.chat.domain

import com.zillit.desktop.core.common.LinkSpan
import com.zillit.desktop.core.common.linkSpans

/**
 * One slice of a message body: plain words, a tagged crew member, or a link.
 *
 * The wire writes a tag as `@{{userId}}` (web `cncUtil.js:44` — the `@` is
 * optional in the regex because some writers drop it). The reader never sees
 * the id: known ids render as `@Full Name`, clickable; an id the crew list
 * cannot place stays as raw text rather than becoming a dead link.
 *
 * Links are [LinkSpan]'s, carried over so a bubble can draw tags and
 * addresses in one pass; what counts as an address is settled there.
 */
sealed interface MentionSpan {
    data class Words(val text: String) : MentionSpan

    data class Mention(val userId: String, val name: String) : MentionSpan

    /** [text] as it was typed; [url] is what opening it means, scheme and all. */
    data class Link(val url: String, val text: String) : MentionSpan
}

private val MENTION = Regex("@?\\{\\{([a-zA-Z0-9]+)\\}\\}")

fun mentionSpans(body: String, nameOf: (String) -> String?): List<MentionSpan> {
    if ("{{" !in body) return wordsAndLinks(body)

    val spans = mutableListOf<MentionSpan>()
    var consumed = 0
    MENTION.findAll(body).forEach { match ->
        val name = nameOf(match.groupValues[1]) ?: return@forEach
        if (match.range.first > consumed) {
            spans += wordsAndLinks(body.substring(consumed, match.range.first))
        }
        spans += MentionSpan.Mention(match.groupValues[1], name)
        consumed = match.range.last + 1
    }
    if (consumed < body.length) spans += wordsAndLinks(body.substring(consumed))
    return spans.ifEmpty { listOf(MentionSpan.Words(body)) }
}

/**
 * [words] split around every address in it, as mention spans.
 *
 * What an address *is* lives in `core:common` — the call's own chat lights up
 * the same ones, and one regex is what keeps the two surfaces agreeing.
 */
private fun wordsAndLinks(words: String): List<MentionSpan> =
    linkSpans(words).map { span ->
        when (span) {
            is LinkSpan.Words -> MentionSpan.Words(span.text)
            is LinkSpan.Link -> MentionSpan.Link(url = span.url, text = span.text)
        }
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

/**
 * The body a listing or a banner shows: tags spelt `@Name`, and a tag whose
 * id the crew list cannot place spelt [unknown] — a preview reading
 * `@{{678108ba34a30d92ea0…}}` is noise nobody can read past, where the bubble
 * can afford to leave the raw token alone.
 */
fun previewMentions(body: String, nameOf: (String) -> String?, unknown: String): String =
    flattenMentions(body, nameOf).replace(MENTION) { unknown }
