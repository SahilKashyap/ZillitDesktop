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

/** One person on the production: [id] is the Zillit user id the request endpoint takes; [department] is already readable. */
data class CrewMember(val id: String, val name: String, val department: String = "", val email: String = "")
