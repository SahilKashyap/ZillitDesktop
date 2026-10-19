package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.taggedBody
import com.zillit.desktop.feature.chat.ui.ChatMentionPickerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tagging a crew member from the composer.
 *
 * The composer leaves a readable `@Aisha Khan` in the draft and the wire
 * carries `@{{userId}}`, so the whole feature turns on one translation step
 * being exactly right: too eager and an email address becomes a tag, too shy
 * and the tag reaches the other clients as plain words nobody is notified
 * about.
 */
class ChatTaggingTest {

    private val aisha = CrewContact(userId = "u1", fullName = "Aisha Khan")
    private val aishaAlone = CrewContact(userId = "u2", fullName = "Aisha")
    private val sunil = CrewContact(userId = "u3", fullName = "Sunil k Gautam")
    private val crew = listOf(aisha, aishaAlone, sunil)

    // -- the draft on its way to the wire ------------------------------------

    @Test
    fun `a tagged name becomes the id the other clients resolve`() {
        assertEquals("ask @{{u1}} about it", taggedBody("ask @Aisha Khan about it", crew))
    }

    @Test
    fun `the longest name wins, so a surname is not left as a loose word`() {
        // "@Aisha Khan" must not read as a tag of "Aisha" followed by "Khan",
        // which is what a shortest-first walk would produce.
        assertEquals("@{{u1}}", taggedBody("@Aisha Khan", crew))
        assertEquals("@{{u2}} only", taggedBody("@Aisha only", crew))
    }

    @Test
    fun `a name is matched however it was capitalised`() {
        // People type a name as they think of it, not as the directory spells it.
        assertEquals("@{{u1}}", taggedBody("@aisha khan", crew))
    }

    @Test
    fun `several tags in one line all become ids`() {
        assertEquals(
            "@{{u1}} and @{{u3}} on channel 2",
            taggedBody("@Aisha Khan and @Sunil k Gautam on channel 2", crew),
        )
    }

    @Test
    fun `an at sign that names nobody is left exactly as typed`() {
        // @here, a handle we have no one for, a price — all of them words.
        assertEquals("@here in 5", taggedBody("@here in 5", crew))
        assertEquals("@Nobody Known", taggedBody("@Nobody Known", crew))
    }

    @Test
    fun `an email address is not a tag even when the name would fit`() {
        // The `@` has a letter in front of it, so it is an address. This is
        // the case that makes the word test non-negotiable.
        assertEquals("mail sahil@zillit.com", taggedBody("mail sahil@zillit.com", crew))
        assertEquals("write to aisha@Aisha Khan", taggedBody("write to aisha@Aisha Khan", crew))
    }

    @Test
    fun `a body with no at sign, and one with no crew to match, pass through`() {
        assertEquals("rolling at 8", taggedBody("rolling at 8", crew))
        assertEquals("@Aisha Khan", taggedBody("@Aisha Khan", emptyList()))
    }

    @Test
    fun `an unmatched at sign does not swallow the tag after it`() {
        // The scan has to carry on past a bare `@` rather than from it, or the
        // second tag is never reached.
        assertEquals("@ @{{u1}}", taggedBody("@ @Aisha Khan", crew))
    }

    // -- the suggestions the composer shows ----------------------------------

    @Test
    fun `typing an at opens the picker and a space-separated word ranks it`() {
        val picker = ChatMentionPickerState()
        picker.sync("ask @ais", crew)

        assertTrue(picker.isOpen)
        assertEquals("ais", picker.query)
        assertEquals(listOf("Aisha Khan", "Aisha"), picker.matches.map { it.fullName })
        assertEquals(aisha, picker.selectedContact())
    }

    @Test
    fun `the picker stays shut for a body with no tag being typed`() {
        val picker = ChatMentionPickerState()
        picker.sync("rolling at 8", crew)

        assertFalse(picker.isOpen)
        assertNull(picker.selectedContact())
    }

    @Test
    fun `the picker stays shut for an email address`() {
        val picker = ChatMentionPickerState()
        picker.sync("mail me at sahil@zillit", crew)

        assertFalse(picker.isOpen)
    }

    @Test
    fun `the arrows walk the rows and wrap around`() {
        val picker = ChatMentionPickerState()
        picker.sync("@ais", crew)

        picker.moveDown()
        assertEquals(aishaAlone, picker.selectedContact())
        picker.moveDown()
        assertEquals(aisha, picker.selectedContact(), "down from the last row returns to the first")
        picker.moveUp()
        assertEquals(aishaAlone, picker.selectedContact(), "up from the first row reaches the last")
    }

    @Test
    fun `narrowing the query lights the top row again`() {
        // A narrower query reorders the rows, so an index remembered across
        // that reorder would complete somebody the writer never looked at.
        val picker = ChatMentionPickerState()
        picker.sync("@ais", crew)
        picker.moveDown()
        assertEquals(aishaAlone, picker.selectedContact())

        picker.sync("@aisha k", crew)
        assertEquals(aisha, picker.selectedContact())
    }

    @Test
    fun `escape shuts the list for this token and the next one reopens it`() {
        val picker = ChatMentionPickerState()
        picker.sync("@ais", crew)
        picker.dismiss()
        assertFalse(picker.isOpen, "Enter belongs to sending again")

        // Still the same token, even after a recomposition re-syncs it.
        picker.sync("@ais", crew)
        assertFalse(picker.isOpen)

        picker.sync("@aish", crew)
        assertTrue(picker.isOpen, "a fresh token earns a fresh chance")
    }
}
