package com.zillit.desktop.feature.continuity.domain

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * The two boards. "My Department" (intra) is where a crew member uploads;
 * "All Departments" is what has been forwarded for the whole production,
 * grouped by the uploading department. (The web also wires an
 * inter-department tab, commented out of its tab bar — not ported.)
 */
enum class ContinuityTab(val label: String, val wireLabel: String, val readSegment: String) {
    MyDepartment("My Department", "intra", "continuity_intra_label"),
    AllDepartments("All Departments", "all", "continuity_all_label"),
}

/** One department that has media for a scene, on the All Departments board. */
data class ContinuityDepartment(val id: String, val name: String)

/** A `label: value` line of the card's "More info". */
data class TalentInfo(val label: String, val value: String)

/**
 * The stored file. Every key is required by the server on create — even
 * `caption` empty and the dimensions zero — so the model carries them all.
 */
data class ContinuityAttachment(
    /** The S3 key (or Box id). */
    val media: String,
    /** The S3 key of the video thumbnail; the image itself for images; empty for documents. */
    val thumbnail: String,
    /** `image` | `video` | `document`. */
    val contentType: String,
    /** The extension, lower-cased. */
    val contentSubtype: String,
    val name: String,
    val bucket: String,
    val region: String,
    /** Bytes, as the server keeps it: a string. */
    val fileSize: String = "0",
    val caption: String = "",
    val height: Int = 0,
    val width: Int = 0,
    val duration: Int = 0,
) {
    val isImage: Boolean get() = contentType == "image"
    val isVideo: Boolean get() = contentType == "video"
    val isDocument: Boolean get() = contentType == "document"
    val isPdf: Boolean get() = isDocument && contentSubtype == "pdf"
}

/** One card: a file with its scene details. */
data class ContinuityScene(
    val id: String,
    val uniqueId: String,
    val sceneNumber: String,
    val episode: String,
    val notes: String,
    val actorName: String,
    val talentInfo: List<TalentInfo>,
    val attachment: ContinuityAttachment?,
    val departmentId: String,
    val uploadedBy: String,
    val visibleIntra: Boolean,
    val visibleAll: Boolean,
    val deletedIntra: Boolean,
    val deletedAll: Boolean,
    val createdMs: Long,
    val updatedMs: Long,
) {
    /** The pagination cursor: what the web sends as `timestamp`. */
    val cursorMs: Long get() = if (updatedMs > 0) updatedMs else createdMs

    fun shownOn(tab: ContinuityTab): Boolean = when (tab) {
        ContinuityTab.MyDepartment -> visibleIntra && !deletedIntra
        ContinuityTab.AllDepartments -> visibleAll && !deletedAll
    }
}

/** What the Add/Edit dialog collects. */
data class SceneDraft(
    val sceneNumber: String = "",
    val episode: String = "",
    val notes: String = "",
    val talentInfo: List<TalentInfo> = emptyList(),
)

/** A file the host picked, bytes and all. */
data class PickedContinuityFile(val name: String, val contentType: String, val bytes: ByteArray) {
    val isImage: Boolean get() = contentType.startsWith("image/")
    val isVideo: Boolean get() = contentType.startsWith("video/")

    override fun equals(other: Any?): Boolean = other is PickedContinuityFile && other.name == name
    override fun hashCode(): Int = name.hashCode()
}

/** Who is looking, from the production's rights on `continuity_tool`. */
data class ContinuityViewer(
    val userId: String = "",
    val departmentId: String = "",
    val isTelevision: Boolean = false,
    val canView: Boolean = true,
    val canPost: Boolean = false,
    val canDownload: Boolean = false,
    val isAdmin: Boolean = false,
    val ready: Boolean = false,
) {
    val isBlocked: Boolean get() = ready && !canView && !isAdmin

    companion object {
        const val TOOL_IDENTIFIER = "continuity_tool"

        fun from(
            permissions: ProjectPermissions,
            userId: String,
            departmentId: String,
            isTelevision: Boolean,
        ): ContinuityViewer {
            val access = permissions.access(TOOL_IDENTIFIER)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return ContinuityViewer(userId, departmentId, isTelevision, ready = false)
            }
            return ContinuityViewer(
                userId = userId,
                departmentId = departmentId,
                isTelevision = isTelevision,
                canView = permissions.canView(TOOL_IDENTIFIER),
                canPost = permissions.canPost(TOOL_IDENTIFIER),
                canDownload = permissions.canDownload(TOOL_IDENTIFIER),
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}

/** Everything the board asks `/api/v2/continuity` for. */
interface ContinuityRepository {
    /** The scene folders — leading numbers — that have media on [tab]. */
    suspend fun folders(tab: ContinuityTab): ZillitResult<List<String>>

    /**
     * Cards under [sceneFolder], older than [beforeMs] (`nextPrevious=previous`),
     * optionally one department's on the All board. The server pages; an
     * empty answer is the end.
     */
    suspend fun scenes(
        tab: ContinuityTab,
        sceneFolder: String,
        departmentId: String?,
        beforeMs: Long,
    ): ZillitResult<List<ContinuityScene>>

    /** Departments with media for [sceneFolder] on the All board. */
    suspend fun departments(sceneFolder: String): ZillitResult<List<ContinuityDepartment>>

    /** One record per file. */
    suspend fun create(draft: SceneDraft, attachment: ContinuityAttachment, uniqueId: String): ZillitResult<Unit>

    suspend fun update(id: String, draft: SceneDraft): ZillitResult<ContinuityScene?>

    /** Forward to All Departments. */
    suspend fun share(ids: List<String>, sceneFolder: String): ZillitResult<Unit>

    /** Removes from the [tab] board only (soft, per visibility). */
    suspend fun delete(tab: ContinuityTab, id: String): ZillitResult<Unit>
}

/** The host's file seams: S3 up, signed fetch down, save to Downloads. */
interface ContinuityTransfer {
    suspend fun upload(file: PickedContinuityFile): ZillitResult<ContinuityAttachment>
    suspend fun fetch(attachment: ContinuityAttachment, preview: Boolean): ZillitResult<ByteArray>
    suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit>
}
