package com.zillit.desktop.feature.drive.ui

import com.zillit.desktop.feature.drive.domain.DriveViewer

/**
 * Every page the Drive can show.
 *
 * An enum rather than free-form route strings so the shell cannot navigate
 * somewhere that does not render, and so [visibleTo] is the single place that
 * decides who sees what.
 *
 * [slug] matches the web's own inner tabs so a deep link means the same thing
 * on both clients.
 */
enum class DriveDestination(val slug: String, val label: String) {

    /** The folder browser. The tool's home. */
    Browse("browse", "Files"),

    /** Starred files and folders, across every folder. */
    Favourites("favourites", "Starred"),

    /** Soft-deleted items, restorable until purged. */
    Trash("trash", "Trash"),

    /** The audit trail across the whole drive. */
    Activity("activity", "Activity"),

    /** What the production is using, and against what allowance. */
    Storage("storage", "Storage"),
    ;

    /**
     * Whether [viewer] may open this page.
     *
     * View rights gate the whole tool, so a viewer without them sees no pages
     * and the screen says so once rather than per tab.
     *
     * **Trash is not gated on posting rights.** A regular user sees only their
     * own deleted items and an admin sees everything — that filtering is the
     * server's (FR-07.2), and it is what makes the page safe to show to
     * everyone. Hiding it from non-posters instead would strand anyone who
     * deleted something by accident.
     */
    fun visibleTo(viewer: DriveViewer): Boolean = when {
        !viewer.canView && viewer.ready -> false
        // Project-wide usage figures are an administrator's view; a crew member
        // is told nothing useful by "the project has used 4.2 TB".
        this == Storage -> viewer.isAdmin || viewer.canPost
        else -> true
    }

    companion object {
        fun fromSlug(slug: String?): DriveDestination? =
            entries.firstOrNull { it.slug == slug }

        /** Always the browser when they can see it — that is what the tool is for. */
        fun landing(viewer: DriveViewer): DriveDestination =
            entries.firstOrNull { it.visibleTo(viewer) } ?: Browse
    }
}

/** List or grid. Persists across folder navigation (FR-20.4). */
enum class DriveViewMode(val slug: String, val label: String) {
    List("list", "List"),
    Grid("grid", "Grid"),
    ;

    fun toggled(): DriveViewMode = if (this == List) Grid else List
}
