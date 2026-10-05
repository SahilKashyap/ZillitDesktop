package com.zillit.desktop.feature.calls.ui

import com.zillit.desktop.feature.calls.domain.CallDirectoryEntry
import com.zillit.desktop.feature.calls.domain.CallMedia
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.CallStatus

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
    /** See [buildTiles]. Keep-name-private members are already filtered out. */
    directory: (String) -> CallDirectoryEntry? = { null },
): CallUiState {
    val tiles = buildTiles(session, media, selfName, micMuted, cameraOn, previous.handRaised, directory)
    // Line 1's stage is the room, as the web draws it (callStore.js:4058-4225):
    // a face is on it while their media is. A row that still reads in_call
    // with nobody behind it — a `left` that matched no row — stays in the
    // users panel, never as an empty tile on the stage.
    val line1 = session?.provider == CallProvider.Mediasoup
    val stage = tiles.filterNot { it.presence == CallStatus.Ringing }
        .filter { !line1 || it.isSelf || it.media != null }
    val names = previous.participantNames + session?.participants.orEmpty()
        .mapNotNull { row ->
            val name = row.name.ifBlank { directory(row.userId)?.name.orEmpty() }
            if (row.userId.isBlank() || name.isBlank()) null else row.userId to name
        }
    // Latched, never unlatched mid-call: the stage swapping between a Compose
    // grid and a browser surface every time somebody toggled a camera would
    // move a native window between parents on each toggle.
    //
    // A peer presenting counts as video too. A shared screen is a picture
    // with no camera behind it, and on an audio call the stage stayed on the
    // Compose avatars — the share arrived and was never mounted anywhere.
    val seen = previous.videoSeen || session?.hasVideo == true || cameraOn ||
        media.peers.values.any { it.videoOn || it.sharing }
    return previous.copy(
        session = session,
        media = media,
        micMuted = micMuted,
        cameraOn = cameraOn,
        tiles = tiles,
        stageTiles = stage,
        participantNames = names,
        videoSeen = seen,
        stageJson = if (stage.isEmpty()) "" else stageJson(stage, columnsFor(stage.size), previous.pins),
    )
}
