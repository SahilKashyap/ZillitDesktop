package com.zillit.desktop.feature.permissiongrid.data

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.permissiongrid.domain.DefaultGridPage
import com.zillit.desktop.feature.permissiongrid.domain.DefaultGridRow
import com.zillit.desktop.feature.permissiongrid.domain.GridAxis
import com.zillit.desktop.feature.permissiongrid.domain.GridCell
import com.zillit.desktop.feature.permissiongrid.domain.GridPage
import com.zillit.desktop.feature.permissiongrid.domain.GridRow
import com.zillit.desktop.feature.permissiongrid.domain.GridSubject
import com.zillit.desktop.feature.permissiongrid.domain.SubjectColumn
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
 * one the subject does not have. The `*Updatable` lock flags are
 * **camelCase** here while their siblings are snake_case; that is the wire,
 * not a transcription slip.
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
    @SerialName("admin_access") val adminAccess: Boolean? = null,
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
        if (unitId != null && unitName != null) return null
        val id = when (axis) {
            GridAxis.Users, GridAxis.CrewList -> userId
            GridAxis.Departments -> departmentId
            GridAxis.Designations -> designationId
        }?.takeIf { it.isNotBlank() } ?: return null
        val department = departmentName?.takeIf { it.isNotBlank() }?.localised()
        val designation = designationName?.takeIf { it.isNotBlank() }?.localised()
        // Named by the axis for the same reason: a designation row carries a
        // department name too, and titling the row with it would list the same
        // department a dozen times over.
        val name = when (axis) {
            GridAxis.Users, GridAxis.CrewList -> fullName?.takeIf { it.isNotBlank() }
            GridAxis.Departments -> department
            GridAxis.Designations -> designation
        }
        return GridSubject(
            id = id,
            name = name ?: id,
            department = department,
            designation = designation,
            isAdmin = adminAccess == true,
        )
    }

    /**
     * The cell this entry describes, or null when it is the subject.
     *
     * Posting is open only on an explicit `postingUpdatable: true`, View and
     * Download shut only on an explicit `false` — the web's own asymmetry
     * (`AccessGrid.jsx` `RightsCell`), kept so a box is pressable here exactly
     * when it is there.
     */
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
            postLocked = postingUpdatable != true,
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
 * `headers` names the frozen subject columns (`user_label`,
 * `department_label`, `designation_label`) and the tools after them. The tool
 * columns are sorted by their translated title, as the web sorts them; the
 * subject columns keep the server's order and stay on the left.
 *
 * Rows keep the server's order on the crew-list axis — that order *is* the
 * crew list — and are otherwise sorted by name, department, then designation,
 * the web's `sortFunction`.
 */
internal fun GridPageDto.toPage(axis: GridAxis, mine: String?): GridPage {
    val parsed = rows.orEmpty().mapNotNull { entries ->
        val subject = entries.firstNotNullOfOrNull { it.toSubject(axis) } ?: return@mapNotNull null
        if (mine != null && axis.isPeople && subject.id == mine) return@mapNotNull null
        GridRow(
            subject = subject,
            cells = entries.mapNotNull { it.toCell() }.associateBy { it.unitName },
        )
    }
        // Belt and braces: the list is keyed by subject id, and a server that
        // repeats one would take the window down rather than draw a duplicate.
        .distinctBy { it.subject.id }

    val ordered = if (axis == GridAxis.CrewList) {
        parsed
    } else {
        parsed.sortedWith(
            compareBy<GridRow>(
                { if (axis.isPeople) it.subject.name.lowercase() else "" },
                { it.subject.department?.lowercase().orEmpty() },
                { it.subject.designation?.lowercase().orEmpty() },
            ),
        )
    }

    val names = headers.orEmpty()
    val subjects = names.mapNotNull { SubjectColumn.of(it) }.ifEmpty { SubjectColumn.defaultFor(axis) }
    val tools = (names.filter { SubjectColumn.of(it) == null } + ordered.flatMap { it.cells.keys })
        .distinct()
        .sortedBy { it.localised().lowercase() }

    return GridPage(
        columns = tools,
        rows = ordered,
        // The header count is the production's, not this page's — the rows we
        // dropped are still rows the server is paging through.
        total = totalUsers ?: ordered.size,
        subjects = subjects,
    )
}

/**
 * The Default Grid's reading of the same payload (web `Defaultgrid.jsx`):
 * `headers[0]` titles the subject column, the rest are tools sorted by their
 * title, and each row is named by its department or designation.
 */
internal fun GridPageDto.toDefaultPage(axis: GridAxis): DefaultGridPage {
    val names = headers.orEmpty()
    val parsed = rows.orEmpty().mapIndexed { index, entries ->
        val first = entries.firstOrNull()
        val raw = if (axis == GridAxis.Designations) first?.designationName else first?.departmentName
        DefaultGridRow(
            key = (if (axis == GridAxis.Designations) first?.designationId else first?.departmentId)
                ?.takeIf { it.isNotBlank() }
                ?: index.toString(),
            name = raw?.takeIf { it.isNotBlank() }?.localised().orEmpty(),
            cells = entries.drop(1).mapNotNull { it.toCell() },
        )
    }.distinctBy { it.key }
    return DefaultGridPage(
        first = names.firstOrNull() ?: SubjectColumn.Department.header,
        columns = names.drop(1).sortedBy { it.localised() },
        rows = parsed,
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
