package com.zillit.desktop.feature.drive.domain

/**
 * The listing order, as one choice rather than the server's two fields.
 *
 * The server takes `sort_by` and `sort_order` separately; every client picks
 * from a fixed set of six combinations. Modelling the *combination* is what
 * makes the picker a list of six labels instead of two dropdowns that can
 * produce "size, oldest first" — an order nobody wants and the server does not
 * index for.
 */
enum class DriveSort(val id: String, val label: String, val field: String, val order: String) {
    DateDesc("date_desc", "Newest first", "date", "desc"),
    DateAsc("date_asc", "Oldest first", "date", "asc"),
    NameAsc("name_asc", "Name (A–Z)", "name", "asc"),
    NameDesc("name_desc", "Name (Z–A)", "name", "desc"),
    SizeDesc("size_desc", "Size (largest)", "size", "desc"),
    SizeAsc("size_asc", "Size (smallest)", "size", "asc"),
    ;

    companion object {
        /**
         * Ported from `mapSortFilterToServer`, including its default.
         *
         * Note that the web's table omits `date_desc` from its own map and
         * relies on the fallback to produce it. Spelled out here, because a
         * default reached by falling off the end of a lookup is one nobody can
         * find when it turns out to be wrong.
         */
        fun from(id: String?): DriveSort = entries.firstOrNull { it.id == id } ?: DateDesc
    }
}

/** How the listing is bucketed under its headings. */
enum class DriveGrouping(val wire: String?, val label: String) {
    None(null, "None"),
    Type("type", "Type"),
    UploadedBy("uploaded_by", "Uploaded by"),
    Extension("extension", "Extension"),
    ;

    /**
     * Which bucket [item] falls in.
     *
     * Ported from `getServerGroupKeyForItem` — the server groups too, but the
     * client re-derives the key so an appended page lands in the right bucket
     * without a refetch.
     */
    fun keyFor(item: DriveItem): String = when (this) {
        None -> "Items"
        Type -> if (item.isFolder) "Folders" else "Files"
        UploadedBy -> item.uploadedByName.ifBlank { "Unknown" }
        Extension -> when {
            item.isFolder -> "Folder"
            item.extension.isBlank() -> "No extension"
            else -> item.extension.uppercase()
        }
    }

    companion object {
        fun from(wire: String?): DriveGrouping =
            entries.firstOrNull { it.wire == wire } ?: None
    }
}

/** The one-click narrowings above the listing. */
enum class DriveQuickFilter(val wire: String?, val label: String) {
    All(null, "All"),
    Mine("mine", "Mine"),
    Shared("shared", "Shared with me"),
    LastSevenDays("last_7_days", "Last 7 days"),
    LargeFiles("large_files", "Large files"),
    ;

    companion object {
        fun from(wire: String?): DriveQuickFilter =
            entries.firstOrNull { it.wire == wire } ?: All
    }
}

/**
 * Everything that narrows one listing request.
 *
 * One object rather than eight parameters threaded through the repository,
 * the view model and the cache key: they always travel together, and a cache
 * key built from a subset of them is a cache that serves the wrong folder.
 */
data class DriveQuery(
    /** Null means the drive root, which the server asks for as `root=true`. */
    val folderId: String? = null,
    val search: String = "",
    val sort: DriveSort = DriveSort.DateDesc,
    val grouping: DriveGrouping = DriveGrouping.None,
    val quickFilter: DriveQuickFilter = DriveQuickFilter.All,
    val tagId: String? = null,
    val offset: Int = 0,
    val limit: Int = PAGE_SIZE,
) {

    /**
     * The query string, exactly as `driveApi.js` builds it.
     *
     * `root=true` and `folder_id` are mutually exclusive: sending both makes
     * the server ignore the folder and answer with the root, which presents as
     * "clicking into a folder does nothing".
     */
    fun toParameters(): Map<String, Any?> = buildMap {
        if (folderId == null) put("root", "true") else put("folder_id", folderId)
        search.trim().takeIf { it.isNotEmpty() }?.let { put("search", it) }
        put("sort_by", sort.field)
        put("sort_order", sort.order)
        grouping.wire?.let { put("group_by", it) }
        quickFilter.wire?.let { put("quick_filter", it) }
        tagId?.takeIf { it.isNotBlank() }?.let { put("tag_id", it) }
        put("limit", limit)
        put("offset", offset)
        put("paginate", "true")
    }

    /**
     * A key that identifies this exact result set.
     *
     * Every field participates. The web's 30-second listing cache is keyed the
     * same way (`buildDriveListCacheKey`) and the reason is worth stating: a
     * key that omits, say, the quick filter serves "Mine" rows for "All" and
     * the user sees their own files and concludes the drive is empty.
     */
    fun cacheKey(): String = listOf(
        folderId ?: "root",
        search.trim().lowercase(),
        sort.id,
        grouping.wire ?: "none",
        quickFilter.wire ?: "all",
        tagId ?: "",
        offset.toString(),
        limit.toString(),
    ).joinToString("|")

    companion object {
        /** The server's default. Its hard cap is 200. */
        const val PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 200
    }
}

/** One page of the listing, files and folders together. */
data class DrivePage(
    val items: List<DriveItem>,
    val total: Int,
) {
    /** Folders first within a page — a mixed sort buries them among the files. */
    val ordered: List<DriveItem>
        get() = items.sortedByDescending { it.isFolder }
}

/** One bucket of a grouped listing. */
data class DriveGroup(val key: String, val items: List<DriveItem>)

/** Buckets a page under the chosen grouping, preserving the server's order. */
fun List<DriveItem>.groupedBy(grouping: DriveGrouping): List<DriveGroup> {
    if (grouping == DriveGrouping.None) return listOf(DriveGroup("Items", this))
    // `groupBy` on a list preserves first-seen key order, which is the server's
    // sort. Re-sorting the keys here would silently override the sort the user
    // chose with an alphabetical one.
    return groupBy { grouping.keyFor(it) }.map { (key, items) -> DriveGroup(key, items) }
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

private const val BYTE_STEP = 1024.0
private const val DECIMAL_CUTOFF = 10.0
private const val HUNDRED = 100
