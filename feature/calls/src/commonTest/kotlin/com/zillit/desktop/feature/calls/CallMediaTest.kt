package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.domain.CallEngineEvent
import com.zillit.desktop.feature.calls.domain.CallMedia
import com.zillit.desktop.feature.calls.domain.EngineConnection
import com.zillit.desktop.feature.calls.domain.LinkQuality
import com.zillit.desktop.feature.calls.domain.reduce
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The media picture, folded from engine events. */
class CallMediaTest {

    private fun media(vararg events: CallEngineEvent): CallMedia =
        events.fold(CallMedia()) { state, event -> state.reduce(event) }

    @Test
    fun `joining records the channel, our uid and a connected line`() {
        val state = media(CallEngineEvent.Joined("chan", 42))
        assertEquals("chan", state.channel)
        assertEquals(42, state.selfUid)
        assertEquals(EngineConnection.Connected, state.connection)
    }

    @Test
    fun `leaving forgets everything`() {
        val state = media(
            CallEngineEvent.Joined("chan", 42),
            CallEngineEvent.PeerJoined(7),
            CallEngineEvent.Left("chan"),
        )
        assertEquals(CallMedia(), state)
    }

    @Test
    fun `an event for an unseen uid creates that peer rather than being dropped`() {
        // The roster snapshot can be older than the channel, so the first
        // thing heard about someone is often a mute, not a join.
        val state = media(CallEngineEvent.PeerAudioMuted(7, muted = true))
        assertTrue(state.peers.getValue(7).audioMuted)
    }

    @Test
    fun `uid 0 never becomes a peer`() {
        // 0 is both the engine's self marker and the roster's "never told us";
        // admitting it would make this device a stranger in its own call.
        val state = media(CallEngineEvent.PeerJoined(0), CallEngineEvent.PeerAudioMuted(0, true))
        assertTrue(state.peers.isEmpty())
    }

    @Test
    fun `our own uid never becomes a peer`() {
        val state = media(CallEngineEvent.Joined("chan", 42), CallEngineEvent.PeerJoined(42))
        assertTrue(state.peers.isEmpty())
    }

    @Test
    fun `video muted reads as video off, not as a missing peer`() {
        val state = media(
            CallEngineEvent.PeerVideoMuted(7, muted = false),
            CallEngineEvent.PeerVideoMuted(7, muted = true),
        )
        assertFalse(state.peers.getValue(7).videoOn)
    }

    @Test
    fun `a peer leaving takes their speaking flag with them`() {
        val state = media(
            CallEngineEvent.PeerJoined(7),
            CallEngineEvent.ActiveSpeakers(listOf(7)),
            CallEngineEvent.PeerLeft(7),
        )
        assertTrue(state.peers.isEmpty())
        assertTrue(state.speaking.isEmpty())
    }

    @Test
    fun `network quality for uid 0 is ours, not a stranger's`() {
        val state = media(CallEngineEvent.NetworkQuality(0, tx = 1, rx = 1))
        assertEquals(LinkQuality.Excellent, state.selfQuality)
        assertTrue(state.peers.isEmpty())
    }

    @Test
    fun `link quality takes the worse leg`() {
        // A call is only as good as its weaker direction; reporting the better
        // one would call a line healthy while nobody can hear you.
        assertEquals(LinkQuality.Bad, LinkQuality.ofAgora(tx = 1, rx = 4))
        assertEquals(LinkQuality.Bad, LinkQuality.ofAgora(tx = 4, rx = 1))
        assertEquals(LinkQuality.Excellent, LinkQuality.ofAgora(tx = 1, rx = 1))
        assertEquals(LinkQuality.Unknown, LinkQuality.ofAgora(tx = 0, rx = 0))
    }

    @Test
    fun `only the bad readings count as trouble`() {
        assertFalse(LinkQuality.Excellent.isTrouble)
        assertFalse(LinkQuality.Good.isTrouble)
        assertFalse(LinkQuality.Unknown.isTrouble)
        assertTrue(LinkQuality.Poor.isTrouble)
        assertTrue(LinkQuality.Down.isTrouble)
    }

    @Test
    fun `an unchanged network reading produces an equal state, so nothing recomposes`() {
        val once = media(CallEngineEvent.NetworkQuality(0, 1, 1))
        val twice = once.reduce(CallEngineEvent.NetworkQuality(0, 1, 1))
        assertEquals(once, twice)
    }

    @Test
    fun `a failure leaves the picture alone for the coordinator to act on`() {
        val before = media(CallEngineEvent.Joined("chan", 42))
        assertEquals(before, before.reduce(CallEngineEvent.Failed("boom")))
    }

    @Test
    fun `speakers replace rather than accumulate`() {
        val state = media(
            CallEngineEvent.ActiveSpeakers(listOf(7, 8)),
            CallEngineEvent.ActiveSpeakers(listOf(9)),
        )
        assertEquals(setOf(9), state.speaking)
        // Speaking is a reading about someone, not evidence they exist: the
        // roster and the peer map are populated by joins alone.
        assertNull(state.peers[9])
    }
}
