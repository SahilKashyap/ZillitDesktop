package com.zillit.desktop.feature.selectstills.data

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.selectstills.domain.StillsPick
import com.zillit.desktop.feature.selectstills.domain.isJunkName
import com.zillit.desktop.feature.selectstills.domain.stillsTypeOf
import java.io.File
import java.net.URLConnection
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The file system for the stills upload.
 *
 * Reads are on the IO dispatcher; a 40 MB slice off a card reader is not a
 * frame's worth of work, and the upload runs while the gallery is on screen.
 */
class JvmStillsFileReader : StillsFileReader {

    override fun exists(path: String): Boolean = runCatching { File(path).isFile }.getOrDefault(false)

    override fun readAll(path: String): ByteArray? = runCatching { File(path).readBytes() }.getOrNull()

    override suspend fun readInChunks(path: String, chunk: Int, onChunk: suspend (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(chunk)
        withContext(Dispatchers.IO) { File(path).inputStream() }.use { stream ->
            while (true) {
                val read = withContext(Dispatchers.IO) { stream.read(buffer) }
                if (read <= 0) break
                onChunk(buffer, read)
            }
        }
    }

    /**
     * The size and a SHA-256 of the first and last 256 KB. The service spots a
     * repeat from this without anybody hashing 50 MB; a file it cannot read
     * comes back blank and the upload simply goes ahead without the check.
     */
    override fun fingerprint(path: String, size: Long): String = runCatching {
        if (size <= 0) return ""
        val file = File(path)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            if (size <= EDGE_BYTES * 2) {
                digest.update(stream.readBytes())
            } else {
                val head = ByteArray(EDGE_BYTES)
                stream.readNBytes(head, 0, EDGE_BYTES)
                digest.update(head)
                stream.skip(size - EDGE_BYTES * 2)
                val tail = ByteArray(EDGE_BYTES)
                stream.readNBytes(tail, 0, EDGE_BYTES)
                digest.update(tail)
            }
        }
        val hex = digest.digest().joinToString("") { byte -> byte.hex() }
        "$hex.$size"
    }.getOrElse {
        ZillitLog.w(TAG) { "could not fingerprint a file: ${it::class.simpleName}" }
        ""
    }

    /**
     * Every file under [path], folders walked through — a card is usually
     * dragged in as a folder. What is not a photo is left in: the caller
     * screens it and says why, as the web does.
     */
    override fun walkPhotos(path: String): List<StillsPick> {
        val root = runCatching { File(path) }.getOrNull() ?: return emptyList()
        if (root.isFile) return listOfNotNull(describe(root))
        if (!root.isDirectory) return emptyList()
        val out = mutableListOf<StillsPick>()
        runCatching {
            root.walkTopDown()
                .onEnter { folder -> !isJunkName(folder.name) }
                .filter { it.isFile && !isJunkName(it.name) }
                .take(MAX_WALK)
                .forEach { file -> describe(file)?.let(out::add) }
        }
        return out
    }

    override fun describe(path: String): StillsPick? = runCatching { describe(File(path)) }.getOrNull()

    private fun describe(file: File): StillsPick? {
        if (!file.isFile) return null
        val declared = runCatching { URLConnection.guessContentTypeFromName(file.name) }.getOrNull()
        return StillsPick(
            path = file.absolutePath,
            name = file.name,
            size = file.length(),
            type = stillsTypeOf(file.name, declared),
        )
    }

    /** One byte as two lowercase hex digits. */
    private fun Byte.hex(): String = ((toInt() and BYTE_MASK) + HEX_LEAD).toString(HEX).substring(1)

    private companion object {
        const val TAG = "Stills"
        const val BYTE_MASK = 0xFF
        const val HEX_LEAD = 0x100
        const val HEX = 16
        const val EDGE_BYTES = 256 * 1024

        /**
         * A drop of a whole card is hundreds of files; a drop of a home folder
         * is hundreds of thousands. Stopping at a card's worth keeps a slip of
         * the hand from freezing the tool.
         */
        const val MAX_WALK = 20_000
    }
}
