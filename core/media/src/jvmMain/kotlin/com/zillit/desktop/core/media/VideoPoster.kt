package com.zillit.desktop.core.media

import com.zillit.desktop.core.common.ZillitLog
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

/**
 * A video's poster frame cut by FFmpeg — for the codecs the pure-Java poster
 * maker (jcodec) cannot read: AV1, HEVC, VP9, ProRes. Without it such a clip
 * went out with no thumbnail at all, and every client drew a file chip where
 * the picture should be.
 *
 * A frame a second in (the jcodec maker's choice too) — unless it is dark:
 * plenty of clips open on black, and a black poster says nothing, so a few
 * seconds later and then ten are tried before settling. Shrunk to [maxEdge]
 * and written as JPEG. Null when there is no FFmpeg, no room on disk for the
 * copy it reads, or no frame at all.
 */
suspend fun ffmpegPosterJpeg(bytes: ByteArray, name: String, maxEdge: Int = POSTER_EDGE): ImageThumbnail? =
    withContext(Dispatchers.IO) {
        val ffmpeg = VideoCompressor.findFfmpeg() ?: return@withContext null
        val work = Files.createTempDirectory("zillit-poster").toFile()
        try {
            if (work.usableSpace < bytes.size.toLong() + POSTER_DISK_MARGIN) return@withContext null
            val extension = name.substringAfterLast('.', "").lowercase().ifBlank { "mp4" }
            val source = File(work, "source.$extension").apply { writeBytes(bytes) }
            val frame = File(work, "poster.jpg")
            val frames = POSTER_TIMES.asSequence().mapNotNull { at -> frameAt(ffmpeg, source, frame, at, maxEdge) }
            var fallback: ImageThumbnail? = null
            // Lazily, so the first frame with a picture in it ends the search.
            frames.firstOrNull { poster ->
                if (fallback == null) fallback = poster
                !isDark(poster.jpegBytes)
            } ?: fallback
        } finally {
            work.deleteRecursively()
        }
    }

/** One frame [at] seconds in, as a thumbnail; null when there is none there. */
private fun frameAt(ffmpeg: String, source: File, frame: File, at: String, maxEdge: Int): ImageThumbnail? {
    frame.delete()
    if (!cutFrame(ffmpeg, source, frame, at, maxEdge) || frame.length() == 0L) return null
    val jpeg = frame.readBytes()
    val image = runCatching { Image.makeFromEncoded(jpeg) }.getOrNull() ?: return null
    return ImageThumbnail(jpeg, image.width, image.height)
}

private fun cutFrame(ffmpeg: String, source: File, frame: File, at: String, maxEdge: Int): Boolean {
    val command = listOf(
        ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-y",
        "-ss", at, "-i", source.absolutePath,
        "-frames:v", "1",
        "-vf", "scale=w=$maxEdge:h=$maxEdge:force_original_aspect_ratio=decrease",
        "-q:v", "3",
        frame.absolutePath,
    )
    val process = runCatching { ProcessBuilder(command).redirectErrorStream(true).start() }.getOrNull() ?: return false
    return try {
        process.outputStream.close()
        // Drained, so a chatty decoder cannot fill the pipe and stall.
        process.inputStream.bufferedReader().readText()
        process.waitFor(POSTER_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        ZillitLog.w("VideoPoster") { "ffmpeg poster failed: $error" }
        false
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}

/** Mean brightness under one-in-sixteen of full: a frame of black, or near enough. */
private fun isDark(jpeg: ByteArray): Boolean {
    val image = runCatching { javax.imageio.ImageIO.read(jpeg.inputStream()) }.getOrNull() ?: return false
    var total = 0L
    var samples = 0
    for (y in 0 until image.height step DARK_STEP) {
        for (x in 0 until image.width step DARK_STEP) {
            val rgb = java.awt.Color(image.getRGB(x, y))
            total += (rgb.red + rgb.green + rgb.blue) / CHANNELS
            samples++
        }
    }
    return samples > 0 && total / samples < DARK_MEAN
}

/** Seconds in, in the order tried; the start last, for a clip shorter than a second. */
private val POSTER_TIMES = listOf("1", "3", "10", "0")
private const val DARK_STEP = 8
private const val CHANNELS = 3
private const val DARK_MEAN = 16

/** The bubble's poster size — the jcodec maker's 480 on the long edge. */
private const val POSTER_EDGE = 480
private const val POSTER_DISK_MARGIN = 256L * 1024 * 1024
private const val POSTER_TIMEOUT_SECONDS = 120L
