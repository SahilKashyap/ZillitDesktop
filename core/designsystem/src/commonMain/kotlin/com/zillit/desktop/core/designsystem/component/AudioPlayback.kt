package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * Where one voice message's playback stands.
 *
 * One of these exists at a time — the machine has one speaker, and every
 * surface that plays audio shares the player, so starting a message anywhere
 * silences whatever else was talking.
 */
data class PlaybackState(
    /** The attachment's object key — which bubble the sound belongs to. */
    val key: String,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val isPlaying: Boolean = false,
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
 * The one speaker, as a port.
 *
 * Implemented once per platform (the JVM's `Clip` on desktop) and handed to
 * every surface with voice bubbles, so playback state is a single truth: the
 * board pausing when a chat message plays is this interface's doing, not a
 * coincidence.
 */
interface AudioPlayer {
    val state: StateFlow<PlaybackState?>

    /** Play, pause, or switch to [key]; [bytes] decode on first load. */
    suspend fun toggle(key: String, bytes: ByteArray): ZillitResult<Unit>

    /**
     * Jumps the **loaded** message to [fraction] of its length, 0..1.
     *
     * Only the loaded one: a bar clicked before its message ever played has no
     * decoded audio to jump within, and toggling first is the gesture that
     * loads it.
     */
    fun seek(key: String, fraction: Float)

    fun stop()
}

/**
 * The track with the played share filled, and the clock under it.
 *
 * Colours are parameters because the two homes of this bar disagree: the
 * board draws white-on-card, a chat bubble draws in its own tint. The shape,
 * hit area and clock format are the shared part — the part that must not
 * drift between them.
 */
@Composable
@Suppress("LongParameterList")
fun ZillitAudioProgress(
    progress: Float,
    positionMillis: Long,
    totalMillis: Long,
    onSeek: ((Float) -> Unit)?,
    modifier: Modifier = Modifier,
    trackColor: Color = ZillitTheme.colors.border,
    fillColor: Color = ZillitTheme.colors.accent,
    clockColor: Color = ZillitTheme.colors.textMuted,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Taller hit area than the painted track: a 4dp click target
                // is a dexterity test, not a control.
                .height(AUDIO_TRACK_HIT)
                .then(
                    if (onSeek == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(onSeek) {
                            detectTapGestures { offset ->
                                onSeek(offset.x / size.width.toFloat())
                            }
                        }
                    },
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AUDIO_TRACK)
                    .clip(ZillitTheme.shapes.pill)
                    .background(trackColor),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(AUDIO_TRACK)
                        .background(fillColor),
                )
            }
        }

        ZillitText(
            text = playbackClock(positionMillis) + " / " + playbackClock(totalMillis),
            style = ZillitTheme.typography.labelSmall,
            color = clockColor,
        )
    }
}

/** 0:07 — matching the web's `formatSeconds`. */
fun playbackClock(millis: Long): String {
    val totalSeconds = millis / MILLIS_PER_SECOND
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return minutes.toString() + ":" + seconds.toString().padStart(2, '0')
}

private val AUDIO_TRACK = 4.dp
private val AUDIO_TRACK_HIT = 20.dp
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
