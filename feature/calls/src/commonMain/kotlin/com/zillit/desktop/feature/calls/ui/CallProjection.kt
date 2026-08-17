package com.zillit.desktop.feature.calls.ui

import com.zillit.desktop.feature.calls.domain.CallMedia
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.MediaPeer

/**
 * The whole UI projection as one function.
 *
 * Top-level and pure so the video latch, the tile build and the page payload
 * are unit-testable without a ViewModel, a Main dispatcher, or a call — which
 * is the only way any of this gets checked on a machine with one camera and
 * one person.
 */
fun projectCallUi(
    previous: CallUiState,
    session: CallSession?,
    media: CallMedia,
    micMuted: Boolean,
    cameraOn: Boolean,
    selfName: String,
): CallUiState {
    val tiles = buildTiles(session, media, selfName, micMuted, cameraOn)
    // Latched, never unlatched mid-call: the stage swapping between a Compose
    // grid and a browser surface every time somebody toggled a camera would
    // move a native window between parents on each toggle.
    val seen = previous.videoSeen || session?.hasVideo == true || cameraOn ||
        media.peers.values.any(MediaPeer::videoOn)
    return previous.copy(
        session = session,
        media = media,
        micMuted = micMuted,
        cameraOn = cameraOn,
        tiles = tiles,
        videoSeen = seen,
        stageJson = if (tiles.isEmpty()) "" else stageJson(tiles, columnsFor(tiles.size)),
    )
}
