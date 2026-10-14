package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.domain.ChatComposerRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The composer's refusals, held to the other clients' lines.
 *
 * Each number here is a rule some other client already enforces; a desktop
 * that drifts either way is a desktop that sends what a phone would not, or
 * refuses what a phone would carry.
 */
class ChatComposerRulesTest {

    /** 8000 is allowed; 8001 is not. */
    @Test
    fun `the text ceiling is 8000 characters, inclusive`() {
        assertFalse(ChatComposerRules.bodyTooLong("a".repeat(8000)))
        assertTrue(ChatComposerRules.bodyTooLong("a".repeat(8001)))
    }

    /** An ordinary line is never the thing that gets refused. */
    @Test
    fun `an ordinary line passes`() {
        assertFalse(ChatComposerRules.bodyTooLong("call time moved to 6am, gate 3"))
    }

    /**
     * No 70 MB cap any more: a 500 MB rushes file is carried (a video that
     * size is compressed on upload, not refused). The only ceiling left is
     * what one file can weigh and still be read whole.
     */
    @Test
    fun `a file far past the old 70 MB cap is carried`() {
        assertNull(ChatComposerRules.refuse("rushes.mp4", 70L * 1024 * 1024 + 1))
        assertNull(ChatComposerRules.refuse("rushes.mp4", 500L * 1024 * 1024))
    }

    /** At the readable ceiling a file is carried; a byte more cannot be read, and is refused. */
    @Test
    fun `the readable ceiling is carried, and one byte more is not`() {
        val ceiling = ChatComposerRules.MAX_ATTACHMENT_BYTES
        assertNull(ChatComposerRules.refuse("rushes.mp4", ceiling))
        assertEquals(
            ChatComposerRules.ATTACHMENT_TOO_LARGE,
            ChatComposerRules.refuse("rushes.mp4", ceiling + 1),
        )
    }

    /**
     * An executable is refused by name whatever its size — and in any case,
     * which the web's own `=== 'exe'` comparison would let through.
     */
    @Test
    fun `an executable is refused, whatever its case`() {
        assertEquals(
            ChatComposerRules.ATTACHMENT_REFUSED_TYPE,
            ChatComposerRules.refuse("setup.exe", 1024),
        )
        assertEquals(
            ChatComposerRules.ATTACHMENT_REFUSED_TYPE,
            ChatComposerRules.refuse("SETUP.EXE", 1024),
        )
    }

    /** Size is the reason the sender can act on, so it is the one they get. */
    @Test
    fun `an oversize executable is refused for its size`() {
        assertEquals(
            ChatComposerRules.ATTACHMENT_TOO_LARGE,
            ChatComposerRules.refuse("setup.exe", ChatComposerRules.MAX_ATTACHMENT_BYTES + 1),
        )
    }

    /** The everyday file: a name with a dot in it and nothing wrong. */
    @Test
    fun `an ordinary file passes`() {
        assertNull(ChatComposerRules.refuse("call.sheet.day12.pdf", 2L * 1024 * 1024))
        assertNull(ChatComposerRules.refuse("noextension", 512))
    }
}
