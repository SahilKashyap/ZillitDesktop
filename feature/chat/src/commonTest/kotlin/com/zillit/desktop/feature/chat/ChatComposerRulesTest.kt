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

    /** 2000 is allowed; 2001 is not (`ChatFooterCnc.jsx:202` reads `> 2000`). */
    @Test
    fun `the text ceiling is 2000 characters, inclusive`() {
        assertFalse(ChatComposerRules.bodyTooLong("a".repeat(2000)))
        assertTrue(ChatComposerRules.bodyTooLong("a".repeat(2001)))
    }

    /** An ordinary line is never the thing that gets refused. */
    @Test
    fun `an ordinary line passes`() {
        assertFalse(ChatComposerRules.bodyTooLong("call time moved to 6am, gate 3"))
    }

    /**
     * 70 MB exactly is carried; a byte more is not. The web keeps a file at
     * `size / 1024 / 1024 <= 70` (`ChatFooterCnc.jsx:509-511`), so the
     * boundary belongs on the allowed side.
     */
    @Test
    fun `seventy megabytes is carried, and one byte more is not`() {
        val ceiling = 70L * 1024 * 1024
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
            ChatComposerRules.refuse("setup.exe", 80L * 1024 * 1024),
        )
    }

    /** The everyday file: a name with a dot in it and nothing wrong. */
    @Test
    fun `an ordinary file passes`() {
        assertNull(ChatComposerRules.refuse("call.sheet.day12.pdf", 2L * 1024 * 1024))
        assertNull(ChatComposerRules.refuse("noextension", 512))
    }
}
