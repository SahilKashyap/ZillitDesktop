package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.domain.NoticeDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoticeDraftTest {

    @Test
    fun `an empty draft cannot be sent`() {
        assertFalse(NoticeDraft().canSend)
    }

    @Test
    fun `whitespace alone is not a notice`() {
        // The board is read by a whole crew; a blank card is noise.
        assertFalse(NoticeDraft("   \n\t ").canSend)
    }

    @Test
    fun `ordinary text can be sent`() {
        assertTrue(NoticeDraft("Crew call moved to 0600").canSend)
    }

    @Test
    fun `text is trimmed before sending, not before counting`() {
        val draft = NoticeDraft("  hello  ")

        assertEquals("hello", draft.trimmed)
        assertEquals(9, draft.length, "the counter reflects what is typed, including spaces")
    }

    @Test
    fun `the backend limit is enforced while typing`() {
        val atLimit = NoticeDraft("x".repeat(NoticeDraft.MAX_LENGTH))
        val over = NoticeDraft("x".repeat(NoticeDraft.MAX_LENGTH + 1))

        assertTrue(atLimit.canSend)
        assertFalse(over.canSend, "the server would reject this; say so while typing")
        assertTrue(over.isOverLimit)
    }

    @Test
    fun `the counter appears only near the limit`() {
        assertFalse(NoticeDraft("short").showsCounter)
        assertTrue(NoticeDraft("x".repeat(NoticeDraft.MAX_LENGTH - 10)).showsCounter)
    }

    @Test
    fun `remaining goes negative rather than clamping`() {
        // A user 40 characters over needs to know how much to cut.
        assertEquals(-40, NoticeDraft("x".repeat(NoticeDraft.MAX_LENGTH + 40)).remaining)
    }
}
