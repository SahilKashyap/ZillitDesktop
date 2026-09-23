package com.zillit.desktop.feature.drive.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The two halves of the Drive, as the web's outer tabs split them
 * (`DriveHeader.jsx`): what is mine, and what others have shared with me.
 */
enum class DriveSection(private val labelKey: String, private val rootNameKey: String) {
    MyDrive(S.drive_section_my_drive, S.txt_drive),
    SharedWithMe(S.drive_section_shared_with_me, S.drive_section_shared_with_me),
    ;

    val label: String get() = str(labelKey)
    val rootName: String get() = str(rootNameKey)
}

/** The My Drive narrowing (ZL-19247): everything, or only what I have shared on. */
enum class MyDriveFilter(private val labelKey: String) {
    All(S.all),
    SharedByMe(S.drive_filter_shared_by_me),
    ;

    val label: String get() = str(labelKey)
}

/** The root splits into two tabs; inside a folder both kinds share one list. */
enum class DriveInnerTab(private val labelKey: String) {
    Folders(S.folders),
    Files(S.drive_kind_files),
    ;

    val label: String get() = str(labelKey)
}

/**
 * The server-side scope of one listing request — the `quick_filter` the two
 * list routes take. Resolved from the section and the My Drive filter exactly
 * as `DriveManagement.getQuickFilter` does.
 */
enum class DriveScope(val wire: String) {
    Mine("mine"),
    Shared("shared"),
    SharedByMe("shared_by_me"),
    ;

    companion object {
        fun of(section: DriveSection, filter: MyDriveFilter): DriveScope = when {
            section == DriveSection.SharedWithMe -> Shared
            filter == MyDriveFilter.SharedByMe -> SharedByMe
            else -> Mine
        }
    }
}

/**
 * Everything that narrows one listing request.
 *
 * The web fetches the *whole* scope in one go (`getAllFileForDrive` and
 * `getAllFoldersForDrive` with no folder filter) and narrows to the open
 * folder on the client. That is what lets it size folders by summing their
 * contents, draw a folder tree for "Move to…", rebuild a breadcrumb from a
 * search hit's ancestry, and switch folders without a round trip — so this
 * client does the same.
 */
data class DriveListQuery(
    val scope: DriveScope = DriveScope.Mine,
    val search: String = "",
) {
    /** The query string, exactly as `driveApi.js` builds it. */
    fun toParameters(): Map<String, Any?> = buildMap {
        put("quick_filter", scope.wire)
        search.trim().takeIf { it.isNotEmpty() }?.let { put("search", it) }
    }
}

/** Which column the listing is ordered by — the web table's three sorters. */
enum class DriveSortColumn { Name, Modified, Size }

/** A column and a direction; null column keeps the server's order. */
data class DriveSortSpec(val column: DriveSortColumn? = null, val ascending: Boolean = true) {

    /** Clicking a header: first ascending, then descending, then off — antd's cycle. */
    fun toggled(next: DriveSortColumn): DriveSortSpec = when {
        column != next -> DriveSortSpec(next, ascending = true)
        ascending -> DriveSortSpec(next, ascending = false)
        else -> DriveSortSpec(null)
    }
}

/**
 * The inputs to one listing as the user sees it.
 *
 * Every narrowing the page applies, gathered so [combinedRows] and
 * [innerTabTotals] read the same filters and can never disagree about what is
 * on screen.
 */
data class DriveView(
    val folderId: String? = null,
    val isSearching: Boolean = false,
    val innerTab: DriveInnerTab = DriveInnerTab.Folders,
    val taggedIds: Set<String>? = null,
    val favouriteIds: Set<String> = emptySet(),
    val showFavouritesOnly: Boolean = false,
    val sort: DriveSortSpec = DriveSortSpec(),
)

/**
 * The rows the table shows — `DriveManagement.combinedData`, as a function.
 *
 * Folders first, then files; narrowed to the open folder unless a search is
 * active (search matches come from anywhere and are shown together); then
 * the tag filter, then the favourites filter; and, at the root only, the
 * Folders/Files inner tab. Folder sizes are the sum of every file beneath
 * them, bubbled up through their ancestors, with a direct-file count beside.
 */
fun combinedRows(listing: DriveListing, view: DriveView): List<DriveItem> {
    val folders = if (view.isSearching) {
        listing.folders
    } else {
        listing.folders.filter { it.parentFolderId == view.folderId }
    }
    val files = if (view.isSearching) {
        listing.files
    } else {
        listing.files.filter { it.parentFolderId == view.folderId }
    }

    val sizes = folderSizes(listing)
    val counts = listing.files.groupingBy { it.parentFolderId }.eachCount()

    var rows = folders.map { folder ->
        folder.copy(
            sizeBytes = sizes[folder.id] ?: 0L,
            itemCount = counts[folder.id] ?: 0,
        )
    } + files

    view.taggedIds?.let { tagged -> rows = rows.filter { it.id in tagged } }
    if (view.showFavouritesOnly) rows = rows.filter { it.id in view.favouriteIds }
    if (!view.isSearching && view.folderId == null) {
        rows = rows.filter { if (view.innerTab == DriveInnerTab.Folders) it.isFolder else !it.isFolder }
    }
    return rows.distinctBy { it.id }.sortedWith(view.sort.comparator())
}

/**
 * The counts beside each root tab — `innerTabTotals`: the same folder, tag
 * and favourites narrowing as [combinedRows], so the number a tab promises is
 * what switching to it shows.
 */
fun innerTabTotals(listing: DriveListing, view: DriveView): Pair<Int, Int> {
    fun matches(item: DriveItem): Boolean {
        if (!view.isSearching && item.parentFolderId != view.folderId) return false
        if (view.taggedIds != null && item.id !in view.taggedIds) return false
        if (view.showFavouritesOnly && item.id !in view.favouriteIds) return false
        return true
    }
    return listing.folders.count(::matches) to listing.files.count(::matches)
}

/**
 * Bytes per folder, each folder's own files bubbled up to every ancestor —
 * `folderTotalSizeMap`. A folder whose files all sit in subfolders still
 * reads as the size of what it holds.
 */
private fun folderSizes(listing: DriveListing): Map<String, Long> {
    val parentOf = listing.folders.associate { it.id to it.parentFolderId }
    val totals = mutableMapOf<String, Long>()
    listing.files.forEach { file ->
        var folder = file.parentFolderId
        // Bounded by the tree's depth; the visited set guards a cycle a
        // corrupt parent chain could otherwise loop on.
        val seen = mutableSetOf<String>()
        while (folder != null && seen.add(folder)) {
            totals[folder] = (totals[folder] ?: 0L) + file.sizeBytes
            folder = parentOf[folder]
        }
    }
    return totals
}

private fun DriveSortSpec.comparator(): Comparator<DriveItem> {
    // Folders stay ahead of files whatever the column, as every file manager
    // and the web's own folder-then-file concatenation do.
    val kind = compareByDescending<DriveItem> { it.isFolder }
    val column = column ?: return kind
    val by: Comparator<DriveItem> = when (column) {
        DriveSortColumn.Name -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        DriveSortColumn.Modified -> compareBy { it.modifiedAt ?: 0L }
        DriveSortColumn.Size -> compareBy { it.sizeBytes }
    }
    return kind.then(if (ascending) by else by.reversed())
}

/**
 * The trail to [folderId], rebuilt from the folders' own ancestry — what the
 * web does when a folder is opened from a search hit (ZL-20182), and what a
 * client with the whole scope in hand can do for every navigation.
 *
 * Bounded by the tree; a parent the scope cannot see ends the trail there,
 * which is right for "Shared with me", where a shared subfolder's parents are
 * not the user's to browse.
 */
fun breadcrumbTo(folderId: String?, folders: List<DriveItem>): List<DriveCrumb> {
    if (folderId == null) return emptyList()
    val byId = folders.associateBy { it.id }
    val trail = ArrayDeque<DriveCrumb>()
    var current: String? = folderId
    val seen = mutableSetOf<String>()
    while (current != null && seen.add(current)) {
        val folder = byId[current] ?: break
        trail.addFirst(DriveCrumb(folder.id, folder.name))
        current = folder.parentFolderId
    }
    return trail.toList()
}

/** One node of the folder tree the destination pickers draw. */
data class FolderNode(
    val folder: DriveItem,
    val children: List<FolderNode>,
    val depth: Int,
)

/**
 * The scope's folders as a tree — `MoveToDialog.treeData`,
 * `DriveDestinationField.treeData`. [excluded] folders (and everything beneath
 * them) are left out: an item cannot be moved into itself or its own
 * descendants, and the server would refuse the loop anyway.
 */
fun folderTree(folders: List<DriveItem>, excluded: Set<String> = emptySet()): List<FolderNode> {
    val byParent = folders.filter { it.id !in excluded }.groupBy { it.parentFolderId }
    val known = folders.map { it.id }.toSet()
    fun build(parentId: String?, depth: Int, seen: Set<String>): List<FolderNode> =
        byParent[parentId].orEmpty()
            .sortedBy { it.name.lowercase() }
            .filter { it.id !in seen }
            .map { folder ->
                FolderNode(folder, build(folder.id, depth + 1, seen + folder.id), depth)
            }
    // A folder whose parent is outside the scope (shared subfolders) roots
    // the tree alongside the true roots, so it is still reachable.
    val roots = build(null, 0, emptySet())
    val orphans = folders
        .filter { it.id !in excluded && it.parentFolderId != null && it.parentFolderId !in known }
        .sortedBy { it.name.lowercase() }
        .map { FolderNode(it, build(it.id, 1, setOf(it.id)), 0) }
    return roots + orphans
}

/** Every node of a tree, depth-first, for a flat picker list. */
fun List<FolderNode>.flattened(): List<FolderNode> =
    flatMap { listOf(it) + it.children.flattened() }

/** Ids of [folderId] and every folder beneath it. */
fun descendantsOf(folderId: String, folders: List<DriveItem>): Set<String> {
    val byParent = folders.groupBy { it.parentFolderId }
    val out = mutableSetOf(folderId)
    val queue = ArrayDeque(listOf(folderId))
    while (queue.isNotEmpty()) {
        val next = queue.removeFirst()
        byParent[next].orEmpty().forEach { child ->
            if (out.add(child.id)) queue.addLast(child.id)
        }
    }
    return out
}

/** "1.20 MB" / "840 B" — the size column, and the storage meter's caption. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= BYTE_STEP && unit < units.lastIndex) {
        value /= BYTE_STEP
        unit++
    }
    val rendered = if (value < DECIMAL_CUTOFF) {
        val hundredths = (value * HUNDRED).toLong()
        "${hundredths / HUNDRED}.${(hundredths % HUNDRED).toString().padStart(2, '0')}"
    } else {
        value.toLong().toString()
    }
    return "$rendered ${units[unit]}"
}

/**
 * "just now", "3 minutes ago", "2 days ago" — dayjs's `fromNow`, which the
 * web's date cells use for anything in the last week. Older stamps are shown
 * as a date by the caller.
 */
fun relativeTime(millis: Long, now: Long): String {
    val seconds = ((now - millis) / MILLIS_PER_SECOND).coerceAtLeast(0)
    return when {
        seconds < SECONDS_JUST_NOW -> str(S.docusign_template_just_now)
        seconds < SECONDS_PER_MINUTE -> str(S.drive_time_a_minute_ago)
        seconds < SECONDS_PER_HOUR -> plural(seconds / SECONDS_PER_MINUTE, AgoUnit.Minute)
        seconds < SECONDS_PER_DAY -> plural(seconds / SECONDS_PER_HOUR, AgoUnit.Hour)
        else -> plural(seconds / SECONDS_PER_DAY, AgoUnit.Day)
    }
}

/** Whether [millis] is within the last seven days — when the web shows relative time. */
fun isRecent(millis: Long, now: Long): Boolean = now - millis < RECENT_WINDOW_MILLIS

private enum class AgoUnit(val oneKey: String, val manyKey: String) {
    Minute(S.drive_time_a_minute_ago, S.drive_time_minutes_ago),
    Hour(S.drive_time_an_hour_ago, S.drive_time_hours_ago),
    Day(S.desktop_drive_time_a_day_ago, S.drive_time_days_ago),
}

private fun plural(count: Long, unit: AgoUnit): String =
    if (count != 1L) str(unit.manyKey, count) else str(unit.oneKey)

private const val BYTE_STEP = 1024.0
private const val DECIMAL_CUTOFF = 10.0
private const val HUNDRED = 100
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_JUST_NOW = 45L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_DAY = 86_400L
private const val RECENT_WINDOW_MILLIS = 7L * SECONDS_PER_DAY * MILLIS_PER_SECOND
