package com.zillit.desktop.core.media

/**
 * How long an MP4 runs and how big its picture is, read from the file's own
 * boxes — the two things the preview needs to say what a quality would weigh.
 *
 * The same box walk [Mp4Rotation] does, for two other headers:
 *
 *  - `moov` → `mvhd`, which carries a timescale (ticks a second) and a
 *    duration in those ticks. The movie header, not a track's, so a clip
 *    whose video and audio run to different lengths answers once.
 *  - `moov` → `trak` → `tkhd`, whose last two words are the track's width
 *    and height in 16.16 fixed point. Only a visual track has a shape, so an
 *    audio header — zero by zero — is walked past rather than believed.
 *
 * Everything a phone or a camera writes is MP4 or QuickTime, which share this
 * layout. Anything else (MKV, WebM, AVI) answers null and the preview simply
 * shows no figure, which is better than showing a wrong one.
 */
object Mp4Probe {

    /** Length and shape, or null where neither header could be read. */
    fun of(bytes: ByteArray): Mp4Facts? = runCatching {
        val facts = facts(bytes, 0, bytes.size)
            // A clip that was never prepared for streaming keeps its `moov`
            // at the end, where a walk from byte zero lands inside `mdat`.
            ?: moovOffsets(bytes).firstNotNullOfOrNull { at -> facts(bytes, at, bytes.size) }
        facts?.takeIf { it.seconds != null || it.width != null }
    }.getOrNull()

    /**
     * Walks siblings in `[from, until)`, gathering the movie header's length
     * and the first visual track's shape — either of which may be missing
     * from the range in hand.
     */
    private fun facts(bytes: ByteArray, from: Int, until: Int): Mp4Facts? {
        var found: Mp4Facts? = null
        var at = from
        while (at + BOX_HEADER <= until) {
            val size = boxSize(bytes, at, until) ?: return found
            val type = String(bytes, at + TYPE_AT, TYPE_LENGTH, Charsets.US_ASCII)
            val end = minOf(at + size, until.toLong()).toInt()
            val here = when (type) {
                MOOV_TYPE, TRAK_TYPE -> facts(bytes, at + BOX_HEADER, end)
                MVHD_TYPE -> duration(bytes, at + BOX_HEADER, end)?.let { Mp4Facts(seconds = it) }
                TKHD_TYPE -> shape(bytes, at + BOX_HEADER, end)
                else -> null
            }
            found = found.merge(here)
            at = end
        }
        return found
    }

    /** Whichever of the two halves each side knows; the first answer wins a tie. */
    private fun Mp4Facts?.merge(other: Mp4Facts?): Mp4Facts? = when {
        this == null -> other
        other == null -> this
        else -> Mp4Facts(
            seconds = seconds ?: other.seconds,
            width = width ?: other.width,
            height = height ?: other.height,
        )
    }

    /**
     * The movie's length in seconds from an `mvhd` payload.
     *
     * Version 0 writes two 32-bit times then a 32-bit timescale and duration;
     * version 1 writes 64-bit times and a 64-bit duration. The version is the
     * payload's first byte. A duration of all-ones means "unknown", which a
     * growing recording writes.
     */
    private fun duration(bytes: ByteArray, from: Int, end: Int): Double? {
        val version = bytes[from].toInt() and BYTE
        val fields = from + WORD // past version and flags
        val (timescale, ticks) = if (version == 1) {
            if (fields + V1_HEADER > end) return null
            bytes.int32(fields + LONG + LONG) to bytes.int64(fields + LONG + LONG + WORD)
        } else {
            if (fields + V0_HEADER > end) return null
            bytes.int32(fields + WORD + WORD) to (bytes.int32(fields + WORD + WORD + WORD).toLong() and UNSIGNED)
        }
        if (timescale <= 0 || ticks <= 0 || ticks == UNSIGNED) return null
        return ticks.toDouble() / timescale
    }

    /**
     * A visual track's width and height from a `tkhd` payload: the two words
     * after the matrix, which sits a fixed distance from the box's end.
     */
    private fun shape(bytes: ByteArray, from: Int, end: Int): Mp4Facts? {
        val trailing = end - TRAILING_AFTER_MATRIX
        if (trailing < from) return null
        val width = bytes.fixed(trailing)
        val height = bytes.fixed(trailing + WORD)
        // Zero by zero is the sound track; a real picture has both.
        if (width <= 0 || height <= 0) return null
        return Mp4Facts(width = width, height = height)
    }

    /** A box's length, or null when its header does not make sense. */
    private fun boxSize(bytes: ByteArray, at: Int, until: Int): Long? {
        val declared = bytes.int32(at).toLong() and UNSIGNED
        val size = when (declared) {
            TO_END_OF_FILE -> (until - at).toLong()
            LARGE_SIZE -> if (at + LARGE_HEADER <= until) bytes.int64(at + BOX_HEADER) else null
            else -> declared
        }
        return size?.takeIf { it >= BOX_HEADER }
    }

    /** Every offset where a `moov` box could begin — its size word precedes its type. */
    private fun moovOffsets(bytes: ByteArray): List<Int> = buildList {
        for (at in TYPE_AT until bytes.size - TYPE_LENGTH) {
            if (MOOV_MARKER.indices.all { bytes[at + it] == MOOV_MARKER[it] }) add(at - TYPE_AT)
        }
    }

    private fun ByteArray.int32(at: Int): Int =
        (0 until WORD).fold(0) { packed, byte ->
            (packed shl BITS_PER_BYTE) or (this[at + byte].toInt() and BYTE)
        }

    private fun ByteArray.int64(at: Int): Long =
        (int32(at).toLong() and UNSIGNED shl BITS_PER_INT) or (int32(at + WORD).toLong() and UNSIGNED)

    /** 16.16 fixed point, rounded — a frame size is always whole pixels. */
    private fun ByteArray.fixed(at: Int): Int = Math.round(int32(at) / FIXED_ONE.toFloat())

    private val MOOV_MARKER = "moov".toByteArray(Charsets.US_ASCII)

    private const val MOOV_TYPE = "moov"
    private const val TRAK_TYPE = "trak"
    private const val MVHD_TYPE = "mvhd"
    private const val TKHD_TYPE = "tkhd"

    private const val BYTE = 0xFF
    private const val UNSIGNED = 0xFFFFFFFFL
    private const val BITS_PER_BYTE = 8
    private const val BITS_PER_INT = 32
    private const val WORD = 4
    private const val LONG = 8

    private const val BOX_HEADER = 8
    private const val LARGE_HEADER = 16
    private const val TYPE_AT = 4
    private const val TYPE_LENGTH = 4
    private const val TO_END_OF_FILE = 0L
    private const val LARGE_SIZE = 1L

    /** Creation, modification, timescale and duration, in words. */
    private const val V0_HEADER = 16

    /** The same four, with 64-bit times and duration. */
    private const val V1_HEADER = 28

    /** `width` and `height`, the only fields after a `tkhd`'s matrix. */
    private const val TRAILING_AFTER_MATRIX = 8

    private const val FIXED_ONE = 1 shl 16
}

/** What [Mp4Probe] could read; either half may be missing. */
data class Mp4Facts(
    val seconds: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
)
