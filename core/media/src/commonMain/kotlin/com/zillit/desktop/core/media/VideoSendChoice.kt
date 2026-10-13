package com.zillit.desktop.core.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** 100 MB: the size above which a video with no preview behind it is compressed unasked. */
const val LARGE_VIDEO_BYTES: Long = 100L * 1024 * 1024

/**
 * How a video leaves: re-encoded to one of two sizes, or byte for byte.
 *
 * Every video is offered all three, whatever it weighs — a 20 MB clip going
 * to someone on a phone's data is as worth shrinking as a 2 GB one. The
 * figures are what the encoders aim at, and what the option's "~38 MB" is
 * worked out from; the encoder's own judgement decides the real file.
 */
enum class VideoSend(
    /** The most pixels on the long edge; the short edge follows the picture's shape. */
    val longEdge: Int?,
    /** Video and audio together, as the encoder is asked to spend it. */
    val bitsPerSecond: Long?,
) {
    /** 480p — a small file for a slow line, still legible on a phone. */
    Sd(longEdge = SD_LONG_EDGE, bitsPerSecond = SD_BITS_PER_SECOND),

    /** 1080p — the quality bar the app has compressed to since large sends began. */
    Hd(longEdge = HD_LONG_EDGE, bitsPerSecond = HD_BITS_PER_SECOND),

    /** No re-encode at all: the file as the camera wrote it. */
    Original(longEdge = null, bitsPerSecond = null),
    ;

    /** The text on the option, before its size is put beside it. */
    val labelKey: String
        get() = when (this) {
            Sd -> S.desktop_video_quality_sd
            Hd -> S.desktop_video_quality_hd
            Original -> S.desktop_video_quality_original
        }
}

/**
 * Roughly what a video of [sourceBytes] will weigh sent this way, or null when
 * it cannot yet be said — the length is read off the file's header a moment
 * after the preview opens, and a figure invented before then would be a
 * figure that moves.
 *
 * Bitrate times length, with two sanity bounds a sender would otherwise catch
 * us out on: a clip already smaller than the tier is not blown up to it (the
 * encoder never scales up, so the bits follow the pixels it really has), and
 * no estimate is larger than the original, because a result no smaller is
 * thrown away and the original sent instead.
 *
 * That second bound is lifted for a clip in a format most players cannot draw:
 * there the H.264 is kept whatever it weighs, so it really can come out larger
 * than the file it replaces, and a measured 894 KB AV1 clip converts to about
 * 2 MB. Clamping it would promise a size the send then exceeds.
 */
fun VideoSend.estimatedBytes(sourceBytes: Long, video: VideoInfo?): Long? {
    val bits = bitsPerSecond ?: return sourceBytes
    if (video == null) return null
    val seconds = video.durationSeconds?.takeIf { it > 0 } ?: return null
    val estimate = (bits * seconds / BITS_PER_BYTE * shareOfTier(video)).toLong().coerceAtLeast(1)
    return if (video.unplayableFormat != null) estimate else estimate.coerceAtMost(sourceBytes)
}

/**
 * How much of this tier's bitrate a source of this size really needs: all of
 * it at the tier's own resolution or above, and less in proportion to the
 * pixels where the source is smaller — bits track area, so two thirds of the
 * width is a bit under half the bitrate.
 */
private fun VideoSend.shareOfTier(video: VideoInfo?): Double {
    val tier = longEdge ?: return 1.0
    val source = video?.longEdge?.takeIf { it > 0 } ?: return 1.0
    if (source >= tier) return 1.0
    val scale = source.toDouble() / tier
    return scale * scale
}

/**
 * Over the caption while a video is on screen: its size, the three ways it
 * can go with what each would weigh, and — for a clip in a format most
 * players cannot draw — why one of the two smaller ones is the kinder choice.
 *
 * Original is chosen for the sender already ([MediaPreviewSession.videoChoice]),
 * so Send is never held up; picking SD or HD is a deliberate act, and nothing
 * is ever re-encoded behind the sender's back.
 */
@Composable
internal fun VideoChoiceBar(
    item: PreviewItem,
    choice: VideoSend?,
    video: VideoInfo?,
    onChoose: (VideoSend) -> Unit,
) {
    val unplayable = video?.unplayableFormat
    // Nothing on this machine can re-encode (Linux, or a build with no
    // FFmpeg beside it): offering SD and HD would offer what cannot happen.
    val compressible = video?.compressible ?: true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.CenterHorizontally),
    ) {
        ZillitText(
            text = when {
                !compressible -> str(S.desktop_video_quality_unsupported)
                unplayable != null -> str(S.desktop_video_quality_unplayable, unplayable)
                else -> str(S.desktop_video_quality_prompt, formatBytes(item.bytes.size.toLong()))
            },
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.widthIn(max = PROMPT_MAX_WIDTH),
        )
        val offered = if (compressible) VideoSend.entries else listOf(VideoSend.Original)
        offered.forEach { tier ->
            ChoiceButton(
                text = tier.optionLabel(item, video),
                selected = choice == tier,
                onClick = { onChoose(tier) },
            )
        }
    }
}

/** "HD 1080p · ~190 MB", or the bare label until the clip's length is known. */
@Composable
private fun VideoSend.optionLabel(item: PreviewItem, video: VideoInfo?): String {
    val label = str(labelKey)
    val size = estimatedBytes(item.bytes.size.toLong(), video)?.let(::formatBytes) ?: return label
    // The original's size is known exactly; a re-encode's is a guess, and says so.
    // `docusign_bulk_meta` is "%1$s · %2$s" already translated everywhere — the
    // catalogue refuses a second key for text it holds.
    return if (this == VideoSend.Original) {
        str(S.docusign_bulk_meta, label, size)
    } else {
        str(S.desktop_video_quality_estimate, label, size)
    }
}

/** One of the three: filled when chosen, outlined when not. */
@Composable
private fun ChoiceButton(text: String, selected: Boolean, onClick: () -> Unit) {
    ZillitButton(
        text = text,
        onClick = onClick,
        variant = if (selected) ButtonVariant.Primary else ButtonVariant.Secondary,
        size = ButtonSize.Small,
    )
}

private val PROMPT_MAX_WIDTH = 360.dp

/**
 * The figures are measured, not nominal: a 1080p iPhone clip encoded by
 * VideoToolbox came out at 1.54 Mbit/s at SD (asked for 1.5 of picture and
 * 128 kbit/s of sound) and 6.6–7.0 Mbit/s at HD, where the constant quality is
 * asked for no bitrate at all. Both are set a little above what was measured,
 * so a sender is told a size the file then comes in under rather than over.
 */
private const val SD_LONG_EDGE = 854
private const val SD_BITS_PER_SECOND = 1_570_000L

private const val HD_LONG_EDGE = 1920
private const val HD_BITS_PER_SECOND = 7_300_000L

private const val BITS_PER_BYTE = 8
