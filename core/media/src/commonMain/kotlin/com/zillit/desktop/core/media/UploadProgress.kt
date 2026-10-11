package com.zillit.desktop.core.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Where a file is on its way to storage, as the one `Int` every upload seam
 * already passes (`onProgress: (Int) -> Unit`).
 *
 * Three phases share it, so a bubble can say which one it is in rather than
 * calling a video's re-encode "Uploading":
 *
 *  - [PREPARING] (negative): thumbnails and poster frames are being made.
 *  - [compressing] `n` — a large video being re-encoded, n percent through.
 *  - plain `0..100`: bytes leaving for storage.
 *
 * Encoded rather than a new type so the seams, and the fakes behind every
 * test that drives them, keep their shape.
 */
object UploadProgress {

    /** Thumbnails and poster frames being made; nothing to count yet. */
    const val PREPARING: Int = -1

    private const val COMPRESSING_BASE = 1_000
    private const val FULL = 100

    enum class Phase { Preparing, Compressing, Uploading }

    /** A video [percent] of the way through being compressed. */
    fun compressing(percent: Int): Int = COMPRESSING_BASE + percent.coerceIn(0, FULL)

    fun phase(progress: Int): Phase = when {
        progress < 0 -> Phase.Preparing
        progress >= COMPRESSING_BASE -> Phase.Compressing
        else -> Phase.Uploading
    }

    /** 0..100 within the current phase; 0 while preparing. */
    fun percent(progress: Int): Int = when (phase(progress)) {
        Phase.Preparing -> 0
        Phase.Compressing -> (progress - COMPRESSING_BASE).coerceIn(0, FULL)
        Phase.Uploading -> progress.coerceIn(0, FULL)
    }

    /** "Processing…", "Compressing 40%", "Uploading 72%". */
    fun label(progress: Int): String = when (phase(progress)) {
        Phase.Preparing -> str(S.desktop_processing_ellipsis)
        Phase.Compressing -> str(S.desktop_compressing_percent, percent(progress))
        Phase.Uploading -> str(S.drive_uploads_status_uploading_format, percent(progress))
    }
}

/**
 * Laid over a file's own picture while it uploads: the picture dimmed, a ring
 * that fills with the current phase, and the phase in words under it. While
 * preparing there is nothing to count, so the ring spins instead.
 *
 * Fills whatever it is placed in — put it in a `Box` over the thumbnail, at
 * the thumbnail's size, so the tile does not move when the upload ends and
 * the overlay goes.
 */
@Composable
fun UploadOverlay(progress: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SCRIM),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            if (UploadProgress.phase(progress) == UploadProgress.Phase.Preparing) {
                ZillitSpinner(size = RING, color = Color.White)
            } else {
                ProgressRing(UploadProgress.percent(progress) / FULL_PERCENT)
            }
            ZillitText(
                text = UploadProgress.label(progress),
                style = ZillitTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

/** A determinate ring: a faint full circle, and the done share drawn over it from twelve o'clock. */
@Composable
private fun ProgressRing(fraction: Float) {
    Canvas(Modifier.size(RING)) {
        val stroke = RING_STROKE.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = Color.White.copy(alpha = TRACK_ALPHA),
            startAngle = 0f,
            sweepAngle = FULL_TURN,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(stroke),
        )
        drawArc(
            color = Color.White,
            startAngle = TWELVE_O_CLOCK,
            sweepAngle = FULL_TURN * fraction.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

private val SCRIM = Color.Black.copy(alpha = 0.45f)
private val RING = 36.dp
private val RING_STROKE = 3.dp
private const val TRACK_ALPHA = 0.3f
private const val FULL_TURN = 360f
private const val TWELVE_O_CLOCK = -90f
private const val FULL_PERCENT = 100f
