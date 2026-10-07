package com.zillit.desktop.feature.selectstills.data

import com.zillit.desktop.feature.selectstills.domain.StillsPick

/**
 * The file system, as the upload needs it — one seam, so the queue and the
 * uploader are testable without a disk.
 *
 * Paths rather than bytes: a card is hundreds of files of tens of megabytes,
 * and a picker that read one to hand it on would exhaust the heap before the
 * first chunk left.
 */
interface StillsFileReader {

    /** Whether the file is still there; a drag can end with the card ejected. */
    fun exists(path: String): Boolean

    /** The whole file — only for the small ones (a headshot). */
    fun readAll(path: String): ByteArray?

    /**
     * The file in slices of at most [chunk] bytes, in order. [onChunk] is given
     * a buffer it must not keep and how much of it is filled.
     */
    suspend fun readInChunks(path: String, chunk: Int, onChunk: suspend (ByteArray, Int) -> Unit)

    /**
     * A cheap fingerprint — the size and a hash of the first and last 256 KB —
     * which is how the service notices "this photo is already here" without
     * anybody hashing 50 MB. Blank when it cannot be computed; the upload then
     * goes ahead without the check.
     */
    fun fingerprint(path: String, size: Long): String

    /** Every photo-shaped file under a folder, recursively — a card is dragged in as one. */
    fun walkPhotos(path: String): List<StillsPick>

    /** One file described, for a path that arrived from a drop. */
    fun describe(path: String): StillsPick?
}
