package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.domain.CallEngineEvent
import com.zillit.desktop.feature.calls.domain.CallMedia
import com.zillit.desktop.feature.calls.domain.reduce
import com.zillit.desktop.feature.calls.ui.CallTile
import com.zillit.desktop.feature.calls.ui.TileMedia
import com.zillit.desktop.feature.calls.ui.stageJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A remote camera turned off left its last frame frozen in the tile, where
 * their picture belonged: a muted (LiveKit) or paused (mediasoup) track still
 * holds that frame. The stage now tells the page `camOff`, which keeps the
 * video out — but only on an explicit report, so a stream whose "on" was
 * never reported keeps showing as before.
 */
class CameraOffTileTest {

    @Test
    fun `only an explicit report turns the camera off, and the next on clears it`() {
        val joined = CallMedia().reduce(CallEngineEvent.PeerJoined(7))
        assertFalse(joined.peers.getValue(7).cameraOff, "no report yet is not off")

        val off = joined.reduce(CallEngineEvent.PeerVideoMuted(7, muted = true))
        assertTrue(off.peers.getValue(7).cameraOff)

        val back = off.reduce(CallEngineEvent.PeerVideoMuted(7, muted = false))
        assertFalse(back.peers.getValue(7).cameraOff)
    }

    private fun camOff(tile: CallTile): Boolean =
        Json.parseToJsonElement(stageJson(listOf(tile), columns = 1))
            .jsonObject.getValue("tiles").jsonArray.single()
            .jsonObject.getValue("camOff").jsonPrimitive.boolean

    private fun tile(media: TileMedia?, self: Boolean = false) =
        CallTile(key = "k", name = "Vivek Mishra", uid = 7, isSelf = self, media = media)

    @Test
    fun `the stage says camOff for a reported-off camera, and only then`() {
        assertTrue(camOff(tile(TileMedia(cameraOff = true))))
        assertFalse(camOff(tile(TileMedia(videoOn = false))), "unreported is never hidden")
        assertFalse(camOff(tile(null)), "an unattributed stream is never hidden")
    }

    @Test
    fun `a shared screen and our own preview are never hidden by it`() {
        assertFalse(camOff(tile(TileMedia(cameraOff = true, sharing = true))))
        assertFalse(camOff(tile(TileMedia(cameraOff = true), self = true)))
    }
}
