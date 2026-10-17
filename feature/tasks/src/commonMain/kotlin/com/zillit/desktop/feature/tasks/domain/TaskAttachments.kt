// One small function per rule the service has about attachments, and one per
// line the panel prints about them.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.tasks.domain

import com.zillit.desktop.core.media.decodeImageBitmap
import com.zillit.desktop.core.media.encodeImageJpeg
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlin.math.roundToLong

/**
 * Images and files in a task's description — the web's `lib/attachments.js`
 * and the checks around it.
 *
 * The limits are the **service's** (`zillit_tasks` → `attachment-rules.js`),
 * checked here first so the user hears about a 12 MB file before it is sent
 * rather than after:
 *
 *  - images (JPEG, PNG, WebP, GIF) are shrunk to at most 2000 px on the
 *    longest side, as JPEG; a small one (and a GIF up to 3 MB) goes as it is.
 *    At most 4 MB once prepared;
 *  - files (PDF, Word, Excel, PowerPoint, CSV, text) go exactly as they are,
 *    at most 10 MB;
 *  - at most 20 images and files on one task, counting what is waiting for
 *    Create and what is going up.
 *
 * The server checks every one of these again. Its refusals are its own
 * message keys; these are the client's own wording, which is why they are
 * [AttachmentRefusal]s rather than server messages.
 */

/** Whether the service keeps this as an image or as a file. */
enum class AttachmentKind(val wire: String) {
    Image("image"),
    File("file"),
}

/** One image or file a task has. */
data class TaskAttachment(
    val id: String,
    val name: String,
    val kind: AttachmentKind = AttachmentKind.File,
    val fileSizeBytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    /**
     * A signed link, in every single-task answer — and one that **runs out**.
     * A thumbnail that fails to load asks for a fresh one once
     * (`GET …/attachments/:id/link`) before it says "Couldn't load".
     */
    val url: String = "",
    val uploadedBy: String? = null,
    val createdMillis: Long = 0,
) {
    val isImage: Boolean get() = kind == AttachmentKind.Image
}

/** A file picked, pasted or dropped, prepared and waiting to go up. */
data class PreparedAttachment(
    /** A client-side key: these have no id until the service has one. */
    val key: String,
    val kind: AttachmentKind,
    val name: String,
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int = 0,
    val height: Int = 0,
) {
    val sizeBytes: Long get() = bytes.size.toLong()

    /** Never prints the bytes. */
    override fun toString(): String = "PreparedAttachment(key=$key, name=$name, size=${bytes.size})"

    // A data class over a ByteArray: compared by identity of the array, which
    // is what the queue wants (two picks of one file are two items).
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = key.hashCode()
}

/** The badge on a file row: three letters, a colour, and what the type is called. */
data class DocBadge(val label: String, val colour: Long, val typeName: String)

/** Why a file was turned away before anything was sent. */
sealed interface AttachmentRefusal {
    val name: String

    data class NotAnImage(override val name: String) : AttachmentRefusal
    data class InputTooLarge(override val name: String) : AttachmentRefusal
    data class Unreadable(override val name: String) : AttachmentRefusal
    data class PrepareFailed(override val name: String) : AttachmentRefusal
    data class ImageTooLarge(override val name: String) : AttachmentRefusal
    data class WrongType(override val name: String) : AttachmentRefusal
    data class FileTooLarge(override val name: String) : AttachmentRefusal
    data class Empty(override val name: String) : AttachmentRefusal
}

/** What the user is told, in this client's own words — the server's are its own. */
fun AttachmentRefusal.message(): String = when (this) {
    is AttachmentRefusal.NotAnImage -> str(S.desktop_tasks_attachment_not_image, name)
    is AttachmentRefusal.InputTooLarge -> str(S.desktop_tasks_attachment_input_too_large, name)
    is AttachmentRefusal.Unreadable -> str(S.desktop_tasks_attachment_unreadable, name)
    is AttachmentRefusal.PrepareFailed -> str(S.desktop_tasks_attachment_prepare_failed, name)
    is AttachmentRefusal.ImageTooLarge -> str(S.desktop_tasks_attachment_image_too_large, name)
    is AttachmentRefusal.WrongType -> str(S.desktop_tasks_attachment_type, name)
    is AttachmentRefusal.FileTooLarge -> str(S.desktop_tasks_attachment_file_too_large, name)
    is AttachmentRefusal.Empty -> str(S.desktop_tasks_attachment_empty, name)
}

/** What one prepare answered: the item, or why not. */
sealed interface PrepareOutcome {
    data class Ready(val item: PreparedAttachment) : PrepareOutcome
    data class Refused(val why: AttachmentRefusal) : PrepareOutcome
}

const val MAX_ATTACHMENTS = 20
const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
const val MAX_FILE_BYTES = 10L * 1024 * 1024

/** Bigger than this is not worth decoding to shrink. */
private const val MAX_INPUT_BYTES = 25L * 1024 * 1024
private const val MAX_SIDE = 2000
private const val JPEG_QUALITY = 85
private const val KEEP_AS_IS_BYTES = 600L * 1024
private const val KEEP_GIF_BYTES = 3L * 1024 * 1024

/** The image types the service takes. */
val IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

/**
 * The documents that can be attached, by extension: the badge, its colour,
 * and what the type is called. The web's `DOC_KINDS`, with its colours.
 */
private val DOC_BADGES: Map<String, DocBadge> = mapOf(
    "pdf" to DocBadge("PDF", 0xFFD23B2F, "PDF"),
    "doc" to DocBadge("DOC", 0xFF2B5FD9, "Word"),
    "docx" to DocBadge("DOC", 0xFF2B5FD9, "Word"),
    "xls" to DocBadge("XLS", 0xFF1D8A4E, "Excel"),
    "xlsx" to DocBadge("XLS", 0xFF1D8A4E, "Excel"),
    "ppt" to DocBadge("PPT", 0xFFD0632A, "PowerPoint"),
    "pptx" to DocBadge("PPT", 0xFFD0632A, "PowerPoint"),
    "csv" to DocBadge("CSV", 0xFF5F6B7D, "CSV"),
    "txt" to DocBadge("TXT", 0xFF5F6B7D, ""),
)

/** The extensions "Attach file" takes, images included so a mixed pick works. */
val ATTACHABLE_EXTENSIONS: Set<String> = DOC_BADGES.keys + IMAGE_EXTENSIONS

fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

/** The badge for a file name; an unknown extension is a plain "FILE". */
fun docBadge(name: String): DocBadge {
    val found = DOC_BADGES[extensionOf(name)] ?: return DocBadge("FILE", OTHER_COLOUR, str(S.file))
    // Two type names are words rather than product names, so they translate.
    return when (found.label) {
        "TXT" -> found.copy(typeName = str(S.desktop_tasks_file_kind_text))
        else -> found
    }
}

/** "820 B", "23 KB", "4.2 MB" — as the web prints a size. */
fun formatAttachmentSize(bytes: Long): String = when {
    bytes < BYTES_PER_KB -> "$bytes B"
    bytes < BYTES_PER_KB * BYTES_PER_KB -> "${(bytes + BYTES_PER_KB / 2) / BYTES_PER_KB} KB"
    else -> {
        val mb = bytes.toDouble() / BYTES_PER_KB / BYTES_PER_KB
        // Rounded like the web's `toFixed(1)`: 4.1999 MB is "4.2 MB", not "4.1".
        val tenths = (mb * TENTHS).roundToLong()
        "${tenths / TENTHS}.${tenths % TENTHS} MB"
    }
}

/**
 * Whether [me] may remove this one: whoever added it, or anyone the server
 * lets delete the task. The server checks again.
 */
fun canRemoveAttachment(attachment: TaskAttachment, task: Task?, me: String?): Boolean =
    me != null && me.isNotBlank() && (attachment.uploadedBy == me || task?.canDelete == true)

/**
 * The ids of a task's images and files, in order.
 *
 * Adding or removing one leaves `updated` alone — so that an open panel with
 * unsaved edits never turns into a 409 because of an attachment — which also
 * means the panel cannot notice someone else's through `updated`. It compares
 * these instead.
 */
fun attachmentIds(task: Task?): String = task?.attachments.orEmpty().joinToString(",") { it.id }

/** How many more a task will take: 20 less what it has, what waits, and what is going up. */
fun roomForAttachments(has: Int, pending: Int, uploading: Int): Int =
    (MAX_ATTACHMENTS - has - pending - uploading).coerceAtLeast(0)

/**
 * One picked file, ready to go up — or why not.
 *
 * An image is decoded and, past 2000 px or 600 KB, re-encoded as JPEG; a
 * small one (and a GIF up to 3 MB) goes exactly as it came. A document is
 * checked and sent untouched. [pastedName] names an image that arrived from
 * the clipboard with no name of its own.
 */
fun prepareAttachment(
    name: String,
    mimeType: String,
    bytes: ByteArray,
    key: String,
    pastedName: String = "",
): PrepareOutcome {
    val extension = extensionOf(name)
    val looksImage = mimeType.startsWith("image/") || extension in IMAGE_EXTENSIONS
    // An image named `.pdf` is a document as far as the badge goes; the
    // extension wins, as it does on the web.
    return if (looksImage && extension !in DOC_BADGES) {
        prepareImage(name, mimeType, bytes, key, pastedName)
    } else {
        prepareDocument(name, mimeType, bytes, key)
    }
}

private fun prepareDocument(name: String, mimeType: String, bytes: ByteArray, key: String): PrepareOutcome = when {
    extensionOf(name) !in DOC_BADGES -> PrepareOutcome.Refused(AttachmentRefusal.WrongType(name))
    bytes.isEmpty() -> PrepareOutcome.Refused(AttachmentRefusal.Empty(name))
    bytes.size > MAX_FILE_BYTES -> PrepareOutcome.Refused(AttachmentRefusal.FileTooLarge(name))
    else -> PrepareOutcome.Ready(
        PreparedAttachment(
            key = key,
            kind = AttachmentKind.File,
            name = name,
            bytes = bytes,
            mimeType = mimeType.ifBlank { "application/octet-stream" },
        ),
    )
}

/**
 * Whether an image goes exactly as it came: already small enough, a type the
 * service takes, and either under 600 KB or a GIF under 3 MB (re-encoding a
 * GIF would lose its animation).
 */
private fun keepAsIs(mimeType: String, sizeBytes: Int, longestSide: Int): Boolean =
    mimeType in IMAGE_MIME_TYPES && longestSide <= MAX_SIDE &&
        (sizeBytes <= KEEP_AS_IS_BYTES || (mimeType == GIF_TYPE && sizeBytes <= KEEP_GIF_BYTES))

@Suppress("ReturnCount") // One refusal per rule, each said in its own words.
private fun prepareImage(
    name: String,
    mimeType: String,
    bytes: ByteArray,
    key: String,
    pastedName: String,
): PrepareOutcome {
    if (mimeType.isNotBlank() && !mimeType.startsWith("image/")) {
        return PrepareOutcome.Refused(AttachmentRefusal.NotAnImage(name))
    }
    if (bytes.isEmpty()) return PrepareOutcome.Refused(AttachmentRefusal.Empty(name))
    if (bytes.size > MAX_INPUT_BYTES) return PrepareOutcome.Refused(AttachmentRefusal.InputTooLarge(name))
    val decoded = decodeImageBitmap(bytes) ?: return PrepareOutcome.Refused(AttachmentRefusal.Unreadable(name))
    // A screenshot arrives as "image.png" with no name of its own, so it takes
    // the one the caller offers; the extension is then put back by the kind
    // the bytes are actually sent as.
    val named = name.takeIf { it.isNotBlank() && it != PASTED_FILE_NAME } ?: pastedName.ifBlank { name }
    val base = named.substringBeforeLast('.', named)
    val longest = maxOf(decoded.width, decoded.height)
    if (keepAsIs(mimeType, bytes.size, longest)) {
        return PrepareOutcome.Ready(
            PreparedAttachment(
                key = key,
                kind = AttachmentKind.Image,
                name = "$base.${mimeType.substringAfter('/').replace("jpeg", "jpg")}",
                bytes = bytes,
                mimeType = mimeType,
                width = decoded.width,
                height = decoded.height,
            ),
        )
    }
    // Decoded again at the size it will be sent at, so the JPEG is written
    // from a bitmap of that size rather than scaled on the way out.
    val shrunk = decodeImageBitmap(bytes, MAX_SIDE)
        ?: return PrepareOutcome.Refused(AttachmentRefusal.PrepareFailed(name))
    val jpeg = encodeImageJpeg(shrunk, JPEG_QUALITY)
        ?: return PrepareOutcome.Refused(AttachmentRefusal.PrepareFailed(name))
    if (jpeg.size > MAX_IMAGE_BYTES) return PrepareOutcome.Refused(AttachmentRefusal.ImageTooLarge(name))
    return PrepareOutcome.Ready(
        PreparedAttachment(
            key = key,
            kind = AttachmentKind.Image,
            name = "$base.jpg",
            bytes = jpeg,
            mimeType = "image/jpeg",
            width = shrunk.width,
            height = shrunk.height,
        ),
    )
}

/** What a browser calls a pasted screenshot; the clipboard gives no name of its own. */
const val PASTED_FILE_NAME = "image.png"

private const val GIF_TYPE = "image/gif"
private const val BYTES_PER_KB = 1024L
private const val OTHER_COLOUR = 0xFF5F6B7D
private const val TENTHS = 10
