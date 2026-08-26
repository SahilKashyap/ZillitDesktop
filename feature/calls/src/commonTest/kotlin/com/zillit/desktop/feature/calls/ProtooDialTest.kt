package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.protoo.CLOSE_REPLACED_BY_OTHER_DEVICE
import com.zillit.desktop.feature.calls.data.protoo.CLOSE_SERVER_SHUTDOWN
import com.zillit.desktop.feature.calls.data.protoo.RETRY_MAX_MILLIS
import com.zillit.desktop.feature.calls.data.protoo.RETRY_MIN_MILLIS
import com.zillit.desktop.feature.calls.data.protoo.isRecoverableProtooClose
import com.zillit.desktop.feature.calls.data.protoo.isTerminalProtooClose
import com.zillit.desktop.feature.calls.data.protoo.normaliseSfuHost
import com.zillit.desktop.feature.calls.data.protoo.protooDialUrl
import com.zillit.desktop.feature.calls.data.protoo.protooRetryDelayMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The rules around dialling the SFU, each one a mistake the phones already made. */
class ProtooDialTest {

    @Test
    fun `a host is stripped to host and port, whatever it arrives wrapped in`() {
        assertEquals("sfu.zillit.com", normaliseSfuHost("sfu.zillit.com"))
        assertEquals("sfu.zillit.com", normaliseSfuHost("wss://sfu.zillit.com"))
        assertEquals("sfu.zillit.com", normaliseSfuHost("https://sfu.zillit.com/"))
        assertEquals("sfu.zillit.com", normaliseSfuHost("https://sfu.zillit.com/some/path"))
        // A port is a real deployment detail and must survive.
        assertEquals("sfu.zillit.com:4443", normaliseSfuHost("wss://sfu.zillit.com:4443/"))
        // Seen in the wild.
        assertEquals("sfu.zillit.com", normaliseSfuHost("wss://https://sfu.zillit.com"))
        assertEquals("sfu.zillit.com", normaliseSfuHost("  sfu.zillit.com  "))
    }

    /**
     * The colon in the peerId is structural — it separates user from device —
     * and every other client sends it raw. Encoding it would address a peer
     * nobody else is talking to.
     */
    @Test
    fun `the dial url keeps the peerId colon unencoded`() {
        val url = protooDialUrl("wss://sfu.zillit.com", roomId = "room-1", peerId = "user-9:device-3")
        assertEquals("wss://sfu.zillit.com/?roomId=room-1&peerId=user-9:device-3", url)
        assertFalse("%3A" in url, "the peerId colon must not be percent-encoded")
    }

    @Test
    fun `the sfu token is appended only when there is one`() {
        val tokenless = protooDialUrl("sfu.zillit.com", "r", "u:d", sfuToken = "")
        assertFalse("token=" in tokenless, "an empty token is a tokenless dial, not token=")

        val withToken = protooDialUrl("sfu.zillit.com", "r", "u:d", sfuToken = "abc123")
        assertTrue(withToken.endsWith("&token=abc123"), withToken)
    }

    /**
     * Only the eviction is terminal.
     *
     * This reverses an earlier rule that also treated 4000 as terminal. A bare
     * 4000 is protoo-server closing one peer on purpose — a deploy, a session
     * being recycled — and never the other-device eviction; iOS says so
     * outright, from a production room where reading it that way tore down
     * live calls. Ending on it cost the user a working call that a redial
     * would have recovered.
     */
    @Test
    fun `only the other-device eviction is terminal`() {
        assertTrue(isTerminalProtooClose(CLOSE_REPLACED_BY_OTHER_DEVICE, ""))
        assertFalse(isTerminalProtooClose(CLOSE_SERVER_SHUTDOWN, ""))
    }

    /**
     * The code is not always preserved — some platforms normalise custom
     * 4000-range codes away — so the reason text has to carry the same fact.
     */
    @Test
    fun `the eviction is recognised from its reason when the code is lost`() {
        assertTrue(isTerminalProtooClose(1000, "replaced-by-other-device"))
        assertTrue(
            isTerminalProtooClose(1000, "REPLACED-BY-OTHER-DEVICE"),
            "must be case-insensitive",
        )
    }

    @Test
    fun `a recycled peer is recoverable, not terminal`() {
        assertTrue(isRecoverableProtooClose(CLOSE_SERVER_SHUTDOWN, ""))
        for (reason in listOf(
            "closed by protoo-server",
            "session replaced",
            "duplicate session",
            "peer reconnected",
        )) {
            assertTrue(isRecoverableProtooClose(1000, reason), reason)
            assertTrue(
                isRecoverableProtooClose(1000, reason.uppercase()),
                "must be case-insensitive: $reason",
            )
            assertFalse(isTerminalProtooClose(1000, reason), "no longer terminal: $reason")
        }
    }

    @Test
    fun `an eviction is never also recoverable`() {
        // The two questions must not both answer yes, or the caller's order of
        // asking would decide whether this device fights its own other one.
        assertFalse(isRecoverableProtooClose(CLOSE_REPLACED_BY_OTHER_DEVICE, ""))
        assertFalse(isRecoverableProtooClose(1000, "replaced-by-other-device"))
    }

    @Test
    fun `an ordinary drop is neither terminal nor a named recovery`() {
        assertFalse(isTerminalProtooClose(1006, "abnormal closure"))
        assertFalse(isTerminalProtooClose(1001, "going away"))
        assertFalse(isTerminalProtooClose(0, ""))
        // Not "recoverable" in the named sense — an abrupt drop retries by the
        // ordinary backoff path, which is a different question.
        assertFalse(isRecoverableProtooClose(1006, "abnormal closure"))
    }

    @Test
    fun `the retry delay doubles to a ceiling and never exceeds it`() {
        assertEquals(RETRY_MIN_MILLIS, protooRetryDelayMillis(0))
        assertEquals(1_600L, protooRetryDelayMillis(1))
        assertEquals(3_200L, protooRetryDelayMillis(2))
        assertEquals(RETRY_MAX_MILLIS, protooRetryDelayMillis(3))
        // A long outage must not overflow into a negative or absurd wait.
        for (attempt in 4..600) {
            assertEquals(RETRY_MAX_MILLIS, protooRetryDelayMillis(attempt), "attempt $attempt")
        }
    }
}
