package com.zillit.desktop.feature.pagedistribution.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/** A stored PDF's descriptor — the `attachment` object, both directions. */
data class StoredPdf(
    /** The S3 object key (or Box file id) — never a URL on the wire. */
    val media: String,
    val name: String,
    val bucket: String,
    val region: String,
    /** A stringified byte count on the wire; empty when unknown. */
    val fileSize: String,
    val thumbnail: String = media,
    val contentSubtype: String = "pdf",
)

/**
 * One uploaded document. The single-list tabs and the folder tabs share the
 * record; which fields are meaningful depends on the tab.
 */
data class DistDocument(
    val id: String,
    val createdMs: Long,
    val createdBy: String,
    val episode: String,
    val sceneNumber: String,
    val pageNumber: String,
    /** `page_colour_code`, hex with `#`; blank on single-list documents. */
    val colour: String,
    /** `schedule_date` / `script_date` on single-list documents; 0 when none. */
    val dateMs: Long,
    val revisionDateMs: Long,
    /** The page's own date; 0 or absent means "—". */
    val userSelectedDateMs: Long,
    val scheduleType: ScheduleType?,
    /** The D.O.D folder name, or the schedule/script name. */
    val name: String,
    val originalName: String,
    val deleted: Boolean,
    val replaced: Boolean,
    val attachment: StoredPdf?,
)

/** A folder header — a scene's pages, or a named D.O.D folder. */
data class DistFolder(
    val id: String,
    /** The scene number or the folder name — whatever [FolderKey] the tab keys by. */
    val key: String,
    val createdMs: Long,
    val revisionDateMs: Long,
    val scheduleType: ScheduleType?,
    val colour: String,
    val deleted: Boolean,
)

/** One row of `{type}/count/{id}` — a viewer's tallies. */
data class CountRow(val userId: String, val viewCount: Int, val downloadCount: Int)

/** Live, or the deleted-and-replaced history the web shows under `/history`. */
enum class ListMode { Live, History }

/** What a single read is for — the server counts views and downloads per user. */
enum class ReadAction(val wire: String) { None(""), View("view"), Download("download") }

/**
 * What the upload dialog collected. One shape for every tab; the wire
 * builder decides which fields ride along.
 */
data class UploadDraft(
    val fileName: String,
    val bytes: ByteArray,
    /** Optional on every tab; a page's "page date", a schedule's `schedule_date`. */
    val dateYmd: String = "",
    val episode: String = "",
    val sceneNumber: String = "",
    val pageNumber: String = "",
    val colour: PageColour = PageColour.White,
    val scheduleType: ScheduleType? = null,
    /** D.O.D folder name (required), or a schedule/script name. */
    val name: String = "",
    /** True when the name was picked from the existing folders — kept verbatim then. */
    val nameFromPick: Boolean = false,
    /** The document being replaced, on a single-list tab. */
    val replaces: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is UploadDraft && other.fileName == fileName && other.replaces == replaces
    override fun hashCode(): Int = fileName.hashCode()
}

/**
 * The tool's rights, read from its identifier — plus the Document
 * Distribution posting right the Publish action needs.
 */
data class DistributionViewer(
    val userId: String = "",
    val canView: Boolean = true,
    val canPost: Boolean = false,
    val canDownload: Boolean = false,
    val canPublish: Boolean = false,
    val isAdmin: Boolean = false,
    /** Episode fields show only on television productions. */
    val isTelevision: Boolean = false,
    val ready: Boolean = false,
) {
    val isBlocked: Boolean get() = ready && !canView && !isAdmin
    val mayPost: Boolean get() = isAdmin || canPost
    val mayDownload: Boolean get() = isAdmin || canDownload
    val mayPublish: Boolean get() = isAdmin || canPublish

    /** The web's rule: admins, or the uploader, may delete. */
    fun mayDelete(createdBy: String): Boolean = isAdmin || (userId.isNotBlank() && userId == createdBy)

    companion object {
        const val DOC_DIST_TOOL = "document_distribution_tool"

        fun from(
            permissions: ProjectPermissions,
            toolIdentifier: String,
            userId: String,
            isTelevision: Boolean,
        ): DistributionViewer {
            val access = permissions.access(toolIdentifier)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return DistributionViewer(userId = userId, isTelevision = isTelevision, ready = false)
            }
            return DistributionViewer(
                userId = userId,
                canView = permissions.canView(toolIdentifier),
                canPost = permissions.canPost(toolIdentifier),
                canDownload = permissions.canDownload(toolIdentifier),
                canPublish = permissions.canPost(DOC_DIST_TOOL),
                isAdmin = permissions.isAdmin,
                isTelevision = isTelevision,
                ready = true,
            )
        }
    }
}
