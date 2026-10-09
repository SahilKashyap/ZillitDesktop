package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.domain.AGORA_SCREEN_UID_OFFSET
import com.zillit.desktop.feature.calls.domain.CallMedia
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus
import com.zillit.desktop.feature.calls.domain.MediaPeer
import com.zillit.desktop.feature.calls.domain.agoraScreenOwner
import com.zillit.desktop.feature.calls.ui.CallUiState
import com.zillit.desktop.feature.calls.ui.buildTiles
import com.zillit.desktop.feature.calls.ui.pinnedTiles
import com.zillit.desktop.feature.calls.ui.stageJson
import com.zillit.desktop.feature.calls.ui.togglePin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Line 2: a web or phone presenter shares from a second Agora uid (camera +
 * 100 000). That stream was an unclaimed "person" keyed by the presenter's
 * user id — so it never took the stage, and pinning it pinned their face.
 */
class AgoraScreenShareTileTest {

    private val camera = 4_321
    private val screen = camera + AGORA_SCREEN_UID_OFFSET

    private val session = CallSession(
        callUuid = "u1",
        selfUserId = "me",
        selfDeviceId = "my-device",
        provider = CallProvider.Agora,
        participants = listOf(
            CallParticipant(
                userId = "web",
                agoraUid = camera.toString(),
                status = CallStatus.InCall,
                name = "Web User",
            ),
        ),
    )

    private val media = CallMedia(
        selfUid = 1,
        peers = mapOf(
            camera to MediaPeer(camera),
            // What onPlaneFlags leaves behind: the screen named after its owner.
            screen to MediaPeer(screen, videoOn = true, identity = "web", displayName = "Web User"),
        ),
    )

    private fun tiles(m: CallMedia = media, s: CallSession = session) =
        buildTiles(s, m, "Me", micMuted = false, cameraOn = false)

    @Test
    fun `the screen gets its own tile, after its owner, under a key of its own`() {
        val tiles = tiles()
        assertEquals(listOf("self", "web", "screen:$screen"), tiles.map { it.key })
        val owner = tiles[1]
        val share = tiles[2]
        assertEquals(camera, owner.uid)
        assertEquals("Web User", share.name)
        assertTrue(share.presenting)
        assertTrue(share.media!!.sharing)
        assertFalse(owner.presenting)
        assertNotEquals(owner.key, share.key)
    }

    @Test
    fun `pinning the screen pins the screen, not its owner's face`() {
        val tiles = tiles()
        val state = CallUiState(session = session, tiles = tiles).togglePin("screen:$screen")
        assertEquals(listOf(screen), pinnedTiles(state.tiles, state.pins).map { it.uid })
    }

    @Test
    fun `the page is told which tile is the screen`() {
        val json = Json.parseToJsonElement(stageJson(tiles(), columns = 2)).jsonObject
        val share = json["tiles"]!!.jsonArray.map { it.jsonObject["share"]!!.jsonPrimitive.boolean }
        assertEquals(listOf(false, false, true), share)
        assertEquals("Sharing", json["words"]!!.jsonObject["sharing"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a screen does not break the one-row-one-stream pairing`() {
        // The owner's row carries no agora_uid: their camera is the one orphan.
        val unbound = session.copy(
            participants = listOf(CallParticipant(userId = "web", status = CallStatus.InCall, name = "Web User")),
        )
        val tiles = tiles(s = unbound)
        assertEquals(listOf("self", "web", "screen:$screen"), tiles.map { it.key })
        assertEquals(camera, tiles[1].uid)
    }

    @Test
    fun `a desktop presenter sharing on its own camera uid presents from its own tile`() {
        val sharing = CallMedia(selfUid = 1, peers = mapOf(camera to MediaPeer(camera, videoOn = true, sharing = true)))
        val tiles = tiles(m = sharing)
        assertEquals(listOf("self", "web"), tiles.map { it.key })
        assertTrue(tiles[1].presenting)
    }

    @Test
    fun `a high uid is a person until a camera sits one offset below it`() {
        assertNull(agoraScreenOwner(screen, cameras = emptySet()))
        assertNull(agoraScreenOwner(camera, cameras = setOf(camera - AGORA_SCREEN_UID_OFFSET)))
        assertEquals(camera, agoraScreenOwner(screen, cameras = setOf(camera)))

        val stranger = CallMedia(selfUid = 1, peers = mapOf(screen to MediaPeer(screen)))
        val alone = session.copy(participants = emptyList())
        assertEquals(listOf("self", "uid:$screen"), tiles(m = stranger, s = alone).map { it.key })
    }

    @Test
    fun `Lines 1 and 3 are untouched, the page reads their shares from the track`() {
        val lk = session.copy(provider = CallProvider.LiveKit)
        val sharing = CallMedia(selfUid = 1, peers = mapOf(camera to MediaPeer(camera, sharing = true)))
        val tiles = tiles(m = sharing, s = lk)
        assertTrue(tiles.none { it.presenting })
    }
}
