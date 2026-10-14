package com.zillit.desktop.feature.drive.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** The words the notification service files Drive activity under. */
object DriveBadgeWire {
    /** `tool` on every Drive row. */
    const val TOOL = "drive_label"

    /** `unit` of a row about a folder; `reference_id` is the folder. */
    const val FOLDER_UNIT = "drive_folder_label"

    /** `unit` of a row about a file; `reference_id` is the file. */
    const val FILE_UNIT = "drive_file_label"

    /** `reference_data.view` (or a virtual `level_1`) of activity in My Drive. */
    const val MY_DRIVE_VIEW = "my_drive_unit"

    /** `reference_data.view` (or a virtual `level_1`) of activity in Shared with me. */
    const val SHARED_VIEW = "shared_with_me_unit"

    /** `level_1` of an item that lives at the drive root. */
    const val ROOT = "root"
}

/**
 * One unread Drive notification, cut down to what the dots need.
 *
 * `level_1` .. `level_3` and [deeper] are the ancestor folders of the item
 * when the notification was written — a snapshot, which is why the roll-up
 * prefers the *current* tree wherever it knows the item (see [driveUnreadFrom]).
 */
data class DriveUnreadRow(
    val unit: String,
    /** The folder or file the activity is about. */
    val referenceId: String,
    val action: String = "",
    val level1: String = "",
    val level2: String = "",
    val level3: String = "",
    /** `levels[]` — folders at depth four and below. */
    val deeper: List<String> = emptyList(),
    /** `reference_data.view`, blank when the server did not say. */
    val view: String = "",
    /**
     * `reference_data.folder_id` — the folder a file sat in when the row was
     * written, which every emitter stamps (iOS `DriveBadgeReader.swift:183-190`).
     */
    val folderId: String = "",
) {
    val isFolder: Boolean get() = unit == DriveBadgeWire.FOLDER_UNIT

    /**
     * Whether opening [openedFolderId] reads this row — iOS
     * `markDriveFolderAndChildrenRead` (`LocalNoticifationRealm.swift:3649-3675`):
     * the folder's own rows (`reference_id`), and the rows of what sits
     * directly in it (`reference_data.folder_id`). Deeper rows wait for their
     * own folder.
     */
    fun isReadWithFolder(openedFolderId: String): Boolean =
        openedFolderId.isNotBlank() && (referenceId == openedFolderId || folderId == openedFolderId)
}

/**
 * What the Drive screen takes from the host's notification ledger.
 *
 * The ledger itself lives in `core:badges`, which this module does not see;
 * the host hands over plain rows and applies reads.
 */
interface DriveBadges {
    /** The open production's unread Drive rows, re-emitted whenever the ledger moves. */
    val rows: Flow<List<DriveUnreadRow>> get() = emptyFlow()

    /**
     * A file was opened: its own rows are read, here and on the server —
     * the web's `emitForNotificationRead` with a `reference_id`.
     */
    fun readItem(id: String) {}

    /**
     * A folder was opened: its own rows and its direct children's
     * ([DriveUnreadRow.isReadWithFolder]) — iOS's folder open, which clears
     * the folder and every file sitting in it in one tap. Deeper rows stay
     * for their own folders.
     */
    fun readFolder(folderId: String) = readItem(folderId)

    /** The items are gone; their rows have nothing left to point at. Local only. */
    fun forget(ids: Collection<String>) {}

    companion object {
        val None: DriveBadges = object : DriveBadges {}
    }
}

/**
 * The unread the Drive screen wears, rolled up from the ledger.
 *
 * Every count is of distinct items, not rows — iOS ZL-18484: a file shared,
 * re-shared and edited is one unread file however many rows it left.
 */
data class DriveUnread(
    /** Folder id → distinct unread items inside it, its own events included. */
    val folders: Map<String, Int> = emptyMap(),
    /** File id → 1 for every file with unread activity (a dot, not a tally). */
    val files: Map<String, Int> = emptyMap(),
    /** The My Drive section tab. */
    val myDrive: Int = 0,
    /** The Shared with me section tab. */
    val shared: Int = 0,
    /** Distinct shared folders with unread events of their own. */
    val sharedFolders: Int = 0,
    /** Distinct shared files with unread events. */
    val sharedFiles: Int = 0,
    /** The [sharedFiles] with no folder above them — loose at the root. */
    val sharedRootFiles: Int = 0,
) {
    fun of(item: DriveItem): Int = (if (item.isFolder) folders else files)[item.id] ?: 0

    fun of(section: DriveSection): Int = if (section == DriveSection.MyDrive) myDrive else shared

    /**
     * The Folders tab at the root: folder events, plus every shared file that
     * sits inside a folder (iOS `HomeView.swift:296-307`). Zero in My Drive —
     * the inner tabs' unread is drawn for Shared with me only.
     */
    fun foldersTab(section: DriveSection): Int {
        if (section != DriveSection.SharedWithMe) return 0
        return sharedFolders + (sharedFiles - sharedRootFiles).coerceAtLeast(0)
    }

    /** The Files tab at the root: loose shared files only. */
    fun filesTab(section: DriveSection): Int = if (section == DriveSection.SharedWithMe) sharedRootFiles else 0

    fun tab(section: DriveSection, tab: DriveInnerTab): Int =
        if (tab == DriveInnerTab.Folders) foldersTab(section) else filesTab(section)
}

/**
 * The current folder tree, from the live listing: where each folder and
 * file sits *now*. [knownFolders] and [knownFiles] are every item the
 * listing holds — a root item has no parent link but is still known, and
 * known at the root.
 */
data class DriveTree(
    val parentByFolder: Map<String, String> = emptyMap(),
    val folderByFile: Map<String, String> = emptyMap(),
    val knownFolders: Set<String> = parentByFolder.keys,
    val knownFiles: Set<String> = folderByFile.keys,
)

/**
 * iOS's drive roll-up (`DriveBadgeReader.fetchAll`, ZL-20179 / ZL-18484 /
 * ZL-18885), as sets.
 *
 * - **Anchor.** A folder row is about its `reference_id`; a file row badges
 *   the folder that contains the file — where the live tree has it now,
 *   else `reference_data.folder_id`, else the innermost stored level.
 * - **Roll-up.** The anchor and every folder above it. Where the live tree
 *   knows the anchor the chain is walked there — the stored `level_*` are a
 *   creation-time snapshot never rewritten on a move, and crediting them
 *   kept badging a moved folder's old parents (ZL-20179). Only an anchor the
 *   listing does not hold (a search, an inaccessible parent) falls back to
 *   the stored path.
 * - **Distinct.** A folder's number is its set of unread items — folder
 *   anchors and file ids — so one item counts once wherever it moved and
 *   however many rows it left (ZL-18484). The section tabs count distinct
 *   folders plus distinct files.
 * - **Tab scope.** The row dots belong to the tab on screen: a row whose
 *   section is the other tab dots nothing here ([view], ZL-18885). The
 *   section of a row is `reference_data.view`, else a virtual `level_1`,
 *   else Android's old rule — an action that says "shared" is Shared with
 *   me. iOS scopes on `reference_data.view` alone, so a row with no view
 *   counted on the tile and in no tab; resolving it keeps the tabs adding up.
 */
fun driveUnreadFrom(
    rows: List<DriveUnreadRow>,
    tree: DriveTree = DriveTree(),
    /** The section whose row dots these are; null dots every row. */
    view: DriveSection? = null,
): DriveUnread {
    val tally = UnreadTally(tree, view?.wireView)
    rows.forEach(tally::add)
    return tally.result()
}

/** The tree as two maps — what the tests and the listing hand over. */
fun driveUnreadFrom(
    rows: List<DriveUnreadRow>,
    parentByFolder: Map<String, String>,
    folderByFile: Map<String, String>,
    view: DriveSection? = null,
): DriveUnread = driveUnreadFrom(rows, DriveTree(parentByFolder, folderByFile), view)

private val DriveSection.wireView: String
    get() = if (this == DriveSection.MyDrive) DriveBadgeWire.MY_DRIVE_VIEW else DriveBadgeWire.SHARED_VIEW

/** The running sets of one roll-up; a class so [driveUnreadFrom] stays a loop. */
private class UnreadTally(private val tree: DriveTree, private val view: String?) {
    private val folders = mutableMapOf<String, MutableSet<String>>()
    private val files = mutableSetOf<String>()
    private val myDriveFolders = mutableSetOf<String>()
    private val myDriveFiles = mutableSetOf<String>()
    private val sharedFolders = mutableSetOf<String>()
    private val sharedFiles = mutableSetOf<String>()
    private val sharedRootFiles = mutableSetOf<String>()

    fun add(row: DriveUnreadRow) {
        val rowView = row.resolvedView()
        val inShared = rowView == DriveBadgeWire.SHARED_VIEW
        val dotted = view == null || rowView == view
        if (row.isFolder) addFolder(row, inShared, dotted) else addFile(row, inShared, dotted)
    }

    private fun addFolder(row: DriveUnreadRow, inShared: Boolean, dotted: Boolean) {
        val anchor = row.folderAnchor().takeIf(::isRealFolder) ?: return
        (if (inShared) sharedFolders else myDriveFolders) += anchor
        if (dotted) credit(anchor, anchor, row, exclude = "")
    }

    private fun addFile(row: DriveUnreadRow, inShared: Boolean, dotted: Boolean) {
        val fileId = row.fileId().takeIf { it.isNotBlank() } ?: return
        (if (inShared) sharedFiles else myDriveFiles) += fileId
        val anchor = containingFolder(row, fileId)
        if (inShared && anchor.isEmpty()) sharedRootFiles += fileId
        if (!dotted) return
        files += fileId
        if (anchor.isNotEmpty()) credit(anchor, fileId, row, exclude = fileId)
    }

    /** [item] counts on [anchor] and every folder above it — see [driveUnreadFrom]. */
    private fun credit(anchor: String, item: String, row: DriveUnreadRow, exclude: String) {
        val above = if (anchor in tree.knownFolders) {
            liveChain(anchor)
        } else {
            row.storedPath().filter { it != anchor && it != exclude }
        }
        (listOf(anchor) + above).forEach { folders.getOrPut(it) { mutableSetOf() } += item }
    }

    /** The anchor's parents in the current tree, nearest first; cycle-guarded. */
    private fun liveChain(anchor: String): List<String> {
        val chain = mutableListOf<String>()
        val walked = mutableSetOf(anchor)
        var cursor = tree.parentByFolder[anchor]
        while (cursor != null && isRealFolder(cursor) && walked.add(cursor)) {
            chain += cursor
            cursor = tree.parentByFolder[cursor]
        }
        return chain
    }

    /** Where the file sits: the live tree first, then the row's own word, then its stored path. */
    private fun containingFolder(row: DriveUnreadRow, fileId: String): String = when {
        fileId in tree.knownFiles -> tree.folderByFile[fileId]?.takeIf(::isRealFolder).orEmpty()
        isRealFolder(row.folderId) -> row.folderId
        else -> row.storedPath().lastOrNull { it != fileId }.orEmpty()
    }

    fun result() = DriveUnread(
        folders = folders.mapValues { it.value.size },
        files = files.associateWith { 1 },
        myDrive = myDriveFolders.size + myDriveFiles.size,
        shared = sharedFolders.size + sharedFiles.size,
        sharedFolders = sharedFolders.size,
        sharedFiles = sharedFiles.size,
        sharedRootFiles = sharedRootFiles.size,
    )
}

/** The stored ancestry, outermost first, without the virtual markers. */
private fun DriveUnreadRow.storedPath(): List<String> =
    (listOf(level1, level2, level3) + deeper).filter(::isRealFolder)

/** The folder a folder row is about: its `reference_id`, else the innermost stored level (legacy rows). */
private fun DriveUnreadRow.folderAnchor(): String =
    referenceId.takeIf(::isRealFolder) ?: storedPath().lastOrNull().orEmpty()

/** The file a file row is about: its `reference_id`, else the innermost stored level (legacy `fileShared`). */
private fun DriveUnreadRow.fileId(): String =
    referenceId.takeIf { it.isNotBlank() } ?: storedPath().lastOrNull().orEmpty()

private fun DriveUnreadRow.resolvedView(): String = when {
    view == DriveBadgeWire.MY_DRIVE_VIEW || view == DriveBadgeWire.SHARED_VIEW -> view
    level1 == DriveBadgeWire.MY_DRIVE_VIEW || level1 == DriveBadgeWire.SHARED_VIEW -> level1
    action.contains("shared", ignoreCase = true) -> DriveBadgeWire.SHARED_VIEW
    else -> DriveBadgeWire.MY_DRIVE_VIEW
}

/** Not blank and not one of the virtual roots that stand where a folder id would. */
private fun isRealFolder(id: String): Boolean =
    id.isNotBlank() &&
        id != DriveBadgeWire.ROOT &&
        id != DriveBadgeWire.MY_DRIVE_VIEW &&
        id != DriveBadgeWire.SHARED_VIEW

/**
 * The listing's own links — the "current tree" the roll-up walks — with the
 * row dots scoped to [section].
 */
fun DriveListing.driveUnread(rows: List<DriveUnreadRow>, section: DriveSection? = null): DriveUnread =
    driveUnreadFrom(
        rows = rows,
        tree = DriveTree(
            parentByFolder = folders.mapNotNull { f -> f.parentFolderId?.let { f.id to it } }.toMap(),
            folderByFile = files.mapNotNull { f -> f.parentFolderId?.let { f.id to it } }.toMap(),
            knownFolders = folders.map { it.id }.toSet(),
            knownFiles = files.map { it.id }.toSet(),
        ),
        view = section,
    )
