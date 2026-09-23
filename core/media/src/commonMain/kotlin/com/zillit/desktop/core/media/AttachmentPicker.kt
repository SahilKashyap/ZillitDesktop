package com.zillit.desktop.core.media

import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The kinds of thing a composer can attach, as the phones offer them.
 *
 * Both other clients open an attachment sheet rather than a bare file
 * dialog — Android's `PickerDialog` items and iOS's action sheet list
 * *Photo, Video, Document, Audio* (plus camera and location, which are
 * separate affordances here). One "Attach a file" button that opens an
 * untyped dialog hides that vocabulary, and a crew member looking for "Video"
 * does not find it.
 *
 * [PreviewKind] already names these four, classified by MIME, so it is the
 * vocabulary rather than a second enum that would drift from it.
 */
val PreviewKind.label: String
    get() = when (this) {
        PreviewKind.Image -> str(S.photo)
        PreviewKind.Video -> str(S.video)
        PreviewKind.Document -> str(S.document)
        PreviewKind.Audio -> str(S.audio)
    }

val PreviewKind.icon: ImageVector
    get() = when (this) {
        PreviewKind.Image -> ZillitIcons.Photo
        PreviewKind.Video -> ZillitIcons.Camera
        PreviewKind.Document -> ZillitIcons.File
        PreviewKind.Audio -> ZillitIcons.Audio
    }

/** What the OS dialog is titled — the kind, so the filter is explained. */
val PreviewKind.pickerTitle: String
    get() = when (this) {
        PreviewKind.Image -> str(S.desktop_media_choose_photos)
        PreviewKind.Video -> str(S.desktop_media_choose_videos)
        PreviewKind.Document -> str(S.desktop_media_choose_documents)
        PreviewKind.Audio -> str(S.desktop_media_choose_audio)
    }

/**
 * The extensions a kind's dialog admits.
 *
 * Document is open-ended on purpose: the phones' document picker takes
 * anything, and a call sheet is as likely to be `.docx` as `.pdf`. The three
 * media kinds are closed sets, because a `.exe` renamed `.mp4` must not reach
 * a board as a video.
 */
val PreviewKind.extensions: Set<String>?
    get() = when (this) {
        PreviewKind.Image -> IMAGE_EXTENSIONS
        PreviewKind.Video -> VIDEO_EXTENSIONS
        PreviewKind.Audio -> AUDIO_EXTENSIONS
        PreviewKind.Document -> null
    }

/**
 * The full attachment sheet, in the phones' order.
 *
 * Android's `allList`: camera, gallery, video, document, location, contact,
 * audio — with camera and location being separate buttons on desktop, and
 * contact out of scope, this is what is left.
 */
val ALL_ATTACHMENT_KINDS: List<PreviewKind> = listOf(
    PreviewKind.Image,
    PreviewKind.Video,
    PreviewKind.Document,
    PreviewKind.Audio,
)

/**
 * A file the user chose, read into memory.
 *
 * The shape every composer already uses for its own `PickedX` — name, type,
 * bytes — so each maps onto its own with a one-liner and none has to learn
 * the picker's.
 */
class PickedFile(
    val name: String,
    val contentType: String,
    val bytes: ByteArray,
) {
    val kind: PreviewKind get() = PreviewKind.of(contentType)

    /** Never prints the bytes. */
    override fun toString(): String = "PickedFile(name=$name, type=$contentType, size=${bytes.size})"
}

/**
 * A file the user chose, by path.
 *
 * For uploads too big to read into memory first — Drive takes 10 GB files,
 * and a picker that read one to hand it on would exhaust the heap before the
 * first chunk left.
 */
data class PickedPath(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val contentType: String,
)

/**
 * Why a chosen file was turned away before it was read.
 *
 * Said rather than swallowed: a dialog that closes and shows nothing leaves
 * the user guessing whether they clicked at all.
 */
sealed interface PickRefusal {
    data class TooLarge(val name: String, val sizeBytes: Long) : PickRefusal

    /** Chosen under one kind, but not of it — a `.txt` picked as a video. */
    data class WrongKind(val name: String, val wanted: PreviewKind) : PickRefusal
}

/**
 * The system file chooser, by kind.
 *
 * One dialog per pick, filtered to the kind chosen on the attach sheet, and
 * checked again after reading — the OS filter is advisory on macOS, and a
 * filename says nothing a user could not have typed.
 */
interface AttachmentPicker {

    /** Empty when the user cancelled or nothing chosen was acceptable. */
    suspend fun pick(
        kind: PreviewKind,
        multiple: Boolean = true,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        onRefused: (PickRefusal) -> Unit = {},
    ): List<PickedFile>

    /** As [pick], but returning paths rather than reading the files. */
    suspend fun pickPaths(
        kind: PreviewKind,
        multiple: Boolean = true,
        onRefused: (PickRefusal) -> Unit = {},
    ): List<PickedPath>

    companion object {
        /** 25 MB — where most mail servers stop accepting; callers raise it. */
        const val DEFAULT_MAX_BYTES: Long = 25L * 1024 * 1024
    }
}

/** Reads a kind off a file name, for the dialog's filter and the check after. */
fun PreviewKind.admits(fileName: String, contentType: String): Boolean {
    val allowed = extensions ?: return true
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return extension in allowed || PreviewKind.of(contentType) == this
}

/** A type by extension, for the platforms whose own table misses common ones. */
fun contentTypeFor(fileName: String, guessed: String?): String =
    guessed ?: KNOWN_TYPES[fileName.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff")
private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "mkv", "webm", "avi", "3gp")
private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "aiff", "caf")

/**
 * The types the boards care about, by extension.
 *
 * The JRE's content-types table misses common ones on Windows, and a photo
 * typed `application/octet-stream` classifies as a document — no preview, no
 * edit tools, a file chip on the board.
 */
private val KNOWN_TYPES = mapOf(
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
    "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif", "bmp" to "image/bmp",
    "tif" to "image/tiff", "tiff" to "image/tiff",
    "mp4" to "video/mp4", "mov" to "video/quicktime", "m4v" to "video/x-m4v", "mkv" to "video/x-matroska",
    "webm" to "video/webm", "avi" to "video/x-msvideo", "3gp" to "video/3gpp",
    "mp3" to "audio/mpeg", "wav" to "audio/wav", "m4a" to "audio/mp4", "aac" to "audio/aac",
    "ogg" to "audio/ogg", "flac" to "audio/flac", "aiff" to "audio/aiff", "caf" to "audio/x-caf",
    "pdf" to "application/pdf",
    "doc" to "application/msword",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xls" to "application/vnd.ms-excel",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "ppt" to "application/vnd.ms-powerpoint",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "txt" to "text/plain", "csv" to "text/csv",
)
