package com.zillit.desktop

import com.zillit.desktop.core.media.ffmpegPosterJpeg
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.zillit.desktop.core.media.LocalPreviewPosterMaker
import com.zillit.desktop.core.media.Mp4Probe
import com.zillit.desktop.core.media.PreviewItem
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.media.PreviewPoster
import com.zillit.desktop.core.media.PreviewPosterMaker
import com.zillit.desktop.core.media.VideoCompressor
import com.zillit.desktop.core.media.VideoInfo
import com.zillit.desktop.feature.home.data.pdfPageCount
import com.zillit.desktop.feature.home.data.pdfThumbnailJpeg
import com.zillit.desktop.feature.home.data.videoThumbnailJpeg

/**
 * What the media editor shows for a file that is not a picture, WhatsApp's
 * way: a PDF's first page under its page count, a clip's frame. The same
 * renders the boards already make for their posters (PDFBox, the video
 * frame grab); anything else keeps its badge.
 */
internal object DesktopPreviewPosters : PreviewPosterMaker {
    override suspend fun poster(item: PreviewItem): PreviewPoster? = when {
        // Sharper than a bubble's poster: this page fills the editor's stage.
        item.isPdf() -> PreviewPoster(
            pdfThumbnailJpeg(item.bytes, dpi = STAGE_DPI, maxEdge = STAGE_EDGE)?.jpegBytes,
            pages = pdfPageCount(item.bytes),
        )
        // jcodec reads H.264 only; FFmpeg cuts the frame for AV1, HEVC, VP9.
        item.kind == PreviewKind.Video -> PreviewPoster(
            videoThumbnailJpeg(item.bytes)?.jpegBytes
                ?: ffmpegPosterJpeg(item.bytes, item.name, maxEdge = STAGE_EDGE)?.jpegBytes,
        )
        else -> null
    }

    /**
     * What the clip says about itself, so the preview can price each quality:
     * its length and shape out of its own boxes, the sample description's
     * format to warn about a clip nobody could draw, and whether this machine
     * has an encoder at all. All header reads — no decode, no temporary copy.
     */
    override suspend fun videoInfo(item: PreviewItem): VideoInfo {
        val facts = Mp4Probe.of(item.bytes)
        return VideoInfo(
            durationSeconds = facts?.seconds,
            width = facts?.width,
            height = facts?.height,
            unplayableFormat = VideoCompressor.unplayableFormat(item.bytes),
            compressible = VideoCompressor.canCompressHere(),
        )
    }

    private const val STAGE_DPI = 144f
    private const val STAGE_EDGE = 1600

    private fun PreviewItem.isPdf(): Boolean =
        contentType.equals("application/pdf", ignoreCase = true) || name.endsWith(".pdf", ignoreCase = true)
}

/** Installs [DesktopPreviewPosters] for everything composed inside [content]. */
@Composable
internal fun PreviewPostersMount(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPreviewPosterMaker provides DesktopPreviewPosters, content = content)
}
