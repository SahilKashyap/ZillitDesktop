package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.home.data.NOTICE_UNREADABLE
import com.zillit.desktop.feature.home.data.noticeBodyText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one rule for reading a board body, now that both paths share it.
 *
 * It existed only on the REST path, as a private method, while the socket
 * path kept a two-line copy in the wiring that decrypted or gave up. So the
 * same post read two different ways depending on how it reached the app: the
 * board drew the words and the notification raised for it drew
 * "[This message could not be decrypted]" over them (user screenshot,
 * 2026-10-22).
 */
class NoticeBodyTextTest {

    private val failing: (String) -> ZillitResult<String> =
        { ZillitResult.Failure(ZillitError.Crypto("payload is not valid hex")) }

    private val working: (String) -> ZillitResult<String> =
        { ZillitResult.Success("decrypted:$it") }

    /** 32 hex characters: one AES block, the shortest thing that could be ciphertext. */
    private val oneBlock = "a".repeat(32)

    @Test
    fun `a body that was never encrypted is its own text`() {
        assertEquals("Call time moved to 0630", noticeBodyText("Call time moved to 0630", failing))
        // Hex, but not whole blocks — so it cannot be this wire's ciphertext.
        assertEquals("abc123", noticeBodyText("abc123", failing))
    }

    @Test
    fun `a blank body stays blank rather than becoming a placeholder`() {
        assertEquals("", noticeBodyText("", failing))
        assertEquals("", noticeBodyText("   ", failing))
    }

    @Test
    fun `a well-shaped body is decrypted`() {
        assertEquals("decrypted:$oneBlock", noticeBodyText(oneBlock, working))
    }

    /** Well-shaped and still unreadable is a broken post, and says so. */
    @Test
    fun `a well-shaped body that will not decrypt is the placeholder`() {
        assertEquals(NOTICE_UNREADABLE, noticeBodyText(oneBlock, failing))
    }

    /** Never the ciphertext: a hex blob on screen reads as a corrupted post. */
    @Test
    fun `a failure never shows the ciphertext`() {
        assertEquals(NOTICE_UNREADABLE, noticeBodyText(oneBlock + oneBlock, failing))
    }
}
