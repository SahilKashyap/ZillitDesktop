package com.zillit.desktop.feature.costumesetsync.domain

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult

/** A file the user picked from disk. An empty [bytes] never reaches the service. */
class PickedFile(val name: String, val bytes: ByteArray, val mime: String) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val isImage: Boolean get() = mime.startsWith("image/")
    val isVideo: Boolean get() = mime.startsWith("video/")
}

/**
 * A file that has gone to project storage: the `attachment` object the service
 * stores beside a photo record (`{ media, name, content_type, content_subtype,
 * bucket, region, file_size }`). The service never takes the file itself — the
 * upload is the usual S3 one, then these details are posted as JSON.
 */
data class StoredFile(
    val media: String,
    val name: String,
    val contentType: String,
    val contentSubtype: String,
    val bucket: String,
    val region: String,
    val fileSize: Long,
)

/**
 * What the host app gives the tool: the things that need a window, a disk or
 * the project's storage credentials, which the feature module cannot reach.
 * The same seam shape Sides and Continuity use. The wiring supplies the real
 * one; [NoHost] is what a test or an unwired build gets, and every call on it
 * fails or does nothing rather than throwing.
 */
interface SyncHost {
    /** Opens the file chooser; an empty list is "cancelled". [extensions] are lower-case, no dot; empty = any. */
    suspend fun pick(extensions: Set<String>, multiple: Boolean): List<PickedFile>

    /** Uploads [file] to project storage. */
    suspend fun store(file: PickedFile): ZillitResult<StoredFile>

    /** A fetchable URL for a stored file (its S3 key resolved against the project's storage), or null. */
    suspend fun resolveUrl(media: String, bucket: String, region: String): String?

    /** Opens an https address in the system browser. */
    fun openUrl(url: String)

    /** Saves [bytes] through a save dialog; false when the user cancelled. */
    suspend fun save(suggestedName: String, bytes: ByteArray): Boolean

    /**
     * Everyone on the production, from Zillit's own department list (`GET /v2/departments/users`) — the
     * "Send a request" recipient list. The Costumes service has no crew list of its own (its `/members`
     * is an access list, not a picker source). Empty until the host wires it.
     */
    suspend fun crew(): List<CrewMember> = emptyList()

    /** The bytes behind a URL [resolveUrl] returned (a thumbnail to decode), or null when it will not come. */
    suspend fun fetch(url: String): ByteArray? = null

    /**
     * Writes [bytes] to a temporary file and hands it to the system viewer
     * (the label sheet opens in the browser, where it prints). False when it could not be opened.
     */
    suspend fun open(fileName: String, bytes: ByteArray): Boolean = false

    /**
     * Opens the app's Email composer with [subject] and [bodyHtml] filled in (the web's `shareMessagesAsEmail`).
     * False when it could not: the user has no Zillit mailbox, or no mail window is wired.
     */
    fun composeEmail(subject: String, bodyHtml: String): Boolean = false

    /**
     * The bytes behind one of this service's own addresses (`SyncOnsetApi.url`), fetched with the project
     * Bearer — the raw GET `ApiClient`'s JSON envelopes cannot make (the budget sheet template).
     */
    suspend fun downloadBytes(url: String): ZillitResult<ByteArray> =
        ZillitResult.Failure(ZillitError.Unknown("no download host is wired"))

    /**
     * Whether [capturePhoto] can open a camera. The document scanner offers "Capture page" only when it can; either
     * way "Add pages from files" does the same job from pictures on disk.
     */
    val hasCamera: Boolean get() = false

    /**
     * Opens the computer's camera, lets the user take one picture, and returns it (a JPEG); null when they closed the
     * window, there is no camera, or the system refused it. The desktop has no capture API in the shared modules, so
     * only the host can supply this.
     */
    suspend fun capturePhoto(): PickedFile? = null
}

/**
 * The one seam between the tool and the app's mail window. The app's wiring sets [open] once the mail view model
 * and the window it opens from exist (neither does when the tool's view model is built); the host's
 * [SyncHost.composeEmail] calls it. Null until then, so a share before that reports "unavailable" instead of dropping.
 */
class MailBridge {
    @Volatile
    var open: ((subject: String, bodyHtml: String) -> Boolean)? = null
}

/** The host of a build that wires none: picks nothing, stores nothing. */
object NoHost : SyncHost {
    override suspend fun pick(extensions: Set<String>, multiple: Boolean): List<PickedFile> = emptyList()

    override suspend fun store(file: PickedFile): ZillitResult<StoredFile> =
        ZillitResult.Failure(ZillitError.Unknown("no file host is wired"))

    override suspend fun resolveUrl(media: String, bucket: String, region: String): String? = null

    override fun openUrl(url: String) = Unit

    override suspend fun save(suggestedName: String, bytes: ByteArray): Boolean = false
}

/**
 * One person on the production: [id] is the Zillit user id the request endpoint takes; [department] is already
 * readable.
 */
data class CrewMember(val id: String, val name: String, val department: String = "", val email: String = "")
