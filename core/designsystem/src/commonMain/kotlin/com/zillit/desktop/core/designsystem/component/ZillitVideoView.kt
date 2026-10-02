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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * The window's full-screen switch, as the viewer sees it.
 *
 * The viewer cannot reach the OS window it is drawn in — it is composed deep
 * inside a tool — so the host provides this. Null hides the button rather than
 * offering a dead one, which is this app's rule for every affordance it cannot
 * honour.
 */
interface FullScreen {
    val isFullScreen: Boolean
    fun toggle()
}

val LocalFullScreen = staticCompositionLocalOf<FullScreen?> { null }

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
        PlaybackRefused(onOpenOutside)
        return
    }

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
        VideoControls(state, playback)
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
private fun VideoControls(state: VideoPlaybackState, playback: VideoPlayback) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = CONTROLS_SCRIM))
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
        LocalFullScreen.current?.let { screen ->
            ZillitIconButton(
                icon = if (screen.isFullScreen) ZillitIcons.Collapse else ZillitIcons.Expand,
                contentDescription = str(
                    if (screen.isFullScreen) S.desktop_br_exit_full_screen else S.desktop_video_full_screen,
                ),
                onClick = screen::toggle,
                tint = Color.White,
            )
        }
    }
}

/** The sentence, and the way out of it. */
@Composable
private fun PlaybackRefused(onOpenOutside: (() -> Unit)?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = str(S.desktop_could_not_play_video),
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
