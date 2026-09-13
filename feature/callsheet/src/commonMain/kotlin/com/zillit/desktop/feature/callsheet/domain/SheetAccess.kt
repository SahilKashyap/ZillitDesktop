package com.zillit.desktop.feature.callsheet.domain

/**
 * The Permission tab's model: one crew member and their call-sheet tool cell,
 * read from the permission grid's crew axis (`permissions/crewlist/tools/access`)
 * and written through the call-sheet service's own switch
 * (`POST /callsheet-tool-access`), which grants view, posting and download
 * together.
 */
data class AccessPerson(
    val userId: String,
    val fullName: String,
    /** Label keys as the grid sends them; translated on render. */
    val designation: String = "",
    val department: String = "",
    /** A project admin's row cannot be changed here. */
    val isAdmin: Boolean = false,
    /** The call-sheet unit this person's cell belongs to (a production may have several). */
    val unitName: String = "",
    val unitId: String = "",
    val canView: Boolean = false,
    val canPost: Boolean = false,
    val canDownload: Boolean = false,
)

/** One page of the grid, as the Permission tab lists it. */
data class AccessPage(
    val people: List<AccessPerson>,
    /** Every person on the axis, for the pager. */
    val total: Int,
)

/** What `POST /callsheet-tool-access` answered — each flag falls back to the requested state. */
data class ToolAccessGrant(
    val canView: Boolean,
    val canPost: Boolean,
    val canDownload: Boolean,
    /** The server's message key, translated on render. */
    val message: String = "",
)
