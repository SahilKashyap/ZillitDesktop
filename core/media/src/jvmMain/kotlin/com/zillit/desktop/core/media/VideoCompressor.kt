package com.zillit.desktop.core.media

import com.zillit.desktop.core.common.OperatingSystem
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.currentPlatform
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Shrinks a large video before it is uploaded, without making it look worse
 * than a phone's own recording.
 *
 * The bar is "no visible loss", not "smallest file": the output is H.264 in
 * an MP4 (what every client plays — the desktop's JavaFX player, the web, old
 * Android phones), capped at 1080p on its short edge, at a quality where the
 * encoder spends bits wherever the picture needs them.
 *
 * Two encoders, tried in order, neither of them shipped with the app:
 *
 *  - macOS's own `avconvert` (AVFoundation, the hardware encoder). It applies
 *    a phone's rotation, maps an iPhone's HDR (HLG) to ordinary colour rather
 *    than washing it out, and keeps the audio. A 60 s 1080p iPhone clip at
 *    13 Mbit/s came out at 10.5 Mbit/s in 12 s; a 4K one shrinks far more.
 *  - `ffmpeg`, wherever the machine already has one (Windows, Linux, or a
 *    Mac whose `avconvert` refused the file): x264 at CRF 20.
 *
 * Anything that goes wrong — no encoder, an encoder that fails, an output no
 * smaller than the input — answers null, and the caller sends the original.
 * A video that could not be compressed is still a video the sender meant to
 * send.
 */
object VideoCompressor {

    /** 100 MB: smaller videos go as they are. */
    const val COMPRESS_ABOVE_BYTES: Long = 100L * 1024 * 1024

    /** Whether a file of this type and size should be compressed before upload. */
    fun wants(contentType: String, sizeBytes: Long): Boolean =
        contentType.startsWith("video/", ignoreCase = true) && sizeBytes > COMPRESS_ABOVE_BYTES

    /**
     * The compressed file, or null when the original should be sent instead.
     *
     * [onProgress] receives 0..100 as the encoder reports it.
     */
    suspend fun compress(
        name: String,
        bytes: ByteArray,
        onProgress: (Int) -> Unit = {},
    ): CompressedVideo? = withContext(Dispatchers.IO) {
        val work = Files.createTempDirectory("zillit-video").toFile()
        try {
            val extension = name.substringAfterLast('.', "").lowercase().ifBlank { "mov" }
            val source = File(work, "source.$extension").apply { writeBytes(bytes) }
            val output = File(work, "compressed.mp4")
            for (encoder in encoders()) {
                output.delete()
                val started = System.currentTimeMillis()
                if (!run(encoder.command(source, output), encoder.progress, onProgress)) continue
                val size = output.length()
                ZillitLog.i(TAG) {
                    "${encoder.label}: ${bytes.size} → $size bytes in ${System.currentTimeMillis() - started} ms"
                }
                // Already efficient (an HEVC phone clip can be): the original
                // is the better file to send.
                if (size <= 0 || size >= bytes.size) return@withContext null
                onProgress(PERCENT)
                return@withContext CompressedVideo(
                    name = name.substringBeforeLast('.', name) + ".mp4",
                    contentType = "video/mp4",
                    bytes = output.readBytes(),
                )
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            ZillitLog.e(TAG, error) { "compression failed; sending the original" }
            null
        } finally {
            work.deleteRecursively()
        }
    }

    /** What this machine can encode with, best first. */
    private fun encoders(): List<Encoder> = buildList {
        if (currentPlatform().os == OperatingSystem.MacOs && File(AVCONVERT).canExecute()) add(Avconvert)
        findFfmpeg()?.let { add(Ffmpeg(it)) }
    }.also { if (it.isEmpty()) ZillitLog.w(TAG) { "no video encoder on this machine" } }

    /**
     * Runs one encoder to completion, narrating its progress. False when it
     * could not start or did not finish cleanly.
     */
    private suspend fun run(command: List<String>, progress: ProgressReader, onProgress: (Int) -> Unit): Boolean {
        val process = runCatching { ProcessBuilder(command).redirectErrorStream(true).start() }
            .getOrElse { error ->
                ZillitLog.w(TAG) { "${command.first()} would not start: $error" }
                return false
            }
        val tail = StringBuilder()
        try {
            process.outputStream.close()
            val reader = process.inputStream.bufferedReader()
            val buffer = CharArray(BUFFER_CHARS)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = reader.read(buffer)
                if (read < 0) break
                tail.append(buffer, 0, read)
                progress.read(tail)?.let { onProgress(it.coerceIn(0, PERCENT)) }
                // Keep enough to finish a line split across reads, and for
                // the log when the encoder fails.
                if (tail.length > TAIL_CHARS) tail.delete(0, tail.length - TAIL_CHARS / 2)
            }
            val exit = process.waitFor()
            if (exit != 0) ZillitLog.w(TAG) { "${command.first()} exited $exit: ${tail.takeLast(LOG_CHARS)}" }
            return exit == 0
        } finally {
            // A cancelled send must not leave an encoder running on the machine.
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun findFfmpeg(): String? {
        val name = if (currentPlatform().os == OperatingSystem.Windows) "ffmpeg.exe" else "ffmpeg"
        // An app launched from the Dock or Finder does not inherit the shell's
        // PATH, so the usual Homebrew homes are looked at by name too.
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        return (path + FFMPEG_HOMES)
            .filter { it.isNotBlank() }
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    private const val TAG = "VideoCompress"
    private const val AVCONVERT = "/usr/bin/avconvert"
    private val FFMPEG_HOMES = listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
    private const val PERCENT = 100
    private const val BUFFER_CHARS = 1024
    private const val TAIL_CHARS = 16_384
    private const val LOG_CHARS = 400
}

/** A video [VideoCompressor] made smaller, ready to upload in the original's place. */
class CompressedVideo(val name: String, val contentType: String, val bytes: ByteArray) {
    override fun toString(): String = "CompressedVideo(type=$contentType, size=${bytes.size})"
}

/** Reads the newest progress percentage out of what an encoder has printed so far. */
internal fun interface ProgressReader {
    fun read(output: CharSequence): Int?
}

internal sealed interface Encoder {
    val label: String
    val progress: ProgressReader
    fun command(source: File, output: File): List<String>
}

/**
 * Apple's converter. `Preset1920x1080` is H.264 that scales down to fit
 * 1920×1080 and never up; the bitrate is Apple's own for that size, which
 * is well clear of where blocks start to show.
 */
internal data object Avconvert : Encoder {
    override val label = "avconvert"
    override val progress = ProgressReader(::avconvertProgress)
    override fun command(source: File, output: File) = listOf(
        "/usr/bin/avconvert",
        "--source", source.absolutePath,
        "--preset", "Preset1920x1080",
        "--output", output.absolutePath,
        "--replace",
        "--progress",
    )
}

/**
 * x264 at CRF 20 — constant quality, not a fixed bitrate, so a dark static
 * shot and a fast pan both look the way they did. The long edge is capped at
 * 1920 (never scaled up), dimensions kept even for 4:2:0, audio re-encoded to
 * AAC so any source container fits in an MP4, and the index moved to the
 * front so playback can start before the download ends.
 */
internal class Ffmpeg(private val executable: String) : Encoder {
    override val label = "ffmpeg"
    override val progress = FfmpegProgress()
    override fun command(source: File, output: File) =
        ffmpegCommand(executable, source.absolutePath, output.absolutePath)
}

internal fun ffmpegCommand(executable: String, source: String, output: String): List<String> = listOf(
    executable,
    "-hide_banner", "-nostdin", "-y",
    "-i", source,
    "-map", "0:v:0", "-map", "0:a:0?",
    "-vf", FFMPEG_SCALE,
    "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p",
    "-c:a", "aac", "-b:a", "160k",
    "-movflags", "+faststart",
    "-progress", "pipe:1",
    output,
)

/** Long edge at most 1920, aspect kept, both sides even. Quoted so the commas stay inside. */
internal const val FFMPEG_SCALE =
    "scale='if(gte(iw,ih),trunc(min(iw,1920)/2)*2,-2)':'if(gte(iw,ih),-2,trunc(min(ih,1920)/2)*2)'"

/** `avconvert progress:  45.17% complete` — the last one printed wins. */
internal fun avconvertProgress(output: CharSequence): Int? =
    AVCONVERT_PERCENT.findAll(output).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()?.toInt()

private val AVCONVERT_PERCENT = Regex("""progress:\s*([0-9]+(?:\.[0-9]+)?)%""")

/**
 * ffmpeg prints the input's `Duration: 00:01:23.45` once, then `out_time_us=`
 * (or the older, misnamed `out_time_ms=`, also microseconds) per progress
 * block; the share of one in the other is the percentage. The duration is
 * remembered, since the output buffer it came from is trimmed as it grows.
 */
internal class FfmpegProgress : ProgressReader {
    private var durationMicros: Long = 0

    override fun read(output: CharSequence): Int? {
        if (durationMicros <= 0) {
            FFMPEG_DURATION.find(output)?.destructured?.let { (h, m, s) ->
                durationMicros = ((h.toLong() * SECONDS_PER_HOUR + m.toLong() * SECONDS_PER_MINUTE) * MICROS +
                    (s.toDouble() * MICROS).toLong())
            }
        }
        val total = durationMicros.takeIf { it > 0 } ?: return null
        val done = FFMPEG_OUT_TIME.findAll(output).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return (done * PERCENT_OF / total).toInt()
    }

    private companion object {
        const val MICROS = 1_000_000L
        const val SECONDS_PER_HOUR = 3600L
        const val SECONDS_PER_MINUTE = 60L
        const val PERCENT_OF = 100L
    }
}

private val FFMPEG_DURATION = Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""")
private val FFMPEG_OUT_TIME = Regex("""out_time_(?:us|ms)=(\d+)""")
