package com.zillit.desktop.feature.selectstills.domain

/**
 * Picking photos to upload: which files are photos the service takes, and how
 * a size reads. The web's `lib/files.js`, less the browser-only parts.
 *
 * The file is sent exactly as the camera wrote it. Nothing here resizes,
 * converts or re-encodes.
 */
private val EXT_TYPES = mapOf(
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "webp" to "image/webp",
    "heic" to "image/heic",
    "heif" to "image/heif",
)

// Camera RAW: refused with its own sentence, since "not a photo" would be wrong.
private val RAW_EXTENSIONS = setOf(
    "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2", "dng", "raf", "orf",
    "rw2", "pef", "srw", "x3f", "3fr", "iiq", "erf", "kdc", "mos", "mrw",
)

// What a dropped folder brings along that nobody meant to upload.
private val JUNK_NAMES = setOf("thumbs.db", "desktop.ini")

private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

/**
 * A file's type as the service names it. macOS reports an empty type for
 * `.heic`, and some cameras' files arrive as `image/jpg`, so the extension is
 * the fallback and the odd spellings are put right.
 */
fun stillsTypeOf(name: String, declaredType: String?): String {
    val type = declaredType.orEmpty().lowercase()
    if (type == "image/jpg" || type == "image/pjpeg") return "image/jpeg"
    if (type.isNotBlank()) return type
    return EXT_TYPES[extensionOf(name)].orEmpty()
}

/** A system file a folder drags in. */
fun isJunkName(name: String): Boolean = name.startsWith(".") || name.lowercase() in JUNK_NAMES

/** Why a chosen file cannot be sent. */
enum class PickRefusal(val wire: String) {
    Raw("raw"),
    Type("type"),
    Empty("empty"),
    Size("size"),
}

/** A file the reader chose, by path — hundreds of 40 MB stills are never read whole. */
data class StillsPick(val path: String, val name: String, val size: Long, val type: String)

/** A file that was turned away here, before anything was sent. */
data class StillsRefused(val name: String, val size: Long, val reason: PickRefusal)

/** What [screenPicks] made of a selection. */
data class ScreenedPicks(val ok: List<StillsPick>, val refused: List<StillsRefused>)

/**
 * Split picked files into the ones that can be sent and the ones that cannot,
 * each with the reason. System files a folder drags in are dropped without a
 * word.
 *
 * @param types the types the service takes, from `GET /me` → upload
 * @param maxBytes the largest file it takes; 0 means "not answered yet", so nothing is capped
 */
fun screenPicks(
    picks: List<StillsPick>,
    types: List<String>,
    maxBytes: Long,
): ScreenedPicks {
    val ok = mutableListOf<StillsPick>()
    val refused = mutableListOf<StillsRefused>()
    val ceiling = if (maxBytes > 0) maxBytes else Long.MAX_VALUE
    picks.forEach { pick ->
        val type = stillsTypeOf(pick.name, pick.type)
        when {
            isJunkName(pick.name) -> Unit
            extensionOf(pick.name) in RAW_EXTENSIONS -> refused += StillsRefused(pick.name, pick.size, PickRefusal.Raw)
            type !in types -> refused += StillsRefused(pick.name, pick.size, PickRefusal.Type)
            pick.size <= 0 -> refused += StillsRefused(pick.name, pick.size, PickRefusal.Empty)
            pick.size > ceiling -> refused += StillsRefused(pick.name, pick.size, PickRefusal.Size)
            else -> ok += pick.copy(type = type)
        }
    }
    return ScreenedPicks(ok, refused)
}

/** A size as the upload hint and the queue rows print it. */
@Suppress("MagicNumber")
fun formatBytes(bytes: Long): String {
    val n = if (bytes > 0) bytes else 0L
    val kb = 1024L
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        n >= gb -> "${oneDecimal(n.toDouble() / gb)} GB"
        n >= mb -> if (n >= 10 * mb) "${(n / mb)} MB" else "${oneDecimal(n.toDouble() / mb)} MB"
        n >= kb -> "${(n + kb / 2) / kb} KB"
        else -> "$n B"
    }
}

@Suppress("MagicNumber")
private fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toLong()
    return "${tenths / 10}.${tenths % 10}"
}
