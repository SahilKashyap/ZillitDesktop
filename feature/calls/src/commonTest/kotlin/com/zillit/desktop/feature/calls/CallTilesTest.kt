package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.answeredByMedia
import com.zillit.desktop.feature.calls.data.protoo.mediasoupUidOf
import com.zillit.desktop.feature.calls.domain.CallDirectoryEntry
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallMedia
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.LinkQuality
import com.zillit.desktop.feature.calls.domain.MediaPeer
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallDirection
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
    fun `an added person whose stream arrived unannounced is in the call by name, not a Guest`() {
        // Line 1 says nothing when a mid-call add answers; their stream is the answer.
        val added = person("sat", status = CallStatus.NotAnswered, name = "Sahil Android Test")
        val call = session(added).copy(provider = CallProvider.Mediasoup)
        val uid = mediasoupUidOf("sat")
        val live = CallMedia(selfUid = 1, peers = mapOf(uid to MediaPeer(uid)))

        val rows = call.participants.answeredByMedia(call.provider, live.peers.keys, call.selfUserId)
        assertEquals(CallStatus.InCall, rows.single().status)

        // Even unpromoted, the stage names the stream after its owner.
        val tiles = buildTiles(call, live, "Me", micMuted = false, cameraOn = false)
        assertEquals(listOf("Me", "Sahil Android Test"), tiles.map { it.name })
    }

    @Test
    fun `on Line 1 a stream under an unknown id is the one in-call row without a stream`() {
        // The phone joined as "phone-id"; its row says "them". Not a Guest
        // beside an empty tile — one face, theirs.
        val call = session(person("them", name = "Sahil Kashyap")).copy(provider = CallProvider.Mediasoup)
        val uid = mediasoupUidOf("phone-id")
        val tiles = buildTiles(call, CallMedia(selfUid = 1, peers = mapOf(uid to MediaPeer(uid))), "Me", false, false)
        assertEquals(listOf("Me", "Sahil Kashyap"), tiles.map { it.name })
        assertEquals(uid, tiles.last().uid)
        assertNotNull(tiles.last().media)
    }

    @Test
    fun `a departure whose stream has not closed yet is not read back as an answer`() {
        val gone = person("sk", status = CallStatus.Left)
        val uid = mediasoupUidOf("sk")
        val rows = listOf(gone)
        assertTrue(rows.answeredByMedia(CallProvider.Mediasoup, setOf(uid), "me") === rows)
    }

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

    /**
     * An incoming Line 2 call: the other person's row reads `caller`, not
     * `in_call`, and carries no agora_uid. Their stream must still bind to
     * them — it used to fall through as an unclaimed stream named "Guest".
     */
    @Test
    fun `on an incoming call the caller's row claims the one orphan stream`() {
        val media = CallMedia(selfUid = 1, peers = mapOf(9 to MediaPeer(9)))
        val tiles = buildTiles(
            session(person("vivek", status = CallStatus.Caller, name = "Vivek Mishra")),
            media, "Me", false, false,
        )

        assertEquals(listOf("Me", "Vivek Mishra"), tiles.map { it.name })
        assertEquals(9, tiles.single { it.userId == "vivek" }.uid)
    }

    /** A 1:1 whose roster lacks the other person: the one stream is them, by the call's name. */
    @Test
    fun `a 1 to 1 stream with no roster row wears the caller's name`() {
        val session = CallSession(
            callUuid = "u1",
            selfUserId = "me",
            mode = CallMode.Private,
            direction = CallDirection.Incoming,
            callerUserId = "vivek",
            callerName = "Vivek Mishra",
        )
        val tiles = buildTiles(session, CallMedia(selfUid = 1, peers = mapOf(9 to MediaPeer(9))), "Me", false, false)

        val them = tiles.single { it.uid == 9 }
        assertEquals("Vivek Mishra", them.name)
        assertEquals("vivek", them.userId)
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

    @Test
    fun `a nameless 1 to 1 peer is named from the call, not called Guest`() {
        // Line 1 is where this bites: nothing writes `user_name` onto the
        // roster row, so the tile read "Guest" beside a call window titled
        // with the very name it was missing.
        val session = CallSession(
            callUuid = "u1",
            selfUserId = "me",
            mode = CallMode.Private,
            direction = CallDirection.Outgoing,
            title = "Samsung Device",
            participants = listOf(CallParticipant(userId = "them", name = "", status = CallStatus.InCall)),
        )

        val tiles = buildTiles(session, CallMedia(), "Vivek", micMuted = false, cameraOn = false)

        assertEquals("Samsung Device", tiles.last().name)
    }

    @Test
    fun `a named peer keeps their own name`() {
        val session = CallSession(
            callUuid = "u1",
            selfUserId = "me",
            mode = CallMode.Private,
            direction = CallDirection.Outgoing,
            title = "Samsung Device",
            participants = listOf(CallParticipant(userId = "them", name = "Asha", status = CallStatus.InCall)),
        )

        val tiles = buildTiles(session, CallMedia(), "Vivek", micMuted = false, cameraOn = false)

        assertEquals("Asha", tiles.last().name)
    }

    @Test
    fun `a group call never borrows the call's name for a nameless row`() {
        // With more than one candidate the call's title is one person's name,
        // and putting it on an arbitrary face is worse than admitting we do
        // not know.
        val session = CallSession(
            callUuid = "u1",
            selfUserId = "me",
            mode = CallMode.Group,
            title = "Crew standup",
            participants = listOf(
                CallParticipant(userId = "a", name = "", status = CallStatus.InCall),
                CallParticipant(userId = "b", name = "", status = CallStatus.InCall),
            ),
        )

        val tiles = buildTiles(session, CallMedia(), "Vivek", micMuted = false, cameraOn = false)

        assertTrue(tiles.drop(1).all { it.name == "Guest" }, tiles.map { it.name }.toString())
    }

    /** A Line 1 group roster: real user ids, no names on any row. */
    private fun namelessGroup() = CallSession(
        callUuid = "u1",
        selfUserId = "me",
        mode = CallMode.Group,
        title = "Team Leads",
        participants = listOf(
            CallParticipant(userId = "a", name = "", status = CallStatus.InCall),
            CallParticipant(userId = "b", name = "", status = CallStatus.InCall),
            CallParticipant(userId = "c", name = "", status = CallStatus.InCall),
        ),
    )

    /**
     * The reported bug: every face on a Line 1 group stage read "Guest",
     * because nothing on that line writes a name onto the roster row and the
     * 1:1 mitigation has no single other person to borrow from.
     */
    @Test
    fun `a nameless group roster row is named from the local directory`() {
        val directory = mapOf(
            "a" to CallDirectoryEntry("Priya"),
            "b" to CallDirectoryEntry("Rahul"),
            "c" to CallDirectoryEntry("Dev"),
        )

        val tiles = buildTiles(
            namelessGroup(), CallMedia(), "Vivek",
            micMuted = false, cameraOn = false, selfHand = false,
            directory = directory::get,
        )

        assertEquals(listOf("Priya", "Rahul", "Dev"), tiles.drop(1).map { it.name })
    }

    /**
     * Somebody the directory declines to name — a keep-name-private member is
     * absent from the map — must stay "Guest". The filtering happens where the
     * map is built; the contract here is that a miss never invents a name.
     */
    @Test
    fun `a crew member the directory declines to name is still not named`() {
        val tiles = buildTiles(
            namelessGroup(), CallMedia(), "Vivek",
            micMuted = false, cameraOn = false, selfHand = false,
            directory = mapOf("a" to CallDirectoryEntry("Priya"))::get,
        )

        assertEquals("Guest", tiles.single { it.userId == "b" }.name)
        assertEquals("Guest", tiles.single { it.userId == "c" }.name)
    }

    /**
     * The roster is the authority for the job title too — it knows people this
     * client never fetched — and the directory only fills a row it left blank,
     * which is what the web's `desigOf` does. A guest has no title in this
     * production and must never borrow one.
     */
    @Test
    fun `a job title comes from the roster first and the directory second`() {
        val tiles = buildTiles(
            session(
                person("vivek", name = "Vivek").copy(designation = "DoP"),
                person("asha", name = "Asha"),
                person("guest_7", name = "Pat").copy(isGuest = true),
            ),
            CallMedia(), "Me",
            micMuted = false, cameraOn = false, selfHand = false,
            directory = mapOf(
                "vivek" to CallDirectoryEntry("Vivek", "Gaffer"),
                "asha" to CallDirectoryEntry("Asha", "Grip"),
                "guest_7" to CallDirectoryEntry("Pat", "Producer"),
            )::get,
        )

        assertEquals("DoP", tiles.single { it.userId == "vivek" }.designation, "the roster's own wins")
        assertEquals("Grip", tiles.single { it.userId == "asha" }.designation, "a blank row is filled in")
        assertEquals("", tiles.single { it.userId == "guest_7" }.designation, "a guest has no title here")
    }

    @Test
    fun `the directory never overrides a name the server sent`() {
        val tiles = buildTiles(
            session(person("vivek", name = "Asha")), CallMedia(), "Me",
            micMuted = false, cameraOn = false, selfHand = false,
            directory = { CallDirectoryEntry("Someone Else") },
        )

        assertEquals("Asha", tiles.last().name)
    }

    /**
     * An unclaimed stream has no user id at all, so there is nobody to look
     * up. A wrong name on a face is worse than the bug this fixes.
     */
    @Test
    fun `an unclaimed stream is still a Guest even with a full directory`() {
        val media = CallMedia(peers = mapOf(77 to MediaPeer(77)))

        val tiles = buildTiles(
            session(), media, "Me",
            micMuted = false, cameraOn = false, selfHand = false,
            directory = { CallDirectoryEntry("Priya") },
        )

        assertEquals("Guest", tiles.single { it.uid == 77 }.name)
    }
}
