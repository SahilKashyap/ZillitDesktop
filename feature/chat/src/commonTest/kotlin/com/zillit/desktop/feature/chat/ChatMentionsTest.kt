package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.domain.MentionSpan
import com.zillit.desktop.feature.chat.domain.flattenMentions
import com.zillit.desktop.feature.chat.domain.mentionSpans
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wire's tag is `@{{userId}}` (web `cncUtil.js:44`); the reader sees
 * `@Full Name`, never the id — and never a dead link for an unknown one.
 */
class ChatMentionsTest {

    private val names = mapOf("67fcd7fc2ae929576c067820" to "Aisha Khan", "abc123" to "Ravi")

    private fun nameOf(id: String) = names[id]

    @Test
    fun `a tagged id becomes a named span between its words`() {
        val spans = mentionSpans("morning @{{abc123}} — call sheet is up", ::nameOf)

        assertEquals(
            listOf(
                MentionSpan.Words("morning "),
                MentionSpan.Mention("abc123", "Ravi"),
                MentionSpan.Words(" — call sheet is up"),
            ),
            spans,
        )
    }

    @Test
    fun `an unknown id stays raw text rather than becoming a dead link`() {
        val spans = mentionSpans("ping @{{nobody99}}", ::nameOf)
        assertEquals(listOf(MentionSpan.Words("ping @{{nobody99}}")), spans)
    }

    @Test
    fun `the flattened body reads with names, for previews and copies`() {
        assertEquals(
            "morning @Ravi and @Aisha Khan",
            flattenMentions("morning @{{abc123}} and @{{67fcd7fc2ae929576c067820}}", ::nameOf),
        )
    }

    @Test
    fun `the at is optional on the wire, and a plain body passes through`() {
        assertEquals(
            listOf(MentionSpan.Mention("abc123", "Ravi")),
            mentionSpans("{{abc123}}", ::nameOf),
        )
        assertEquals(listOf(MentionSpan.Words("no tags here")), mentionSpans("no tags here", ::nameOf))
    }
}
