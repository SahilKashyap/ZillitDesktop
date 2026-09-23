package com.zillit.desktop.feature.continuity.domain

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The two boards — the web's `tabArray` (`Continuity.jsx:44-63`). "My
 * Department" (intra) is where a crew member uploads; "All Departments" is
 * what has been forwarded for the whole production, grouped by the
 * uploading department. (The web also wires an inter-department tab,
 * commented out of its tab bar — not ported.)
 *
 * [wireLabel] is the delete route's segment and the share body's
 * `visibility`; [readSegment] is the badge ledger's `unit` for the tab and
 * the `segment` a read names (`IntraDepartment.jsx:494-508`).
 */
enum class ContinuityTab(private val labelKey: String, val wireLabel: String, val readSegment: String) {
    MyDepartment(S.intradepartment, "intra", "continuity_intra_label"),
    AllDepartments(S.all_departments, "all", "continuity_all_label"),
    ;

    val label: String get() = str(labelKey)

    /** The web's `note :` line under the header (`contunityMy_Notes` / `contunityAll_Notes`). */
    val note: String
        get() = when (this) {
            MyDepartment -> str(S.desktop_continuity_note_my)
            AllDepartments -> str(S.desktop_continuity_note_all)
        }
}

/** One department that has media for a scene, on the All Departments board. */
data class ContinuityDepartment(val id: String, val name: String)

/** A `label: value` line of the card's "More info" — the web's `talent_info` rows. */
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

    /** Whether the cards can show a picture of it: an image, or a video with a poster frame. */
    val hasPoster: Boolean get() = isImage || (isVideo && thumbnail.isNotBlank())
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

    /**
     * The message body a forwarded card travels with — the web's
     * `generateCaption` (`ContinuityDrawer.jsx:60-83`, ZL-12939): the scene
     * number, then the description, the episode on television, then every
     * detail row.
     */
    fun forwardCaption(isTelevision: Boolean): String = buildString {
        appendLine("Scene Number: $sceneNumber")
        if (notes.isNotBlank()) appendLine("Scene Description: $notes")
        if (episode.isNotBlank() && isTelevision) appendLine("Episode Number : $episode")
        talentInfo.forEach { appendLine("${it.label}: ${it.value}") }
    }.trim()
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

    /** What the web's `handleUnitChatUploadFiles` lets through: pictures, videos and office documents. */
    val isAccepted: Boolean
        get() = isImage || isVideo || name.substringAfterLast('.', "").lowercase() in DOCUMENT_EXTENSIONS

    override fun equals(other: Any?): Boolean = other is PickedContinuityFile && other.name == name
    override fun hashCode(): Int = name.hashCode()

    companion object {
        /** `fileUtils.jsx:114-145` — the document extensions the web accepts. */
        val DOCUMENT_EXTENSIONS: Set<String> = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "odt", "ods", "odp",
        )
    }
}

/** A crew member who can be forwarded a card — the web's `ShowUsers` rows. */
data class ContinuityCrewMember(
    val userId: String,
    val name: String,
    /** Already translated; blank when the record has none worth showing. */
    val designation: String = "",
) {
    fun matches(query: String): Boolean {
        val needle = query.trim().lowercase()
        return needle.isEmpty() || name.lowercase().contains(needle) || designation.lowercase().contains(needle)
    }
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

    /** Posting, or an administrator's blanket bypass. */
    val mayPost: Boolean get() = canPost || isAdmin
    val mayDownload: Boolean get() = canDownload || isAdmin

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

/**
 * The unread counts the boards wear — the web's `getContinuityIntraDepartment`
 * / `getContinuityAllDepartment` slices of the badge tree
 * (`TabsComponents.jsx:753-797`): the service files a continuity row under
 * `tool=continuity_label`, `unit=<tab segment>`, `level_1=<scene folder>`,
 * `level_2=<department id>`.
 */
data class ContinuityUnread(
    /** Per tab segment. */
    val tabs: Map<String, Int> = emptyMap(),
    /** Per tab segment, per scene folder. */
    val folders: Map<String, Map<String, Int>> = emptyMap(),
    /** On the All board: per scene folder, per department id. */
    val departments: Map<String, Map<String, Int>> = emptyMap(),
) {
    fun tab(tab: ContinuityTab): Int = tabs[tab.readSegment] ?: 0
    fun folder(tab: ContinuityTab, sceneFolder: String): Int = folders[tab.readSegment]?.get(sceneFolder) ?: 0
    fun department(sceneFolder: String, departmentId: String): Int = departments[sceneFolder]?.get(departmentId) ?: 0

    companion object {
        val Empty = ContinuityUnread()
    }
}

/** The badge ledger's view of continuity, and the reads the boards send. */
interface ContinuityBadges {
    val unread: Flow<ContinuityUnread> get() = emptyFlow()

    /**
     * A folder opened (`IntraDepartment.jsx:494-508`) or, with [departmentId],
     * a department within it on the All board (`DepartmentList.jsx:37-56`).
     */
    fun markRead(tab: ContinuityTab, sceneFolder: String, departmentId: String? = null) {}

    companion object {
        val None: ContinuityBadges = object : ContinuityBadges {}
    }
}

/** Everything the board asks `/api/v2/continuity` for. */
interface ContinuityRepository {
    /**
     * A pulse per continuity event on the socket — scenes created, a scene
     * updated, deleted or forwarded (`listenerSocket.js:862-873`). Every
     * web surface refetches on these (`ContinuityModal.jsx:242-295/488`,
     * `IntraDepartment.jsx:576-658`), so the listener reloads the folder
     * grid and whatever folder is open. Defaulted empty for tests and
     * hosts without a socket.
     */
    val refreshes: Flow<Unit> get() = emptyFlow()

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

    /**
     * Moves scenes into the file cabinet (`PUT /v2/continuity/archive`).
     *
     * The web hides its File Cabinet button (ZL-19912, commented not removed)
     * and so does this board; the call stays wired for when it returns.
     */
    suspend fun archive(sceneIds: List<String>): ZillitResult<Unit> =
        ZillitResult.Failure(ZillitError.Unknown("archiving is not wired"))

    /**
     * Distributes the board (`POST /v2/continuity/distribute`), narrowed by
     * whichever of visibility, scene number and department are given — the
     * web omits a blank rather than sending it empty. Hidden on the web
     * ("Distribute option hidden per requirement — keep logic, do not
     * render") and here alike.
     */
    suspend fun distribute(
        visibility: String,
        sceneNumber: String,
        departmentId: String,
    ): ZillitResult<Unit> = ZillitResult.Failure(ZillitError.Unknown("distribution is not wired"))

    /** Removes from the [tab] board only (soft, per visibility). */
    suspend fun delete(tab: ContinuityTab, id: String): ZillitResult<Unit>
}

/** The host's file seams: S3 up, signed fetch down, save to Downloads, open in the system player. */
interface ContinuityTransfer {
    suspend fun upload(file: PickedContinuityFile): ZillitResult<ContinuityAttachment>
    suspend fun fetch(attachment: ContinuityAttachment, preview: Boolean): ZillitResult<ByteArray>
    suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit>

    /**
     * Plays or opens a file without filing it in Downloads — what the web's
     * inline `<video>` and non-PDF document click amount to on a desktop.
     * Defaults to [saveAndOpen] for hosts that make no distinction.
     */
    suspend fun open(fileName: String, bytes: ByteArray): ZillitResult<Unit> = saveAndOpen(fileName, bytes)
}

/**
 * "Forward → Select Users": one private chat message per card per person,
 * the card's file as the attachment and its details as the body
 * (`ContinuityDrawer.jsx:85-133`).
 */
fun interface ContinuityForwarder {
    suspend fun forward(scene: ContinuityScene, toUserId: String, caption: String): ZillitResult<Unit>
}
