package com.zillit.desktop.core.media

/**
 * The rotation a phone wrote into an MP4, in degrees clockwise.
 *
 * Every phone records in one physical orientation and describes the rest with
 * a 3×3 matrix in the track header, rather than rotating the pixels. A browser
 * and both phone players honour it; **JavaFX does not** (JDK-8091132), which is
 * why a portrait clip plays on its side in the desktop viewer unless something
 * reads this and turns the picture back.
 *
 * Reading it is a box walk, not a parse: `moov` → `trak` → `tkhd`, and the
 * matrix is nine 32-bit words near the end of the track header. Its top-left
 * four carry the rotation, as 16.16 fixed point — ±1 and 0 for every multiple
 * of 90, which is all any camera writes.
 */
object Mp4Rotation {

    /** Degrees clockwise: 0, 90, 180 or 270. Anything unreadable is 0. */
    fun of(bytes: ByteArray): Int = find(bytes) ?: 0

    /**
     * The rotation, or null when these bytes hold no track header at all.
     *
     * The difference matters to the caller: *no* `moov` in this range means ask
     * for a different range (a clip that was never prepared for streaming keeps
     * its `moov` at the end of the file), while a header that says zero means
     * the picture is upright and nothing more need be fetched.
     */
    fun find(bytes: ByteArray): Int? = runCatching {
        // From the top for a file that starts with its boxes…
        walk(bytes, 0, bytes.size)
        // …and otherwise from wherever `moov` appears, because a tail range
        // begins in the middle of `mdat` and lines up with no box at all.
            ?: moovOffsets(bytes).firstNotNullOfOrNull { at -> walk(bytes, at, bytes.size) }
    }.getOrNull()

    /**
     * The video sample format — `avc1`, `hvc1`, `dvh1` and the like — or null
     * when these bytes hold no sample description.
     *
     * Worth knowing because JavaFX takes some and silently stalls on others:
     * no error, no `ready`, a spinner that turns for ever. Naming the format
     * turns that into something a log can answer.
     *
     * The audio track's description sits in the same file, so the answer is
     * the first entry whose format is one of the video ones — an audio entry
     * (`mp4a`, `alac`) is simply not in the list.
     */
    fun videoCodec(bytes: ByteArray): String? = runCatching {
        sampleFormats(bytes, 0, bytes.size)
            ?: moovOffsets(bytes).firstNotNullOfOrNull { at -> sampleFormats(bytes, at, bytes.size) }
    }.getOrNull()

    /** Walks towards `stsd`, answering the first video sample format in it. */
    private fun sampleFormats(bytes: ByteArray, from: Int, until: Int): String? {
        var at = from
        while (at + BOX_HEADER <= until) {
            val size = boxSize(bytes, at, until) ?: return null
            val type = String(bytes, at + TYPE_AT, TYPE_LENGTH, Charsets.US_ASCII)
            val end = minOf(at + size, until.toLong()).toInt()
            val found = when (type) {
                in SAMPLE_PATH -> sampleFormats(bytes, at + BOX_HEADER, end)
                STSD_TYPE -> firstVideoFormat(bytes, at + BOX_HEADER, end)
                else -> null
            }
            if (found != null) return found
            at = end
        }
        return null
    }

    /**
     * The first video entry in an `stsd` payload.
     *
     * After the version/flags word and the entry count, each entry is an
     * ordinary box: a size, then the four-character format.
     */
    private fun firstVideoFormat(bytes: ByteArray, from: Int, end: Int): String? {
        var at = from + WORD + WORD
        while (at + BOX_HEADER <= end) {
            val size = boxSize(bytes, at, end) ?: return null
            val format = String(bytes, at + TYPE_AT, TYPE_LENGTH, Charsets.US_ASCII)
            if (format in VIDEO_FORMATS) return format
            at = minOf(at + size, end.toLong()).toInt()
        }
        return null
    }

    /** Walks sibling boxes in `[from, until)`, descending towards `tkhd`. */
    private fun walk(bytes: ByteArray, from: Int, until: Int): Int? {
        var at = from
        while (at + BOX_HEADER <= until) {
            val size = boxSize(bytes, at, until) ?: return null
            val type = String(bytes, at + TYPE_AT, TYPE_LENGTH, Charsets.US_ASCII)
            val end = minOf(at + size, until.toLong()).toInt()
            val found = when (type) {
                MOOV_TYPE, TRAK_TYPE -> walk(bytes, at + BOX_HEADER, end)
                TKHD_TYPE -> matrixRotation(bytes, at + BOX_HEADER, end)
                else -> null
            }
            if (found != null) return found
            at = end
        }
        return null
    }

    /** A box's length, or null when its header does not make sense. */
    private fun boxSize(bytes: ByteArray, at: Int, until: Int): Long? {
        val declared = bytes.int32(at).toLong() and UNSIGNED
        val size = when (declared) {
            // Zero means "to the end of the file"; one means a 64-bit size next.
            TO_END_OF_FILE -> (until - at).toLong()
            LARGE_SIZE -> if (at + LARGE_HEADER <= until) bytes.int64(at + BOX_HEADER) else null
            else -> declared
        }
        return size?.takeIf { it >= BOX_HEADER }
    }

    /**
     * The rotation in a `tkhd` payload, or null when it is too short.
     *
     * The matrix sits at a fixed offset from the *end* of the box — after it
     * come only width and height — so it is found by counting back rather than
     * by adding up the version-dependent fields in front of it.
     */
    private fun matrixRotation(bytes: ByteArray, from: Int, end: Int): Int? {
        val matrix = end - TRAILING_AFTER_MATRIX - MATRIX_BYTES
        if (matrix < from) return null
        // Only a visual track has a shape, and only a visual track can be
        // rotated: an audio `tkhd` is zero by zero and carries the identity
        // matrix whatever the camera did. Answering from the first track header
        // in the file reads the sound track on anything that puts audio first,
        // and reports every clip as upright. Skipping it here lets the walk
        // carry on to the next `trak`.
        val width = bytes.fixed(matrix + MATRIX_BYTES)
        val height = bytes.fixed(matrix + MATRIX_BYTES + WORD)
        if (width == 0 && height == 0) return null
        // The nine words are laid out in rows — a b u / c d v / x y w — so the
        // second row starts at word three, not word two. Reading the matrix as
        // four consecutive words takes `u` for `c` and finds no rotation in a
        // file that plainly has one.
        val corners = Corners(
            a = bytes.fixed(matrix),
            b = bytes.fixed(matrix + WORD),
            c = bytes.fixed(matrix + WORD * ROW_TWO),
            d = bytes.fixed(matrix + WORD * (ROW_TWO + 1)),
        )
        // The identity, and anything a camera does not write (a shear, a flip),
        // fall through to upright rather than being guessed at.
        return TURNS[corners] ?: 0
    }

    /** The matrix corners that decide the turn. */
    private data class Corners(val a: Int, val b: Int, val c: Int, val d: Int)

    /** Every offset where a `moov` box could begin — its size word precedes its type. */
    private fun moovOffsets(bytes: ByteArray): List<Int> = buildList {
        for (at in TYPE_AT until bytes.size - TYPE_LENGTH) {
            if (bytes.startsWith(at, MOOV_MARKER)) add(at - TYPE_AT)
        }
    }

    private fun ByteArray.startsWith(at: Int, marker: ByteArray): Boolean =
        marker.indices.all { this[at + it] == marker[it] }

    private fun ByteArray.int32(at: Int): Int =
        (0 until WORD).fold(0) { packed, byte ->
            (packed shl BITS_PER_BYTE) or (this[at + byte].toInt() and BYTE)
        }

    private fun ByteArray.int64(at: Int): Long =
        (int32(at).toLong() and UNSIGNED shl BITS_PER_INT) or (int32(at + WORD).toLong() and UNSIGNED)

    /** 16.16 fixed point, rounded — every camera writes exactly ±1 or 0. */
    private fun ByteArray.fixed(at: Int): Int = Math.round(int32(at) / FIXED_ONE.toFloat())

    private val TURNS = mapOf(
        Corners(a = 0, b = 1, c = -1, d = 0) to DEGREES_90,
        Corners(a = -1, b = 0, c = 0, d = -1) to DEGREES_180,
        Corners(a = 0, b = -1, c = 1, d = 0) to DEGREES_270,
    )

    private val MOOV_MARKER = "moov".toByteArray(Charsets.US_ASCII)

    private const val MOOV_TYPE = "moov"
    private const val TRAK_TYPE = "trak"
    private const val TKHD_TYPE = "tkhd"
    private const val STSD_TYPE = "stsd"

    /** The containers between `moov` and the sample description. */
    private val SAMPLE_PATH = setOf("moov", "trak", "mdia", "minf", "stbl")

    /**
     * Every four-character code that names a picture rather than a sound.
     *
     * Deliberately a list of video formats rather than "not audio": an `stsd`
     * also carries timed metadata (`mebx` on anything an iPhone shot) and
     * subtitle entries, and neither is the track being asked about.
     */
    private val VIDEO_FORMATS = setOf(
        "avc1", "avc3", "hvc1", "hev1", "dvh1", "dvhe", "av01", "vp08", "vp09", "mp4v",
    )

    private const val BYTE = 0xFF
    private const val UNSIGNED = 0xFFFFFFFFL
    private const val BITS_PER_BYTE = 8
    private const val BITS_PER_INT = 32
    private const val WORD = 4

    private const val BOX_HEADER = 8
    private const val LARGE_HEADER = 16
    private const val TYPE_AT = 4
    private const val TYPE_LENGTH = 4
    private const val TO_END_OF_FILE = 0L
    private const val LARGE_SIZE = 1L

    private const val MATRIX_BYTES = 36

    /** The matrix's second row, in words. */
    private const val ROW_TWO = 3

    /** `width` and `height`, the only fields after the matrix in a `tkhd`. */
    private const val TRAILING_AFTER_MATRIX = 8

    private const val FIXED_ONE = 1 shl 16
    private const val DEGREES_90 = 90
    private const val DEGREES_180 = 180
    private const val DEGREES_270 = 270
}
