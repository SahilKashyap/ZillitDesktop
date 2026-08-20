package com.zillit.desktop.feature.permissiongrid.data

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.permissiongrid.domain.GridAxis
import com.zillit.desktop.feature.permissiongrid.domain.GridCell
import com.zillit.desktop.feature.permissiongrid.domain.GridPage
import com.zillit.desktop.feature.permissiongrid.domain.GridRow
import com.zillit.desktop.feature.permissiongrid.domain.GridSubject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET permissions/{axis}/{section}/access` — the whole spreadsheet, a page at
 * a time.
 *
 * `total_users` is the row count for every axis, not only the crew one; the
 * name is the backend's.
 */
@Serializable
internal data class GridPageDto(
    @SerialName("headers") val headers: List<String>? = null,
    @SerialName("rows") val rows: List<List<GridEntryDto>>? = null,
    @SerialName("total_users") val totalUsers: Int? = null,
)

/**
 * One entry in a row.
 *
 * A row is a heterogeneous array: the first entry describes the **subject**
 * (who the rights belong to) and every entry after it is one tool's **cell**.
 * They arrive as the same JSON shape with different halves filled in, so one
 * tolerant DTO reads both rather than a polymorphic decoder that would fail
 * the whole page over one unfamiliar row.
 *
 * Every access flag defaults to false: a right the server did not mention is
 * one the subject does not have. The `*Updatable` flags are the opposite —
 * only an explicit `false` locks a right, or a server predating them would
 * render the entire grid read-only. Note they are **camelCase** here while
 * their siblings are snake_case; that is the wire, not a transcription slip.
 */
@Serializable
internal data class GridEntryDto(
    // -- subject half
    @SerialName("user_id") val userId: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("designation_id") val designationId: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("designation_name") val designationName: String? = null,
    // -- cell half
    @SerialName("unit_id") val unitId: String? = null,
    @SerialName("unit_name") val unitName: String? = null,
    @SerialName("view_access") val viewAccess: Boolean? = null,
    @SerialName("posting_access") val postingAccess: Boolean? = null,
    @SerialName("download_access") val downloadAccess: Boolean? = null,
    @SerialName("viewingUpdatable") val viewingUpdatable: Boolean? = null,
    @SerialName("postingUpdatable") val postingUpdatable: Boolean? = null,
    @SerialName("downloadUpdatable") val downloadUpdatable: Boolean? = null,
) {
    /**
     * The subject this entry describes, or null when it is a tool cell.
     *
     * The id is the one belonging to [axis], never "whichever came first".
     * A designation row carries its **department's** id alongside its own —
     * so reading the first non-null collapses every designation in a
     * department onto one id: the rows key each other out of the list, and
     * every write lands on the department instead of the designation.
     */
    fun toSubject(axis: GridAxis): GridSubject? {
        val id = when (axis) {
            GridAxis.Crew -> userId
            GridAxis.Departments -> departmentId
            GridAxis.Designations -> designationId
        }?.takeIf { it.isNotBlank() } ?: return null
        // Named by the axis for the same reason: a designation row carries a
        // department name too, and titling the row with it would list the same
        // department a dozen times over.
        val name = when (axis) {
            GridAxis.Crew -> fullName
            GridAxis.Departments -> departmentName
            GridAxis.Designations -> designationName
        }
        return GridSubject(
            id = id,
            name = name?.takeIf { it.isNotBlank() }?.localised() ?: id,
            // The web shows these as frozen columns beside the name, and only
            // on the crew axis — on the others they are the name.
            department = departmentName
                ?.takeIf { it.isNotBlank() && axis == GridAxis.Crew }
                ?.localised(),
            designation = designationName
                ?.takeIf { it.isNotBlank() && axis == GridAxis.Crew }
                ?.localised(),
        )
    }

    /** The cell this entry describes, or null when it is the subject. */
    fun toCell(): GridCell? {
        val unit = unitId?.takeIf { it.isNotBlank() } ?: return null
        val name = unitName?.takeIf { it.isNotBlank() } ?: return null
        return GridCell(
            unitId = unit,
            unitName = name,
            canView = viewAccess == true,
            canPost = postingAccess == true,
            canDownload = downloadAccess == true,
            viewLocked = viewingUpdatable == false,
            postLocked = postingUpdatable == false,
            downloadLocked = downloadUpdatable == false,
        )
    }
}

/**
 * The page as the grid wants it.
 *
 * [mine] is dropped: every client hides the signed-in admin's own row, because
 * revoking your own view rights from this screen is a door that locks behind
 * you.
 *
 * Columns come from the rows rather than from `headers`, which also carries
 * the frozen subject columns (`department_label`, `designation_label`) that
 * are not tools at all. The tool columns are exactly the `unit_name`s the rows
 * carry, ordered by `headers` where it names them so the production's own
 * arrangement survives, then alphabetically by their translated title — the
 * order the web sorts its headers into.
 */
internal fun GridPageDto.toPage(axis: GridAxis, mine: String?): GridPage {
    val rows = rows.orEmpty().mapNotNull { entries ->
        val subject = entries.firstNotNullOfOrNull { it.toSubject(axis) } ?: return@mapNotNull null
        if (mine != null && subject.id == mine) return@mapNotNull null
        GridRow(
            subject = subject,
            cells = entries.mapNotNull { it.toCell() }.associateBy { it.unitName },
        )
    }
        // Belt and braces after ZL-live: the list is keyed by subject id, and a
        // server that repeats one would take the window down rather than draw
        // a duplicate row.
        .distinctBy { it.subject.id }

    val present = rows.flatMap { it.cells.keys }.toSet()
    val named = headers.orEmpty().filter { it in present }
    val rest = (present - named.toSet()).sortedBy { it.localised().lowercase() }

    return GridPage(
        columns = named + rest,
        rows = rows,
        // The header count is the production's, not this page's — the rows we
        // dropped are still rows the server is paging through.
        total = totalUsers ?: rows.size,
    )
}

/** `{unit_id, enable, access_type, <entityKey>}` — one cell, one call. */
@Serializable
internal data class SetAccessDto(
    @SerialName("unit_id") val unitId: String,
    @SerialName("enable") val enable: Boolean,
    @SerialName("access_type") val accessType: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("designation_id") val designationId: String? = null,
)
