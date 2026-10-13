package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.flow.StateFlow

/**
 * Where one clip's playback stands — the moving part the controls read.
 *
 * [failed] is terminal: the object could not be opened, or died part way
 * through. The viewer swaps to words when it arrives, because a frozen
 * picture and a dead player look identical.
 */
data class VideoPlaybackState(
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val isPlaying: Boolean = false,
    val failed: Boolean = false,
    /**
     * The picture has started arriving — latched, so it stays true once the
     * clip is under way.
     *
     * Separate from [isPlaying] because pausing must not put the viewer back
     * to its spinner. Until this turns true there is a signed URL being
     * fetched, a header being read and a decoder starting, which on a large
     * clip is several seconds of nothing to look at.
     */
    val started: Boolean = false,
    /** Sound off — the player's own mute, not the machine's volume. */
    val isMuted: Boolean = false,
    /**
     * The video's format, by name ("AV1"), when the player cannot draw it.
     * Such a clip still loads and plays its sound over a blank picture, so
     * the player stops it and the viewer says why rather than showing black.
     */
    val unplayableFormat: String? = null,
) {
    /** 0..1, for the progress bar. */
    val progress: Float
        get() = if (durationMillis <= 0) {
            0f
        } else {
            (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
        }
}

/**
 * One open clip: what the host hands back for a URL, and what closes it.
 *
 * Deliberately the shape of [AudioPlayer] rather than a `<video>`-like black
 * box, because the picture and the controls cannot be drawn by the same thing
 * here. The host's picture is a platform surface composed *over* the Compose
 * canvas, so anything drawn on top of it is painted over: the controls have to
 * be ours, beside it, which means the state behind them has to come back out.
 */
interface VideoPlayback {
    val state: StateFlow<VideoPlaybackState>

    /** Play if paused, pause if playing; restarts a clip sitting at its end. */
    fun toggle()

    /** Sound off, or back on. */
    fun toggleMute()

    /** Jumps to [fraction] of the length, 0..1. */
    fun seek(fraction: Float)

    /** Stops it and lets the decoder go. Called exactly once, by the viewer. */
    fun close()
}

/**
 * The host's video player.
 *
 * A seam rather than a widget because the JVM has no video component — the
 * desktop's is JavaFX's media stack, which lives in the app module with the
 * other platform wiring, and common code must not reach for it.
 *
 * [open] takes a plain fetchable address, signed by whoever supplied it. The
 * player streams it; it is never asked to download a file first.
 */
interface VideoEngine {
    fun open(url: String): VideoPlayback

    /** The picture itself, filling [modifier]. */
    @Composable
    fun Picture(playback: VideoPlayback, modifier: Modifier)
}

/** Null where no host installed one — tests, previews, a runtime with no player. */
val LocalVideoEngine = staticCompositionLocalOf<VideoEngine?> { null }

/**
 * The window's own full-screen switch.
 *
 * The viewer cannot reach the OS window it is drawn in — it is composed deep
 * inside a tool — so the host provides this. It is *not* the viewer's full
 * screen button on its own: filling the display with the app, while the clip
 * stays the size of the pane it was playing in, is what that button used to do
 * and is the bug this seam now only assists with. [ZillitVideoStage] is the
 * other half, and the half that matters. Null where the host has no window to
 * offer — a clip then fills its window rather than the display.
 */
interface FullScreen {
    val isFullScreen: Boolean
    fun toggle()
}

val LocalFullScreen = staticCompositionLocalOf<FullScreen?> { null }

/**
 * Where a clip goes when it is made full screen: one slot per window, over
 * everything else in it.
 *
 * A viewer is composed inside a pane, inside a tool, inside a tab strip, and
 * nothing it can do to its own layout escapes that — which is why the button
 * used to reach for the OS window instead and left the picture exactly as
 * small as it was. The player is moved *out* of the pane and into this slot,
 * which the host mounts over the whole window ([ZillitVideoStage]), and back
 * when it is collapsed.
 *
 * Moved, not re-created: the content put here is [androidx.compose.runtime.movableContentOf],
 * so the same platform surface is re-parented with the clip still playing.
 * Re-creating it would restart a stream — and for the shared JavaFX surface,
 * tearing one down while the other end is mounting it is the ordering bug that
 * once left the picture black.
 */
class VideoStage {
    /** The player, while one is full screen. Null the rest of the time. */
    internal var showing by mutableStateOf<(@Composable () -> Unit)?>(null)
}

/** Null where no host mounted one — the button is not drawn there. */
val LocalVideoStage = staticCompositionLocalOf<VideoStage?> { null }

/**
 * Mounts one window's full-screen slot around [content].
 *
 * Every window that can show a clip wraps its content in this, beside the
 * player itself: the slot has to be an ancestor of every viewer in the window
 * and a sibling of nothing else, or "full screen" would again mean "as large
 * as some pane".
 */
@Composable
fun ZillitVideoStage(content: @Composable () -> Unit) {
    val stage = remember { VideoStage() }
    CompositionLocalProvider(LocalVideoStage provides stage) {
        Box(Modifier.fillMaxSize()) {
            content()
            stage.showing?.let { player ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        // Nothing under the layer answers a press meant for it —
                        // the viewer beneath closes on a click outside its picture.
                        .swallowPresses(),
                ) {
                    player()
                }
            }
        }
    }
}

/**
 * A clip, as a viewer shows one: the picture, the controls under it, and words
 * when it cannot be played at all.
 *
 * [url] null means the address is still being resolved — signing is a round
 * trip through the production's credentials. [failed] means it could not be.
 * Either of those, a host with no player, or a player that gives up part way
 * through, all end in the same place: the sentence and [onOpenOutside]. A
 * black rectangle would be the tap-that-did-nothing bug with a player
 * attached.
 */
@Composable
fun ZillitVideoView(
    url: String?,
    failed: Boolean,
    onOpenOutside: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val engine = LocalVideoEngine.current
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            failed -> PlaybackRefused(onOpenOutside)

            url == null -> ZillitText(
                text = str(S.desktop_video_preparing),
                style = ZillitTheme.typography.bodyMedium,
                color = Color.White,
            )

            // A host with no player is the same dead end as a refused URL, and
            // reads the same to whoever is looking at it.
            engine == null -> PlaybackRefused(onOpenOutside)

            else -> Playing(engine, url, onOpenOutside)
        }
    }
}

/** One opened clip, for as long as the viewer shows it. */
@Composable
private fun Playing(engine: VideoEngine, url: String, onOpenOutside: (() -> Unit)?) {
    // Keyed on the URL: a different clip is a different player. Closing the old
    // one is also what stops its sound, which is the part worth being sure of —
    // pausing is a thing you have to remember to do.
    val playback = remember(engine, url) { engine.open(url) }
    DisposableEffect(playback) { onDispose { playback.close() } }
    val state by playback.state.collectAsState()

    if (state.failed) {
        PlaybackRefused(onOpenOutside, state.unplayableFormat)
        return
    }

    val slot = remember { PlayerSlot() }
    slot.stage = LocalVideoStage.current
    slot.window = LocalFullScreen.current

    // One player, composed either here in the pane or in the window's
    // full-screen slot. `movableContentOf` is what makes that a *move* rather
    // than a fresh start: the same platform surface is re-parented and the clip
    // plays straight through the switch, which is the whole point of it.
    val player = remember(playback) {
        movableContentOf {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    // The picture only once there is one. The host's surface is a
                    // platform component that paints over anything Compose draws in its
                    // bounds, so a spinner on top of it would simply not be there — it
                    // takes the surface's place instead, and the surface arrives with
                    // the first frame.
                    if (state.started) {
                        engine.Picture(playback, Modifier.fillMaxSize())
                    } else {
                        Starting()
                    }
                }
                VideoControls(
                    state = state,
                    playback = playback,
                    fullScreen = slot.isFullScreen,
                    // No slot in this window, no button: the rule this app keeps
                    // for every affordance it cannot honour.
                    onToggleFullScreen = if (slot.stage == null) null else slot::toggle,
                )
            }
        }
    }
    slot.player = player

    // Dismissed while full screen — the viewer underneath can be closed from
    // the keyboard — would otherwise leave a dead player filling the window.
    DisposableEffect(slot) { onDispose { slot.collapse() } }

    // Here only while it is not in the slot: in both places at once is the one
    // thing movable content must never be.
    if (!slot.isFullScreen) player()
}

/**
 * The player, the window it is in, and the switch between the two places it
 * can be drawn.
 *
 * A holder rather than three locals because the switch is *inside* the thing
 * it moves — it is a button in the transport bar — so it cannot name it
 * directly. Everything it reads to decide is state the composition is already
 * watching, so the pane and the slot recompose together, in one pass, which is
 * what makes the hand-over a move.
 */
private class PlayerSlot {
    var stage: VideoStage? = null
    var window: FullScreen? = null
    var player: (@Composable () -> Unit)? = null

    /** Whether we were the one to put the window full screen, and so may put it back. */
    private var tookWindow = false

    val isFullScreen: Boolean
        get() = player != null && stage?.showing === player

    /** Into the slot, or back out of it; the OS window follows either way. */
    fun toggle() {
        if (isFullScreen) collapse() else expand()
    }

    private fun expand() {
        val stage = stage ?: return
        stage.showing = player ?: return
        // Full screen has to mean the display, not merely this window — a
        // window at half the screen would otherwise call half the screen full.
        val window = window ?: return
        if (!window.isFullScreen) {
            window.toggle()
            tookWindow = true
        }
    }

    /** Idempotent: also the way out when the viewer is closed from under it. */
    fun collapse() {
        if (isFullScreen) {
            stage?.showing = null
        }
        // Only a window we put full screen goes back; one the user had that way
        // already is theirs.
        if (tookWindow && window?.isFullScreen == true) window?.toggle()
        tookWindow = false
    }
}

/** The wait before the first frame: a spinner, and what it is waiting for. */
@Composable
private fun Starting() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitSpinner(color = Color.White)
        ZillitText(
            text = str(S.desktop_video_preparing),
            style = ZillitTheme.typography.bodySmall,
            color = Color.White.copy(alpha = CLOCK_ALPHA),
        )
    }
}

/**
 * Play/pause and the scrubber, under the picture rather than over it.
 *
 * Under, because the picture is a platform surface that paints above
 * everything Compose draws in its bounds — controls on top of it would simply
 * not be there. The bar is the voice message's, so a clip and a voice note
 * scrub the same way.
 */
@Composable
private fun VideoControls(
    state: VideoPlaybackState,
    playback: VideoPlayback,
    fullScreen: Boolean,
    onToggleFullScreen: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = CONTROLS_SCRIM))
            // The transport belongs to the viewer, not to the scrim around it:
            // a click that misses the play button must not close the clip.
            .swallowPresses()
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIconButton(
            icon = if (state.isPlaying) ZillitIcons.Pause else ZillitIcons.Play,
            contentDescription = str(if (state.isPlaying) S.desktop_pause else S.desktop_play),
            onClick = playback::toggle,
            tint = Color.White,
        )
        ZillitAudioProgress(
            progress = state.progress,
            positionMillis = state.positionMillis,
            totalMillis = state.durationMillis,
            // Nothing to jump within until the length is known — the same rule
            // a voice bubble follows before its message has ever played.
            onSeek = if (state.durationMillis > 0) playback::seek else null,
            modifier = Modifier.weight(1f),
            trackColor = Color.White.copy(alpha = TRACK_ALPHA),
            fillColor = Color.White,
            clockColor = Color.White.copy(alpha = CLOCK_ALPHA),
        )
        ZillitIconButton(
            icon = if (state.isMuted) ZillitIcons.VolumeOff else ZillitIcons.Volume,
            contentDescription = str(if (state.isMuted) S.desktop_unmute else S.desktop_mute),
            onClick = playback::toggleMute,
            tint = Color.White,
        )
        onToggleFullScreen?.let { toggle ->
            ZillitIconButton(
                icon = if (fullScreen) ZillitIcons.Collapse else ZillitIcons.Expand,
                contentDescription = str(
                    if (fullScreen) S.desktop_br_exit_full_screen else S.desktop_video_full_screen,
                ),
                onClick = toggle,
                tint = Color.White,
            )
        }
    }
}

/** The sentence, and the way out of it. */
@Composable
private fun PlaybackRefused(onOpenOutside: (() -> Unit)?, unplayableFormat: String? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = unplayableFormat?.let { str(S.desktop_video_format_unplayable, it) }
                ?: str(S.desktop_could_not_play_video),
            style = ZillitTheme.typography.bodyMedium,
            color = Color.White,
        )
        onOpenOutside?.let { open ->
            ZillitButton(
                text = str(S.desktop_video_open_outside),
                onClick = open,
                variant = ButtonVariant.Secondary,
            )
        }
    }
}

/**
 * The cross that closes a full-screen viewer.
 *
 * On a scrim disc, not bare: a white glyph over a bright photograph or a pale
 * first frame is invisible exactly where someone is looking for the way out.
 */
@Composable
fun ZillitViewerClose(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = CLOSE_SCRIM), CircleShape)
            .padding(CLOSE_INSET),
    ) {
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = str(S.desktop_close_viewer),
            onClick = onClose,
            tint = Color.White,
        )
    }
}

private val CLOSE_INSET = 2.dp
private const val CLOSE_SCRIM = 0.45f
private const val CONTROLS_SCRIM = 0.55f
private const val TRACK_ALPHA = 0.3f
private const val CLOCK_ALPHA = 0.7f
