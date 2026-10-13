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
 * Re-encodes a video to the size the sender picked — [VideoSend.Sd] (480p) or
 * [VideoSend.Hd] (1080p) — without making it look worse than that size can.
 *
 * The output is H.264 in an MP4, which every client plays: the desktop's
 * JavaFX player, the web, old Android phones. The long edge is capped at the
 * tier's and never raised, so a 720p clip sent as HD keeps its own pixels.
 *
 * Two encoders, tried in order:
 *
 *  - FFmpeg, bundled with the app (`$APPDIR/resources/ffmpeg`, staged by the
 *    build's `stageFfmpeg`), or the machine's own when a build has none. It
 *    decodes what AVFoundation cannot (AV1 without an AV1 chip, VP9) and
 *    hands the frames to the operating system's own H.264 encoder — Apple's
 *    VideoToolbox, Windows' Media Foundation — never to a bundled one: the
 *    LGPL build ships none, and the OS encoder carries its patent licence.
 *    The only route on Windows. First because it is the only one that hits a
 *    named resolution and bitrate exactly, which is what the preview has
 *    already promised the sender; it also compresses harder — a 104 MB iPhone
 *    clip came out at 53 MB where `avconvert` left 80 MB. Slower for exotic
 *    sources: an 8K AV1 file decodes in software at a tenth of real time.
 *  - macOS's own `avconvert` (AVFoundation, the hardware encoder), for a
 *    build with no FFmpeg beside it. It applies a phone's rotation, maps an
 *    iPhone's HDR (HLG) to ordinary colour rather than washing it out, and
 *    keeps the audio, but only at one of Apple's own fixed presets.
 *
 * Anything that goes wrong — no encoder, an encoder that fails, an output no
 * smaller than the input — answers null, and the caller sends the original.
 * A video that could not be compressed is still a video the sender meant to
 * send.
 */
object VideoCompressor {

    /** 100 MB: the size above which a send with no preview behind it compresses unasked. */
    const val COMPRESS_ABOVE_BYTES: Long = LARGE_VIDEO_BYTES

    /**
     * Whether this machine can re-encode at all. False on Linux and in a
     * build with no FFmpeg staged, where the preview offers only the
     * original rather than a quality that could never be produced.
     */
    fun canCompressHere(): Boolean = encoders(VideoSend.Hd).isNotEmpty()

    /** "AV1" for a video in a format most players load but cannot draw; null otherwise. */
    fun unplayableFormat(bytes: ByteArray): String? = UNPLAYABLE_FORMATS[Mp4Rotation.videoCodec(bytes)]

    /** What the app's player (JavaFX), Safari and many phones load but cannot draw. */
    val UNPLAYABLE_FORMATS: Map<String, String> = mapOf("av01" to "AV1", "vp09" to "VP9", "vp08" to "VP8")

    /**
     * Whether a file of this type and size is compressed when nobody was
     * asked — a send from a surface with no preview dialog in front of it.
     */
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
        /** Which size to aim at; [VideoSend.Original] asks for no encode at all. */
        quality: VideoSend = VideoSend.Hd,
        /**
         * Whether a result no smaller than the input is thrown away. Not for a
         * clip in a format most players cannot draw (AV1): there the point is
         * the H.264, whatever it weighs.
         */
        requireSmaller: Boolean = true,
        onProgress: (Int) -> Unit = {},
    ): CompressedVideo? = withContext(Dispatchers.IO) {
        if (quality == VideoSend.Original) return@withContext null
        val work = Files.createTempDirectory("zillit-video").toFile()
        // The source is copied out, and the output written beside it: with
        // the disk short of both, an encode dies (ENOSPC) minutes in — a 1.8 GB
        // 8K clip got 19 minutes through before it did. Better not to start.
        val needed = bytes.size.toLong() * 2 + DISK_MARGIN_BYTES
        if (work.usableSpace < needed) {
            ZillitLog.w(TAG) { "not compressing: ${work.usableSpace} bytes free, $needed needed" }
            work.deleteRecursively()
            return@withContext null
        }
        try {
            val extension = name.substringAfterLast('.', "").lowercase().ifBlank { "mov" }
            val source = File(work, "source.$extension").apply { writeBytes(bytes) }
            val output = File(work, "compressed.mp4")
            for (encoder in encoders(quality)) {
                output.delete()
                val started = System.currentTimeMillis()
                if (!run(encoder.command(source, output), encoder.progress, onProgress)) continue
                val size = output.length()
                ZillitLog.i(TAG) {
                    "${encoder.label}: ${bytes.size} → $size bytes in ${System.currentTimeMillis() - started} ms"
                }
                // Already efficient (an HEVC phone clip can be): the original
                // is the better file to send.
                if (size <= 0 || (requireSmaller && size >= bytes.size)) return@withContext null
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

    /** What this machine can encode this tier with, best first. */
    private fun encoders(quality: VideoSend): List<Encoder> = buildList {
        findFfmpeg()?.let { add(Ffmpeg(it, FfmpegTarget.current(), quality)) }
        if (currentPlatform().os == OperatingSystem.MacOs && File(AVCONVERT).canExecute()) add(Avconvert(quality))
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
        // The first thing an encoder says is usually why it refused; the tail
        // alone, once a preset list has scrolled past, hid the reason.
        val head = StringBuilder()
        try {
            process.outputStream.close()
            val reader = process.inputStream.bufferedReader()
            val buffer = CharArray(BUFFER_CHARS)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = reader.read(buffer)
                if (read < 0) break
                tail.append(buffer, 0, read)
                if (head.length < LOG_CHARS) head.append(buffer, 0, minOf(read, LOG_CHARS - head.length))
                progress.read(tail)?.let { onProgress(it.coerceIn(0, PERCENT)) }
                // Keep enough to finish a line split across reads, and for
                // the log when the encoder fails.
                if (tail.length > TAIL_CHARS) tail.delete(0, tail.length - TAIL_CHARS / 2)
            }
            val exit = process.waitFor()
            if (exit != 0) {
                ZillitLog.w(TAG) {
                    "${command.first()} exited $exit: ${head.trim()} … ${tail.takeLast(LOG_CHARS).trim()}"
                }
            }
            return exit == 0
        } finally {
            // A cancelled send must not leave an encoder running on the machine.
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** The app's own FFmpeg first, then one the machine has. */
    internal fun findFfmpeg(): String? {
        val name = if (currentPlatform().os == OperatingSystem.Windows) "ffmpeg.exe" else "ffmpeg"
        bundledFfmpeg(name)?.let { return it }
        // An app launched from the Dock or Finder does not inherit the shell's
        // PATH, so the usual Homebrew homes are looked at by name too.
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        return (path + FFMPEG_HOMES)
            .filter { it.isNotBlank() }
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    /**
     * Compose names the resources directory in a system property, for a
     * package and for `:run` alike. A copy that lost its execute bit on the
     * way gets it back where the directory allows; where it does not (an
     * install owned by root), the program and its libraries are copied once
     * to a temporary folder of this user's and run from there.
     */
    private fun bundledFfmpeg(name: String): String? {
        val dir = System.getProperty(RESOURCES_DIR_PROPERTY)?.takeIf { it.isNotBlank() } ?: return null
        val home = File(dir, "ffmpeg")
        val program = File(home, name).takeIf { it.isFile } ?: return null
        if (!program.canExecute()) program.setExecutable(true, false)
        if (program.canExecute()) return program.absolutePath
        return runCatching {
            // Keyed by size, so a new app version's FFmpeg is a new copy.
            val copy = File(System.getProperty("java.io.tmpdir"), "zillit-ffmpeg-${program.length()}")
            home.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                val target = File(copy, file.name)
                if (target.length() != file.length()) file.copyTo(target, overwrite = true)
            }
            File(copy, name).takeIf { it.setExecutable(true, false) && it.canExecute() }?.absolutePath
        }.getOrElse { error ->
            ZillitLog.w(TAG) { "bundled ffmpeg is not runnable here: $error" }
            null
        }
    }

    private const val TAG = "VideoCompress"
    private const val AVCONVERT = "/usr/bin/avconvert"
    private const val RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"
    private val FFMPEG_HOMES = listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
    private const val PERCENT = 100
    private const val BUFFER_CHARS = 1024
    private const val TAIL_CHARS = 16_384
    private const val DISK_MARGIN_BYTES = 512L * 1024 * 1024
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
 * Apple's converter, at the preset nearest the tier: H.264 that scales down
 * to fit and never up, at Apple's own bitrate for that size. `Preset640x480`
 * fits a 16:9 clip into 640×360 rather than the 854×480 FFmpeg would hit, so
 * an SD file from here comes out smaller than the preview's figure — never
 * larger, which is the direction that would be a lie.
 */
internal class Avconvert(private val quality: VideoSend) : Encoder {
    override val label = "avconvert"
    override val progress = ProgressReader(::avconvertProgress)
    override fun command(source: File, output: File) = listOf(
        "/usr/bin/avconvert",
        "--source", source.absolutePath,
        "--preset", if (quality == VideoSend.Sd) "Preset640x480" else "Preset1920x1080",
        "--output", output.absolutePath,
        "--replace",
        "--progress",
    )
}

/** Which operating-system encoder FFmpeg hands its frames to. */
internal enum class FfmpegTarget {
    /** Apple silicon: VideoToolbox at constant quality, hardware decode where it can. */
    MacAppleSilicon,

    /** An Intel Mac: VideoToolbox there takes a bitrate, not a quality. */
    MacIntel,

    /** Media Foundation, whichever encoder the machine has (GPU or Microsoft's own). */
    Windows,
    ;

    companion object {
        fun current(): FfmpegTarget = when {
            currentPlatform().os == OperatingSystem.Windows -> Windows
            System.getProperty("os.arch").orEmpty().contains("aarch64") -> MacAppleSilicon
            else -> MacIntel
        }
    }
}

/**
 * FFmpeg reading anything, the system writing H.264. The long edge is capped
 * at the tier's (never scaled up), dimensions kept even for 4:2:0, audio
 * re-encoded to AAC so any source container fits in an MP4, and the index
 * moved to the front so playback can start before the download ends.
 */
internal class Ffmpeg(
    private val executable: String,
    private val target: FfmpegTarget,
    private val quality: VideoSend,
) : Encoder {
    override val label = "ffmpeg ${quality.name.lowercase()}"
    override val progress = FfmpegProgress()
    override fun command(source: File, output: File) =
        ffmpegCommand(executable, source.absolutePath, output.absolutePath, target, quality)
}

internal fun ffmpegCommand(
    executable: String,
    source: String,
    output: String,
    target: FfmpegTarget,
    quality: VideoSend = VideoSend.Hd,
): List<String> = buildList {
    val sd = quality == VideoSend.Sd
    add(executable)
    addAll(listOf("-hide_banner", "-nostdin", "-y"))
    // Decoding on the media engine where the chip has one for the source;
    // FFmpeg falls back to software by itself where it does not (AV1 on M1/M2).
    if (target == FfmpegTarget.MacAppleSilicon) addAll(listOf("-hwaccel", "videotoolbox"))
    val scale = ffmpegScale(quality.longEdge ?: HD_LONG_EDGE)
    addAll(listOf("-i", source, "-map", "0:v:0", "-map", "0:a:0?", "-vf", "$scale,format=yuv420p"))
    val bitrate = if (sd) SD_BITRATE else HD_BITRATE
    when (target) {
        // 1080p asks the hardware for a quality rather than a bitrate: at 65
        // of 100 a 60 s iPhone clip at 13 Mbit/s came out at 7 with no
        // visible blocking, where Apple's own 1080p preset spent 10.5. 480p
        // is given a bitrate instead — a constant quality at that size lands
        // wherever the picture leads, and the sender was shown a figure.
        FfmpegTarget.MacAppleSilicon -> addAll(
            listOf("-c:v", "h264_videotoolbox") +
                (if (sd) bitrate else listOf("-q:v", "65")) +
                listOf("-c:a", "aac_at"),
        )
        FfmpegTarget.MacIntel -> addAll(listOf("-c:v", "h264_videotoolbox") + bitrate + listOf("-c:a", "aac_at"))
        FfmpegTarget.Windows ->
            addAll(listOf("-c:v", "h264_mf", "-rate_control", "u_vbr") + bitrate + listOf("-c:a", "aac"))
    }
    val audio = if (sd) "128k" else "160k"
    addAll(listOf("-b:a", audio, "-movflags", "+faststart", "-progress", "pipe:1", output))
}

/** 10 Mbit/s, peaks to 14 — clear of blocking at 1080p60 where no quality mode exists. */
private val HD_BITRATE = listOf("-b:v", "10M", "-maxrate", "14M", "-bufsize", "20M")

/** 1.5 Mbit/s, peaks to 2 — what 480p needs to stay clean on movement. */
private val SD_BITRATE = listOf("-b:v", "1500k", "-maxrate", "2M", "-bufsize", "3M")

private const val HD_LONG_EDGE = 1920

/** Long edge at most [longEdge], aspect kept, both sides even. Quoted so the commas stay inside. */
internal fun ffmpegScale(longEdge: Int): String =
    "scale='if(gte(iw,ih),trunc(min(iw,$longEdge)/2)*2,-2)':'if(gte(iw,ih),-2,trunc(min(ih,$longEdge)/2)*2)'"

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
