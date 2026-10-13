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
 * also walks the *current* tree (see [driveUnreadFrom]).
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
) {
    val isFolder: Boolean get() = unit == DriveBadgeWire.FOLDER_UNIT
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
     * A folder or file was opened: its own rows are read, here and on the
     * server — the web's `emitForNotificationRead` with a `reference_id`.
     * Rows of what lies *inside* a folder are untouched, so each file's dot
     * stays until the file itself is opened.
     */
    fun readItem(id: String) {}

    /** The items are gone; their rows have nothing left to point at. Local only. */
    fun forget(ids: Collection<String>) {}

    companion object {
        val None: DriveBadges = object : DriveBadges {}
    }
}

/**
 * The unread the Drive screen wears, rolled up from the ledger.
 *
 * Every row is one unread, the way the web counts them — not one per item.
 */
data class DriveUnread(
    /** Folder id → unread inside it, its own included. */
    val folders: Map<String, Int> = emptyMap(),
    /** File id → unread about that file. */
    val files: Map<String, Int> = emptyMap(),
    /** The My Drive section tab. */
    val myDrive: Int = 0,
    /** The Shared with me section tab. */
    val shared: Int = 0,
    val sharedFolderRows: Int = 0,
    val sharedFileRows: Int = 0,
    /** Shared file rows with no folder above them. */
    val sharedRootFileRows: Int = 0,
) {
    fun of(item: DriveItem): Int = (if (item.isFolder) folders else files)[item.id] ?: 0

    fun of(section: DriveSection): Int = if (section == DriveSection.MyDrive) myDrive else shared

    /**
     * The Folders tab at the root: folder rows, plus every shared file that
     * sits inside a folder. Zero in My Drive — the web draws the inner tabs'
     * unread for Shared with me only.
     */
    fun foldersTab(section: DriveSection): Int {
        if (section != DriveSection.SharedWithMe) return 0
        return sharedFolderRows + (sharedFileRows - sharedRootFileRows).coerceAtLeast(0)
    }

    /** The Files tab at the root: loose shared files only. */
    fun filesTab(section: DriveSection): Int = if (section == DriveSection.SharedWithMe) sharedRootFileRows else 0

    fun tab(section: DriveSection, tab: DriveInnerTab): Int =
        if (tab == DriveInnerTab.Folders) foldersTab(section) else filesTab(section)
}

/**
 * The web's `getDriveBadgeCounts`, row for row (`BadgeDB.js:1030-1176`).
 *
 * A row counts toward every folder in its stored ancestry, toward its own id
 * (a folder row's `reference_id` is the folder, ZL-18873), and — ZL-20181 —
 * toward every folder above the item *now*: the stored ancestry is a snapshot
 * from the day the notification was written, so a folder moved since then
 * would otherwise leave its new parent's count short. [parentByFolder] and
 * [folderByFile] are that current tree, from the live listing.
 *
 * The section a row belongs to is `reference_data.view`, else a virtual
 * `level_1`; a row with neither falls back to Android's old rule — an action
 * that says "shared" is Shared with me, anything else My Drive.
 */
fun driveUnreadFrom(
    rows: List<DriveUnreadRow>,
    parentByFolder: Map<String, String>,
    folderByFile: Map<String, String>,
): DriveUnread {
    val tally = UnreadTally()
    rows.forEach { tally.add(it, it.ancestry(parentByFolder, folderByFile)) }
    return tally.result()
}

/** The running counts of one roll-up; a class so [driveUnreadFrom] stays a loop. */
private class UnreadTally {
    private val folders = mutableMapOf<String, Int>()
    private val files = mutableMapOf<String, Int>()
    private var myDrive = 0
    private var shared = 0
    private var sharedFolderRows = 0
    private var sharedFileRows = 0
    private var sharedRootFileRows = 0

    fun add(row: DriveUnreadRow, ancestry: Set<String>) {
        val inShared = row.resolvedView() == DriveBadgeWire.SHARED_VIEW
        if (inShared) shared++ else myDrive++
        ancestry.forEach { folders.merge(it, 1, Int::plus) }
        when {
            row.isFolder -> if (inShared) sharedFolderRows++
            else -> addFile(row, inShared, ancestry.isEmpty())
        }
    }

    private fun addFile(row: DriveUnreadRow, inShared: Boolean, atRoot: Boolean) {
        if (row.referenceId.isNotBlank()) files.merge(row.referenceId, 1, Int::plus)
        if (!inShared) return
        sharedFileRows++
        if (atRoot) sharedRootFileRows++
    }

    fun result() =
        DriveUnread(folders, files, myDrive, shared, sharedFolderRows, sharedFileRows, sharedRootFileRows)
}

/** The folders a row counts toward, each once — the web's per-badge `Set`. */
private fun DriveUnreadRow.ancestry(
    parentByFolder: Map<String, String>,
    folderByFile: Map<String, String>,
): Set<String> {
    val ids = linkedSetOf<String>()
    listOf(level1, level2, level3).plus(deeper).filter(::isRealFolder).forEach(ids::add)
    if (isFolder && isRealFolder(referenceId)) ids += referenceId

    // The current chain: from the folder's parent, or from the file's folder.
    var cursor = referenceId.takeIf(::isRealFolder)?.let { if (isFolder) parentByFolder[it] else folderByFile[it] }
    val walked = mutableSetOf<String>()
    while (cursor != null && isRealFolder(cursor) && walked.add(cursor)) {
        ids += cursor
        cursor = parentByFolder[cursor]
    }
    return ids
}

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

/** The listing's own parent links — the "current tree" the roll-up walks. */
fun DriveListing.driveUnread(rows: List<DriveUnreadRow>): DriveUnread = driveUnreadFrom(
    rows = rows,
    parentByFolder = folders.mapNotNull { f -> f.parentFolderId?.let { f.id to it } }.toMap(),
    folderByFile = files.mapNotNull { f -> f.parentFolderId?.let { f.id to it } }.toMap(),
)
