package com.zillit.desktop.feature.drive.ui

import com.zillit.desktop.feature.drive.domain.ActivityCategory
import com.zillit.desktop.feature.drive.domain.DriveAccessEntry
import com.zillit.desktop.feature.drive.domain.DriveAction
import com.zillit.desktop.feature.drive.domain.DriveActivity
import com.zillit.desktop.feature.drive.domain.DriveComment
import com.zillit.desktop.feature.drive.domain.DriveCrumb
import com.zillit.desktop.feature.drive.domain.DriveFileRequest
import com.zillit.desktop.feature.drive.domain.DriveInnerTab
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveListing
import com.zillit.desktop.feature.drive.domain.DrivePerson
import com.zillit.desktop.feature.drive.domain.DriveRef
import com.zillit.desktop.feature.drive.domain.DriveRole
import com.zillit.desktop.feature.drive.domain.DriveSection
import com.zillit.desktop.feature.drive.domain.DriveShareLink
import com.zillit.desktop.feature.drive.domain.DriveSortSpec
import com.zillit.desktop.feature.drive.domain.DriveTag
import com.zillit.desktop.feature.drive.domain.DriveUnread
import com.zillit.desktop.feature.drive.domain.DriveVersion
import com.zillit.desktop.feature.drive.domain.DriveView
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.domain.FileAccessLevel
import com.zillit.desktop.feature.drive.domain.LinkPermission
import com.zillit.desktop.feature.drive.domain.MyDriveFilter
import com.zillit.desktop.feature.drive.domain.PreviewKind
import com.zillit.desktop.feature.drive.domain.QueuedUpload
import com.zillit.desktop.feature.drive.domain.combinedRows
import com.zillit.desktop.feature.drive.domain.eligible
import com.zillit.desktop.feature.drive.domain.suggestFreeName
import com.zillit.desktop.feature.drive.domain.innerTabTotals
import com.zillit.desktop.feature.drive.domain.nextAccessLevel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** List or grid. Persists across folder navigation (FR-20.4). */
enum class DriveViewMode(private val labelKey: String) {
    List(S.desktop_drive_view_list),
    Grid(S.desktop_drive_view_grid),
    ;

    val label: String get() = str(labelKey)
}

/** Everything the details panel knows about the item it is open on. */
data class DetailsState(
    val item: DriveItem? = null,
    val comments: List<DriveComment> = emptyList(),
    val activity: List<DriveActivity> = emptyList(),
    val versions: List<DriveVersion> = emptyList(),
    val access: List<DriveAccessEntry> = emptyList(),
    /** The tags on this item, as distinct from the project's whole tag list. */
    val tags: List<DriveTag> = emptyList(),
    val commentDraft: String = "",
    /** The comment being rewritten in place, and its working text. */
    val editingCommentId: String? = null,
    val editingText: String = "",
    val tagDraft: String = "",
    val loading: Boolean = false,
    val tagsBusy: Boolean = false,
) {
    val open: Boolean get() = item != null

    /** The project tags not yet on this item — what the "add" menu offers. */
    fun unapplied(all: List<DriveTag>): List<DriveTag> {
        val on = tags.map { it.id }.toSet()
        return all.filterNot { it.id in on }
    }
}

/**
 * The "Request files" panel on a folder.
 *
 * A production asks someone with no Zillit account — a supplier, a location
 * owner — to send files straight into a folder. [created] holds the link the
 * service just made, which is the whole point of the exchange and must not be
 * lost when the list reloads behind it.
 */
data class FileRequestState(
    val folderId: String? = null,
    val folderName: String = "",
    val requests: List<DriveFileRequest> = emptyList(),
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val title: String = "",
    val description: String = "",
    val expiryDays: Int = DEFAULT_EXPIRY_DAYS,
    val requireName: Boolean = true,
    val requireEmail: Boolean = false,
    val created: DriveFileRequest? = null,
) {
    val open: Boolean get() = folderId != null

    /** A request needs somewhere to land and something to call itself. */
    val canSubmit: Boolean get() = folderId != null && title.isNotBlank() && !submitting
}

/** A fortnight: long enough for a supplier to get round to it, short enough to expire. */
const val DEFAULT_EXPIRY_DAYS = 14

/** A confirmation the user has to answer before something irreversible happens. */
data class DrivePrompt(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val event: DriveEvent,
    val danger: Boolean = true,
)

/** The trash, which replaces the listing while it is open (`TrashView.jsx`). */
data class TrashState(
    val items: List<DriveItem> = emptyList(),
    val loading: Boolean = false,
)

/** The Activity Log drawer — paged, filtered by family (`ActivityLogDrawer.jsx`). */
data class ActivityLogState(
    val open: Boolean = false,
    val items: List<DriveActivity> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val filter: ActivityCategory? = null,
) {
    val hasMore: Boolean get() = items.size < total

    val filtered: List<DriveActivity>
        get() = (if (filter == null) items else items.filter { it.category == filter })
            .sortedByDescending { it.at ?: 0L }

    fun count(category: ActivityCategory?): Int =
        if (category == null) items.size else items.count { it.category == category }
}

/**
 * The access a picker has assembled — shared by the upload, create-folder and
 * share drawers, which all offer the same two modes: one grant for every
 * eligible person, or a hand-picked set (`FilePermissionsPanel`,
 * `CreateFolderDrawer`, `ShareDrawer`).
 */
data class AccessDraft(
    val projectWide: Boolean = false,
    /** The one grant applied to everyone, in project-wide mode. */
    val projectRole: DriveRole? = null,
    val projectLevel: FileAccessLevel? = null,
    /** Per-user grants, in specific mode. Roles for folders, levels for files. */
    val roles: Map<String, DriveRole> = emptyMap(),
    val levels: Map<String, FileAccessLevel> = emptyMap(),
    val search: String = "",
    /**
     * What a folder above already gives each person — the floor nothing here
     * may go below.
     *
     * Google Drive's rule, which the server now enforces: inside a shared
     * folder someone can be given **more** on a subfolder or a file, never
     * less. The picker locks the boxes at or below this and the row's own
     * checkbox, so they cannot be lowered or removed where they were never
     * granted.
     */
    val inherited: Map<String, DriveRole> = emptyMap(),
) {
    val selectedCount: Int get() = maxOf(roles.size, levels.size)

    /** The level a folder above already grants [userId] here; 0 when none does. */
    fun floor(userId: String, forFolder: Boolean): Int = inherited[userId]?.let { role ->
        if (forFolder) role.level else FileAccessLevel.fromRole(role).level
    } ?: 0

    /** What this person is granted here, as a level — 0 when they are not. */
    fun levelOf(userId: String, forFolder: Boolean): Int =
        if (forFolder) roles[userId]?.level ?: 0 else levels[userId]?.level ?: 0

    /**
     * The draft after a click on the box at [clicked] for [userId].
     *
     * Nested: a box takes the ones below it with it, unticking drops one
     * level, and level 0 stops sharing — except that an inherited floor
     * cannot be crossed.
     */
    fun clicked(userId: String, clicked: Int, forFolder: Boolean): AccessDraft {
        val next = maxOf(nextAccessLevel(levelOf(userId, forFolder), clicked), floor(userId, forFolder))
        return atLevel(userId, next, forFolder)
    }

    /**
     * Everyone below their inherited floor raised up to it.
     *
     * An inherited row does not always say what it grants twice: a folder's
     * row carries `inherited_role` and may carry no `role` at all, which
     * reads back as Viewer. Drawn as it arrived, the grid would show Viewer
     * ticked with the Editor box disabled and unticked — less than the person
     * actually has. The floors are the server's word, so they win.
     */
    fun liftedToFloors(forFolder: Boolean): AccessDraft =
        inherited.keys.fold(this) { draft, userId ->
            val floor = draft.floor(userId, forFolder)
            if (draft.levelOf(userId, forFolder) < floor) draft.atLevel(userId, floor, forFolder) else draft
        }

    /** Sets (or clears, at 0) one person's grant. */
    fun atLevel(userId: String, level: Int, forFolder: Boolean): AccessDraft = if (forFolder) {
        copy(roles = DriveRole.ofLevel(level)?.let { roles + (userId to it) } ?: (roles - userId))
    } else {
        copy(levels = FileAccessLevel.ofLevel(level)?.let { levels + (userId to it) } ?: (levels - userId))
    }

    /** The wire entries for a folder, over [eligible] in project-wide mode. */
    fun folderEntries(eligible: List<DrivePerson>): List<DriveAccessEntry> =
        if (projectWide) {
            projectRole?.let { role ->
                eligible.map { DriveAccessEntry(userId = it.id, role = role, permissions = role.permissions) }
            }.orEmpty()
        } else {
            roles.map { (id, role) -> DriveAccessEntry(userId = id, role = role, permissions = role.permissions) }
        }

    /**
     * The wire entries for a file, over [eligible] in project-wide mode.
     *
     * [skipCovered] leaves out the people the folder already covers at the
     * level chosen for them — what the upload step sends, because a grant the
     * folder already gives is not this file's to make (they can still be
     * given more here).
     */
    fun fileEntries(eligible: List<DrivePerson>, skipCovered: Boolean = false): List<DriveAccessEntry> =
        if (projectWide) {
            projectLevel?.let { level ->
                eligible.map { DriveAccessEntry(userId = it.id, permissions = level.permissions) }
                    .filterNot { skipCovered && level.level <= floor(it.userId, forFolder = false) }
            }.orEmpty()
        } else {
            levels.map { (id, level) -> DriveAccessEntry(userId = id, permissions = level.permissions) }
                .filterNot { skipCovered && levels[it.userId]?.level.orZero() <= floor(it.userId, forFolder = false) }
        }
}

private fun Int?.orZero(): Int = this ?: 0

/** The upload drawer (`UploadFilesDrawer.jsx`). */
data class UploadDraft(
    val files: List<PickedFile> = emptyList(),
    /** Root only: null is the Drive root; otherwise an existing folder was picked. */
    val destinationFolderId: String? = null,
    val pickExisting: Boolean = false,
    val description: String = "",
    val showDetails: Boolean = false,
    val access: AccessDraft = AccessDraft(),
    val accessExpanded: Boolean = false,
    /** Dropped-in files skipped for their extension, shown so the user knows why. */
    val unsupported: List<PickedFile> = emptyList(),
) {
    val canSubmit: Boolean get() = files.isNotEmpty() && !(pickExisting && destinationFolderId == null)
}

/** The create-folder drawer (`CreateFolderDrawer.jsx`). */
data class NewFolderDraft(
    val name: String = "",
    val description: String = "",
    val destinationFolderId: String? = null,
    val pickExisting: Boolean = false,
    val access: AccessDraft = AccessDraft(),
    val submitting: Boolean = false,
    /** The server's refusal of the name (a sibling has it), shown on the field until it is edited. */
    val nameError: String? = null,
) {
    val canSubmit: Boolean
        get() = name.isNotBlank() && name.length <= MAX_FOLDER_NAME &&
            !(pickExisting && destinationFolderId == null) && !submitting
}

const val MAX_FOLDER_NAME = 100

/** The edit-info drawer (`EditItemDrawer.jsx`): a file's name is shown without its extension. */
data class EditDraft(
    val item: DriveItem,
    val name: String,
    val description: String,
    val submitting: Boolean = false,
    /** As [NewFolderDraft.nameError]. */
    val nameError: String? = null,
) {
    val canSubmit: Boolean get() = name.isNotBlank() && !submitting
}

/**
 * The Resolve duplicates dialog — `ResolveDuplicatesBottomSheet` on Android.
 *
 * Which rows it lists is not stored: it is every upload the server refused for
 * its name ([QueuedUpload.needsRename]), so a row that is renamed or cancelled
 * leaves it by itself.
 */
data class RenameUploadsState(
    val open: Boolean = false,
    /** Upload id → what the user has typed. A row not here shows its suggestion. */
    val names: Map<String, String> = emptyMap(),
    /** Upload id → what is wrong with the name last submitted for it. */
    val errors: Map<String, String> = emptyMap(),
)

/** Which of the share drawer's two tabs is open — a file has both, a folder only People. */
enum class ShareTab(private val labelKey: String) {
    People(S.section_people),
    Link(S.drive_share_tab_link),
    ;

    val label: String get() = str(labelKey)
}

/** The share-via-link form and its list of live links (`ShareViaLink.jsx`). */
data class ShareLinkForm(
    val recipients: String = "",
    val permission: LinkPermission = LinkPermission.View,
    val expiresInMillis: Long = DEFAULT_LINK_EXPIRY,
    val maxViews: Int = 0,
    val message: String = "",
    val submitting: Boolean = false,
    val links: List<DriveShareLink> = emptyList(),
    val loadingLinks: Boolean = false,
)

/** A week — the web's default share-link expiry. */
const val DEFAULT_LINK_EXPIRY = 7L * 24 * 60 * 60 * 1000

/** The share / manage-access drawer (`ShareDrawer.jsx`). */
data class ShareState(
    val item: DriveItem,
    val tab: ShareTab = ShareTab.People,
    val access: AccessDraft = AccessDraft(),
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val copyingLink: Boolean = false,
    val link: ShareLinkForm = ShareLinkForm(),
)

/** The "Move to…" picker (`MoveToDialog.jsx`). */
data class MoveToState(
    val items: List<DriveItem>,
    /** Null is the Drive root; the tree preselects it. */
    val targetFolderId: String? = null,
)

/** The in-app preview (`DriveManagement`'s preview modal and `DocumentViewer`). */
data class PreviewState(
    val item: DriveItem,
    val kind: PreviewKind,
    val loading: Boolean = true,
    /** The presigned address — what a video or audio player, or a browser, is handed. */
    val url: String? = null,
    /** Image bytes, decoded by the dialog. */
    val imageBytes: ByteArray? = null,
    /** PDF pages rasterised to PNG. */
    val pages: List<ByteArray> = emptyList(),
    val text: String? = null,
    val error: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is PreviewState &&
        other.item.id == item.id && other.loading == loading && other.url == url &&
        other.error == error && other.text == text &&
        (other.imageBytes?.size ?: 0) == (imageBytes?.size ?: 0) && other.pages.size == pages.size

    override fun hashCode(): Int = item.id.hashCode()
}

/** What the preview's arrows step between. */
private val STEPPABLE = setOf(PreviewKind.Image, PreviewKind.Video)

/** The pointer-anchored row menu — the web's controlled context `Dropdown`. */
data class ItemMenuState(val item: DriveItem, val x: Float, val y: Float)

/** Files dropped from the OS, waiting on the "Set File Permissions" step (`handleDropUpload`). */
data class DropUploadState(
    val files: List<PickedFile>,
    val access: AccessDraft = AccessDraft(),
)

/**
 * Everything the Drive window renders.
 *
 * One state for the whole tool rather than one per surface: the drawers,
 * the listing and the details panel share the viewer, the selection, the
 * favourites set and the crew, and splitting them would mean keeping several
 * copies of the current folder in step.
 */
@Suppress("LongParameterList") // One field per piece of screen state; see the class doc.
data class DriveUiState(
    val viewer: DriveViewer = DriveViewer(),
    val section: DriveSection = DriveSection.SharedWithMe,
    val myDriveFilter: MyDriveFilter = MyDriveFilter.All,
    val innerTab: DriveInnerTab = DriveInnerTab.Folders,
    val viewMode: DriveViewMode = DriveViewMode.List,
    val showTrash: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,

    // -- browsing ---------------------------------------------------------
    val listing: DriveListing = DriveListing(),
    val folderId: String? = null,
    val breadcrumb: List<DriveCrumb> = emptyList(),
    /** What is in the box, and what has been committed after the debounce. */
    val searchInput: String = "",
    val search: String = "",
    val sort: DriveSortSpec = DriveSortSpec(),
    val tags: List<DriveTag> = emptyList(),
    val tagFilterId: String? = null,
    /** Ids carrying the filtered tag; null when no tag filter is on. */
    val taggedIds: Set<String>? = null,
    val showFavouritesOnly: Boolean = false,
    val selected: Set<String> = emptySet(),
    /**
     * Ids the user has starred.
     *
     * Held separately from the items rather than read off each row: the star is
     * per-user and the listing is shared, so a row's own `is_favorite` is only
     * populated on some routes. One authoritative set, fetched from the
     * lightweight `/favorites/ids`, is what keeps the star consistent between
     * the list and the grid.
     */
    val favouriteIds: Set<String> = emptySet(),
    /** The unread dots, rolled up from the ledger over the current listing. */
    val unread: DriveUnread = DriveUnread(),
    /** Small image previews by file id, fetched lazily for the grid and the name cell. */
    val thumbnails: Map<String, ByteArray> = emptyMap(),

    // -- people -----------------------------------------------------------
    val crew: List<DrivePerson> = emptyList(),
    /** Who may be given access — null until known, then the drive-view ids. */
    val shareableIds: Set<String>? = null,

    // -- surfaces ---------------------------------------------------------
    val trash: TrashState = TrashState(),
    val activityLog: ActivityLogState = ActivityLogState(),
    val uploads: List<QueuedUpload> = emptyList(),
    val operationsExpanded: Boolean = true,
    val renameUploads: RenameUploadsState = RenameUploadsState(),
    val details: DetailsState = DetailsState(),
    val fileRequests: FileRequestState = FileRequestState(),
    val prompt: DrivePrompt? = null,
    val upload: UploadDraft? = null,
    val newFolder: NewFolderDraft? = null,
    val edit: EditDraft? = null,
    val share: ShareState? = null,
    val moveTo: MoveToState? = null,
    val preview: PreviewState? = null,
    val menu: ItemMenuState? = null,
    val dropUpload: DropUploadState? = null,
) {

    val isSearching: Boolean get() = search.isNotBlank()

    /** The root crumb's name — the two sections' roots are named differently. */
    val rootName: String get() = section.rootName

    /** The web's `combinedData` — what the table and the grid draw. */
    val rows: List<DriveItem>
        get() = combinedRows(listing, view)

    private val view: DriveView
        get() = DriveView(
            folderId = folderId,
            isSearching = isSearching,
            innerTab = innerTab,
            taggedIds = taggedIds,
            favouriteIds = favouriteIds,
            showFavouritesOnly = showFavouritesOnly,
            sort = sort,
        )

    /** Folder and file counts for the root tabs. */
    val innerTotals: Pair<Int, Int> get() = innerTabTotals(listing, view)

    val selectedItems: List<DriveItem> get() = rows.filter { it.id in selected }

    val folderById: Map<String, DriveItem> get() = listing.folders.associateBy { it.id }

    /** The crew this user may share with — filtered to drive viewers once those are known. */
    val sharePeople: List<DrivePerson>
        get() = shareableIds?.let { allowed -> crew.filter { it.id in allowed } } ?: crew

    /** The name of the folder the user is in, for "Files will be uploaded to …". */
    val currentFolderName: String get() = breadcrumb.lastOrNull()?.name ?: rootName

    /** Upload, New folder and Request files appear only in My Drive with posting rights. */
    val canCreateHere: Boolean get() = viewer.canCreate && section == DriveSection.MyDrive

    /** The folder the user is standing in, when they are in one. */
    val openFolder: DriveItem? get() = folderId?.let { folderById[it] }

    /**
     * Whether anything may be **added** where the user is standing — Upload,
     * New folder, Request files, an OS drop (ZL-21871).
     *
     * Not the same question as [canCreateHere], which asks which tab this is.
     * An Editor on a folder someone shared with them may add to it: that is
     * what Editor means, and the server already permits it. A Viewer may not,
     * and the root of "Shared with me" never can — it is a list of other
     * people's items with no folder to add to.
     *
     * Not the same question as [canMoveHere] either (ZL-21869): adding a file
     * is the point of Editor, while Move rearranges the owner's own tree.
     */
    val canAddHere: Boolean
        get() = viewer.canCreate &&
            (section == DriveSection.MyDrive || openFolder?.let { viewer.may(DriveAction.Edit, it) } == true)

    /**
     * Whether Move is offered at all here (ZL-21869).
     *
     * Withheld everywhere under "Shared with me", at every depth and whatever
     * the viewer's own `can_edit` says: those items belong to someone else,
     * and only their creator may take one out of the folder it was shared in —
     * the server answers `only_owner_can_move_out_of_folder` to anyone else.
     * A subfolder of a shared folder is still this section, so this covers
     * depth too.
     */
    val canMoveHere: Boolean get() = section != DriveSection.SharedWithMe

    /**
     * How many of the selection a bulk delete would actually remove.
     *
     * The server permission-checks each item, so a mixed selection partially
     * succeeds. Knowing the number up front is what lets the toolbar say
     * "Delete 17 of 20" instead of reporting the shortfall afterwards.
     */
    val deletableCount: Int get() = viewer.eligible(DriveAction.Delete, selectedItems).size

    val movableCount: Int get() = viewer.eligible(DriveAction.Edit, selectedItems).size

    val downloadableCount: Int
        get() = viewer.eligible(DriveAction.Download, selectedItems).count { !it.isFolder }

    /**
     * What the rename box holds for [row]: what the user typed, else a free
     * name close to the original — never one an upload or an item in the same
     * folder already has.
     */
    fun renameValue(row: QueuedUpload): String = renameUploads.names[row.id] ?: run {
        val taken = uploads.filter { it.destinationFolderId == row.destinationFolderId }.map { it.fileName } +
            listing.files.filter { it.parentFolderId == row.destinationFolderId }.map { it.name }
        suggestFreeName(row.fileName, taken.toSet())
    }

    /** Uploads still running, for the operations panel's badge. */
    val activeUploads: List<QueuedUpload> get() = uploads.filterNot { it.isSettled }

    /**
     * The images and videos the preview steps through — the listing's own
     * files in its own order, so the arrows follow what is on screen.
     */
    val mediaRows: List<DriveItem>
        get() = rows.filter { !it.isFolder && it.previewKind in STEPPABLE && viewer.may(DriveAction.View, it) }

    /**
     * The image or video either side of the one being previewed; null at the
     * end of the run, or when the preview is not one of them (a PDF, text).
     */
    fun previewNeighbour(forward: Boolean): DriveItem? {
        val current = preview?.item ?: return null
        val run = mediaRows
        val at = run.indexOfFirst { it.id == current.id }
        if (at < 0) return null
        return run.getOrNull(if (forward) at + 1 else at - 1)
    }

    fun isFavourite(item: DriveItem): Boolean = item.id in favouriteIds

    fun refOf(item: DriveItem): DriveRef = DriveRef(item.id, item.kind)

    /** Whether anything modal is up — the listing's own shortcuts stand down then. */
    val hasOverlay: Boolean
        get() = prompt != null || upload != null || newFolder != null || edit != null ||
            share != null || moveTo != null || preview != null || dropUpload != null ||
            activityLog.open || fileRequests.open || renameUploads.open
}
