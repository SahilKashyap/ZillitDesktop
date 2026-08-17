package com.zillit.desktop.feature.drive.ui

import com.zillit.desktop.feature.drive.domain.DriveAccessEntry
import com.zillit.desktop.feature.drive.domain.DriveAction
import com.zillit.desktop.feature.drive.domain.DriveActivity
import com.zillit.desktop.feature.drive.domain.DriveComment
import com.zillit.desktop.feature.drive.domain.DriveCrumb
import com.zillit.desktop.feature.drive.domain.DriveGroup
import com.zillit.desktop.feature.drive.domain.DriveGrouping
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveQuickFilter
import com.zillit.desktop.feature.drive.domain.DriveRef
import com.zillit.desktop.feature.drive.domain.DriveSort
import com.zillit.desktop.feature.drive.domain.DriveTag
import com.zillit.desktop.feature.drive.domain.DriveVersion
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.domain.QueuedUpload
import com.zillit.desktop.feature.drive.domain.StorageUsage
import com.zillit.desktop.feature.drive.domain.eligible
import com.zillit.desktop.feature.drive.domain.groupedBy

/** Everything the details panel knows about the item it is open on. */
data class DetailsState(
    val item: DriveItem? = null,
    val comments: List<DriveComment> = emptyList(),
    val activity: List<DriveActivity> = emptyList(),
    val versions: List<DriveVersion> = emptyList(),
    val access: List<DriveAccessEntry> = emptyList(),
    val commentDraft: String = "",
    val loading: Boolean = false,
) {
    val open: Boolean get() = item != null
}

/** A confirmation the user has to answer before something irreversible happens. */
data class DrivePrompt(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val event: DriveEvent,
)

/**
 * Everything the Drive window renders.
 *
 * One state for five pages rather than five: they share the viewer, the
 * selection, the favourites set and the upload queue, and splitting them would
 * mean keeping several copies of the current folder in step.
 */
data class DriveUiState(
    val viewer: DriveViewer = DriveViewer(),
    val destination: DriveDestination = DriveDestination.Browse,
    val viewMode: DriveViewMode = DriveViewMode.List,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val notice: String? = null,

    // -- browsing ---------------------------------------------------------
    val items: List<DriveItem> = emptyList(),
    val total: Int = 0,
    val folderId: String? = null,
    val breadcrumb: List<DriveCrumb> = emptyList(),
    val search: String = "",
    val sort: DriveSort = DriveSort.DateDesc,
    val grouping: DriveGrouping = DriveGrouping.None,
    val quickFilter: DriveQuickFilter = DriveQuickFilter.All,
    val tags: List<DriveTag> = emptyList(),
    val tagFilterId: String? = null,
    val selected: Set<String> = emptySet(),
    /**
     * Ids the user has starred.
     *
     * Held separately from the items rather than read off each row: the star is
     * per-user and the listing is shared, so a row's own `is_favorite` is only
     * populated on some routes. One authoritative set, fetched from the
     * lightweight `/favorites/ids`, is what keeps the star consistent between
     * the browser, the grid and the favourites page.
     */
    val favouriteIds: Set<String> = emptySet(),

    // -- other pages ------------------------------------------------------
    val trashItems: List<DriveItem> = emptyList(),
    val favourites: List<DriveItem> = emptyList(),
    val activity: List<DriveActivity> = emptyList(),
    val storage: StorageUsage? = null,

    val uploads: List<QueuedUpload> = emptyList(),
    val details: DetailsState = DetailsState(),
    val prompt: DrivePrompt? = null,
) {

    /** The pages this viewer may open, in tab order. */
    val destinations: List<DriveDestination>
        get() = DriveDestination.entries.filter { it.visibleTo(viewer) }

    /** The listing, folders first, bucketed under the chosen grouping. */
    val groups: List<DriveGroup>
        get() = items.sortedByDescending { it.isFolder }.groupedBy(grouping)

    val selectedItems: List<DriveItem> get() = items.filter { it.id in selected }

    val hasMore: Boolean get() = items.size < total

    /**
     * How many of the selection a bulk delete would actually remove.
     *
     * The server permission-checks each item, so a mixed selection partially
     * succeeds. Knowing the number up front is what lets the toolbar say
     * "Delete 17 of 20" instead of reporting the shortfall afterwards.
     */
    val deletableCount: Int get() = viewer.eligible(DriveAction.Delete, selectedItems).size

    val movableCount: Int get() = viewer.eligible(DriveAction.Edit, selectedItems).size

    val downloadableCount: Int get() = viewer.eligible(DriveAction.Download, selectedItems).size

    /** Uploads still running, for the progress strip. */
    val activeUploads: List<QueuedUpload> get() = uploads.filterNot { it.isSettled }

    fun isFavourite(item: DriveItem): Boolean = item.id in favouriteIds || item.isFavourite

    fun refOf(item: DriveItem): DriveRef = DriveRef(item.id, item.kind)

    /** Where a "you are here" line reads from, root included. */
    val locationLabel: String
        get() = if (breadcrumb.isEmpty()) "Drive" else breadcrumb.joinToString(" / ") { it.name }
}
