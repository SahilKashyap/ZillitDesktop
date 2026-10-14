package com.zillit.desktop.feature.chat

import com.zillit.desktop.core.localization.LabelDictionary
import com.zillit.desktop.core.localization.LabelKind
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.feature.chat.domain.asServerSentence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A group's "created" line arrives as an ordinary message from the creator
 * whose words are a label key, so the listing read
 * `Sahil: cnc_new_chat_group_created_ch…` (DN6, 2026-10-14). Translating it
 * must not cost anybody their own words.
 */
class ServerSentenceTest {

    @BeforeTest
    fun install() {
        Labels.install(
            MutableStateFlow(
                LabelDictionary.Empty
                    .with(
                        LabelKind.Labels,
                        mapOf(
                            "cnc_new_chat_group_created_chat" to "A new chat group has been created",
                            "approved" to "Approved",
                        ),
                    )
                    .with(LabelKind.Messages, mapOf("access_denied" to "You cannot do that")),
            ),
        )
    }

    @AfterTest
    fun uninstall() = Labels.reset()

    @Test
    fun `a system line written as a key reads as its sentence`() {
        assertEquals(
            "A new chat group has been created",
            "cnc_new_chat_group_created_chat".asServerSentence(),
        )
    }

    @Test
    fun `the messages table is consulted first, as a notice does`() {
        assertEquals("You cannot do that", "access_denied".asServerSentence())
    }

    @Test
    fun `words somebody typed are never looked up`() {
        assertEquals("hello there", "hello there".asServerSentence())
        // Several words, one of which is a key: still untouched, because a
        // sentence is not a key and never reaches the dictionary.
        assertEquals("is this approved", "is this approved".asServerSentence())
    }

    @Test
    fun `a one-word message the dictionary does not know is left exactly as typed`() {
        // The whole reason this uses `exact` rather than `localised`: the
        // latter humanises a miss, and would hand back "Ok" and "Snake Case"
        // for words a person actually wrote.
        assertEquals("ok", "ok".asServerSentence())
        assertEquals("snake_case", "snake_case".asServerSentence())
        assertEquals("👍", "👍".asServerSentence())
    }

    @Test
    fun `an empty body stays empty`() {
        assertEquals("", "".asServerSentence())
        assertEquals("   ", "   ".asServerSentence())
    }
}
