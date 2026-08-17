package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.domain.CallMedia
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.LinkQuality
import com.zillit.desktop.feature.calls.domain.MediaPeer
import com.zillit.desktop.feature.calls.ui.buildTiles
import com.zillit.desktop.feature.calls.ui.columnsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Roster rows and engine uids, reconciled into the faces on the stage. */
class CallTilesTest {

    private fun session(vararg people: CallParticipant) = CallSession(
        callUuid = "u1",
        selfUserId = "me",
        selfDeviceId = "my-device",
        participants = people.toList(),
    )

    private fun person(
        id: String,
        uid: String = "",
        status: CallStatus = CallStatus.InCall,
        name: String = id,
    ) = CallParticipant(userId = id, agoraUid = uid, status = status, name = name)

    @Test
    fun `we come first, and we are always on the stage`() {
        val tiles = buildTiles(session(), CallMedia(), "Sahil", micMuted = false, cameraOn = false)
        assertEquals(1, tiles.size)
        assertTrue(tiles.first().isSelf)
        assertEquals("Sahil", tiles.first().name)
    }

    @Test
    fun `our own mute comes from the button, not from the media stack`() {
        // The stack reports other people's tracks; sourcing our own badge from
        // it would lag the button the user just pressed.
        val tiles = buildTiles(session(), CallMedia(), "Sahil", micMuted = true, cameraOn = false)
        assertTrue(tiles.first().media?.audioMuted == true)
    }

    @Test
    fun `a roster row with a uid picks up that stream's state`() {
        val media = CallMedia(
            selfUid = 1,
            peers = mapOf(7 to MediaPeer(7, audioMuted = true, quality = LinkQuality.Poor)),
            speaking = setOf(7),
        )
        val tiles = buildTiles(session(person("vivek", uid = "7")), media, "Me", false, false)
        val vivek = tiles.single { it.userId == "vivek" }
        assertNotNull(vivek.media)
        assertTrue(vivek.media!!.audioMuted)
        assertTrue(vivek.media!!.speaking)
        assertEquals(LinkQuality.Poor, vivek.media!!.quality)
    }

    @Test
    fun `a roster row with no uid and no stream shows identity only`() {
        val tiles = buildTiles(session(person("vivek")), CallMedia(), "Me", false, false)
        // No badge at all beats a badge on the wrong face.
        assertNull(tiles.single { it.userId == "vivek" }.media)
    }

    @Test
    fun `one unbound row and one orphan stream must be each other`() {
        // The 1:1 case, which is routine: the invite carried no agora_uid and
        // the SDK issued one, so nothing in the payload connects them.
        val media = CallMedia(selfUid = 1, peers = mapOf(9 to MediaPeer(9, audioMuted = true)))
        val tiles = buildTiles(session(person("vivek")), media, "Me", false, false)
        val vivek = tiles.single { it.userId == "vivek" }
        assertEquals(9, vivek.uid)
        assertTrue(vivek.media?.audioMuted == true)
    }

    @Test
    fun `two unbound rows and two orphan streams are never guessed at`() {
        val media = CallMedia(
            selfUid = 1,
            peers = mapOf(9 to MediaPeer(9, audioMuted = true), 10 to MediaPeer(10)),
        )
        val tiles = buildTiles(session(person("a"), person("b")), media, "Me", false, false)
        // A wrong binding puts one person's speaking ring on another's face.
        assertNull(tiles.single { it.userId == "a" }.media)
        assertNull(tiles.single { it.userId == "b" }.media)
    }

    @Test
    fun `a stream nobody claims still gets a face`() {
        // Guests join by link and never get a call_users row. Their video is
        // already on the stage, so omitting them shows a person who "does not
        // exist" in the UI.
        val media = CallMedia(selfUid = 1, peers = mapOf(77 to MediaPeer(77)))
        val tiles = buildTiles(session(person("vivek", uid = "7")), media, "Me", false, false)
        val guest = tiles.single { it.uid == 77 }
        assertEquals("Guest", guest.name)
        assertNotNull(guest.media)
    }

    @Test
    fun `people who declined or left are off the stage`() {
        val tiles = buildTiles(
            session(
                person("gone", status = CallStatus.Left),
                person("no", status = CallStatus.Declined),
                person("here", status = CallStatus.InCall),
            ),
            CallMedia(), "Me", false, false,
        )
        assertEquals(setOf("me", "here"), tiles.map { it.userId }.toSet())
    }

    @Test
    fun `someone still ringing is shown, quietly`() {
        val tiles = buildTiles(
            session(person("vivek", status = CallStatus.Ringing)),
            CallMedia(), "Me", false, false,
        )
        val vivek = tiles.single { it.userId == "vivek" }
        assertEquals(CallStatus.Ringing, vivek.presence)
        assertNull(vivek.media)
    }

    @Test
    fun `tiles are keyed by person, so a late uid is adopted rather than duplicated`() {
        val before = buildTiles(session(person("vivek")), CallMedia(), "Me", false, false)
        val after = buildTiles(
            session(person("vivek", uid = "7")),
            CallMedia(selfUid = 1, peers = mapOf(7 to MediaPeer(7))),
            "Me", false, false,
        )
        assertEquals(before.map { it.key }, after.map { it.key })
    }

    @Test
    fun `order never depends on who is speaking`() {
        val roster = session(person("a", uid = "7"), person("b", uid = "8"))
        val quiet = CallMedia(selfUid = 1, peers = mapOf(7 to MediaPeer(7), 8 to MediaPeer(8)))
        val loud = quiet.copy(speaking = setOf(8))
        assertEquals(
            buildTiles(roster, quiet, "Me", false, false).map { it.key },
            buildTiles(roster, loud, "Me", false, false).map { it.key },
        )
    }

    @Test
    fun `no session means no stage`() {
        assertTrue(buildTiles(null, CallMedia(), "Me", false, false).isEmpty())
    }

    @Test
    fun `columns grow with the crowd`() {
        assertEquals(1, columnsFor(1))
        assertEquals(2, columnsFor(2))
        assertEquals(2, columnsFor(4))
        assertEquals(3, columnsFor(5))
        assertEquals(3, columnsFor(9))
        assertEquals(4, columnsFor(10))
        assertEquals(4, columnsFor(12))
    }

    @Test
    fun `a muted microphone silences our own speaking ring`() {
        // Otherwise the stack's last reading keeps the ring lit on a muted mic.
        val media = CallMedia(selfUid = 5, speaking = setOf(5))
        val tiles = buildTiles(session(), media, "Me", micMuted = true, cameraOn = false)
        assertFalse(tiles.first().media?.speaking == true)
    }
}
