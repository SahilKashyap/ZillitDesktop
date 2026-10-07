package com.zillit.desktop.feature.permissiongrid.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.permissions.rightsRefusalMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.permissiongrid.domain.AccessKind
import com.zillit.desktop.feature.permissiongrid.domain.DefaultGridPage
import com.zillit.desktop.feature.permissiongrid.domain.DefaultGridRow
import com.zillit.desktop.feature.permissiongrid.domain.DesignationFilter
import com.zillit.desktop.feature.permissiongrid.domain.GridAxis
import com.zillit.desktop.feature.permissiongrid.domain.GridPage
import com.zillit.desktop.feature.permissiongrid.domain.GridQuery
import com.zillit.desktop.feature.permissiongrid.domain.GridRow
import com.zillit.desktop.feature.permissiongrid.domain.GridSection
import com.zillit.desktop.feature.permissiongrid.domain.PermissionGridRepository
import com.zillit.desktop.feature.permissiongrid.domain.PermissionGridViewer
import com.zillit.desktop.feature.permissiongrid.domain.rightsWorkbook
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/** A floating message — the web's `message.success` / `message.error`. */
data class GridToast(val text: String, val success: Boolean)

data class PermissionGridUiState(
    val viewer: PermissionGridViewer = PermissionGridViewer(ready = false),
    /** The web opens on Based on Crew List, Home. */
    val axis: GridAxis = GridAxis.CrewList,
    val section: GridSection = GridSection.Home,
    val designations: DesignationFilter = DesignationFilter.Used,
    /** One-based, as the pager shows it; the repository subtracts. */
    val page: Int = 1,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    /** What is in the search box, keystroke by keystroke. */
    val query: String = "",
    /** The settled query the page on screen was searched for. */
    val search: String = "",
    val grid: GridPage = GridPage.Empty,
    val isBusy: Boolean = false,
    val error: String? = null,
    val toast: GridToast? = null,
    val defaults: DefaultGridState = DefaultGridState(),
) {
    /** The server searched, filtered and sorted this page; it is what shows. */
    val rows: List<GridRow> get() = grid.rows

    val columns: List<String> get() = grid.columns

    val lastPage: Int get() = ((grid.total + pageSize - 1) / pageSize).coerceAtLeast(1)

    /** Only someone with posting rights on the grid tool may change a cell. */
    val canEdit: Boolean get() = viewer.canPost

    /** The listing-order banner: crew-list axis, admins only (ZL-16967). */
    val showsListingOrderBanner: Boolean get() = axis == GridAxis.CrewList && viewer.isAdmin
}

/**
 * The read-only Default Grid. Read whole and paged here, as the web does
 * (`Defaultgrid.jsx`, ten to a page, searched on the client).
 */
data class DefaultGridState(
    val section: GridSection = GridSection.Home,
    val axis: GridAxis = GridAxis.Departments,
    val query: String = "",
    val page: Int = 1,
    val pageSize: Int = DEFAULT_GRID_PAGE_SIZE,
    val grid: DefaultGridPage = DefaultGridPage.Empty,
    val isBusy: Boolean = false,
    val error: String? = null,
    /** Which `(axis, section)` [grid] holds, so re-opening does not re-read. */
    val loadedFor: Pair<GridAxis, GridSection>? = null,
) {
    val matching: List<DefaultGridRow>
        get() {
            val needle = query.trim()
            if (needle.isEmpty()) return grid.rows
            return grid.rows.filter { it.name.contains(needle, ignoreCase = true) }
        }

    val lastPage: Int get() = ((matching.size + pageSize - 1) / pageSize).coerceAtLeast(1)

    val visible: List<DefaultGridRow>
        get() = matching.drop((page.coerceAtMost(lastPage) - 1) * pageSize).take(pageSize)

    companion object {
        /** The two axes the Default Grid offers — defaults are not per person. */
        val axes = listOf(GridAxis.Departments, GridAxis.Designations)
    }
}

const val DEFAULT_PAGE_SIZE = 20
const val DEFAULT_GRID_PAGE_SIZE = 10

/** antd's size-changer steps. */
val PAGE_SIZES = listOf(10, 20, 50, 100)

sealed interface PermissionGridEvent {
    data class Start(val viewer: PermissionGridViewer) : PermissionGridEvent
    data object Reload : PermissionGridEvent

    data class SelectAxis(val axis: GridAxis) : PermissionGridEvent
    data class SelectSection(val section: GridSection) : PermissionGridEvent
    data class SelectDesignations(val filter: DesignationFilter) : PermissionGridEvent
    data class Search(val query: String) : PermissionGridEvent
    data object ClearSearch : PermissionGridEvent
    data class GoToPage(val page: Int) : PermissionGridEvent
    data class SetPageSize(val size: Int) : PermissionGridEvent
    data class Toggle(
        val subjectId: String,
        val unitName: String,
        val kind: AccessKind,
        val enable: Boolean,
    ) : PermissionGridEvent
    data object DismissToast : PermissionGridEvent

    /** The Default Grid's own controls. */
    sealed interface Defaults : PermissionGridEvent {
        data object Open : Defaults
        data class SelectAxis(val axis: GridAxis) : Defaults
        data class SelectSection(val section: GridSection) : Defaults
        data class Search(val query: String) : Defaults
        data class GoToPage(val page: Int) : Defaults
        data class SetPageSize(val size: Int) : Defaults
        data object DownloadExcel : Defaults
    }
}

sealed interface PermissionGridEffect {
    /** A finished workbook for the host to put on disk. */
    data class SaveFile(val fileName: String, val bytes: ByteArray) : PermissionGridEffect {
        override fun equals(other: Any?): Boolean =
            other is SaveFile && other.fileName == fileName && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = 31 * fileName.hashCode() + bytes.contentHashCode()
    }
}

/**
 * The production's viewing & posting rights — the web's `AccessGrid.jsx`,
 * opened from Film Tools and from Admin Settings alike.
 *
 * Four axes (two of people, departments, designations) over two sections
 * (the home dashboard and the film-tools grid), each cell three independent
 * rights. Searching, sorting and the hired-designations filter are the
 * server's; this keeps the page, paints a toggle at once and puts it back if
 * the server refuses.
 */
@Suppress("TooManyFunctions") // One screen and its sub-page; each function is one web handler.
class PermissionGridViewModel(
    private val repository: PermissionGridRepository,
    /** Carries a refused press to the app frame, which offers to ask an admin. */
    private val rights: RightsRequestBus? = null,
) : ZillitViewModel<PermissionGridUiState, PermissionGridEvent, PermissionGridEffect>(
    PermissionGridUiState(),
) {

    private var listening = false
    private var searchJob: Job? = null
    private var reorderRefetch: Job? = null

    /** Bumped per read; an answer for an older one is dropped. */
    private var latestLoad = 0

    override fun onEvent(event: PermissionGridEvent) {
        when (event) {
            is PermissionGridEvent.Start -> {
                val first = currentState.grid.rows.isEmpty() && !currentState.isBusy
                setState { copy(viewer = event.viewer) }
                if (event.viewer.canView && first) load()
                listenOnce()
            }

            PermissionGridEvent.Reload -> load()
            is PermissionGridEvent.Toggle -> toggle(event)
            PermissionGridEvent.DismissToast -> setState { copy(toast = null) }
            is PermissionGridEvent.Defaults -> defaults(event)
            else -> narrow(event)
        }
    }

    /** The events that only change what is being looked at — each re-reads. */
    private fun narrow(event: PermissionGridEvent) {
        when (event) {
            is PermissionGridEvent.SelectAxis -> {
                if (event.axis == currentState.axis) return
                // The web clears the search with the type: a name typed for
                // people means nothing among departments.
                searchJob?.cancel()
                setState { copy(axis = event.axis, page = 1, query = "", search = "", grid = GridPage.Empty) }
                load()
            }

            is PermissionGridEvent.SelectSection -> {
                if (event.section == currentState.section) return
                setState { copy(section = event.section, page = 1, grid = GridPage.Empty) }
                load()
            }

            is PermissionGridEvent.SelectDesignations -> {
                if (event.filter == currentState.designations) return
                searchJob?.cancel()
                setState { copy(designations = event.filter, page = 1, query = "", search = "") }
                load()
            }

            is PermissionGridEvent.Search -> search(event.query)

            PermissionGridEvent.ClearSearch -> {
                searchJob?.cancel()
                setState { copy(query = "", search = "", page = 1) }
                load()
            }

            is PermissionGridEvent.GoToPage -> goToPage(event.page)
            is PermissionGridEvent.SetPageSize -> setPageSize(event.size)
            else -> Unit
        }
    }

    private fun goToPage(page: Int) {
        val target = page.coerceIn(1, currentState.lastPage)
        if (target == currentState.page) return
        setState { copy(page = target) }
        load()
    }

    private fun setPageSize(size: Int) {
        if (size == currentState.pageSize) return
        // antd keeps the reader's place where it still exists.
        val first = (currentState.page - 1) * currentState.pageSize
        setState { copy(pageSize = size, page = first / size + 1) }
        load()
    }

    /**
     * Debounces the text, not the filtering: 300 ms after the last keystroke
     * the trimmed, lower-cased query goes to the server and the page resets
     * to the first — one request per settled query, as on the web.
     */
    private fun search(text: String) {
        setState { copy(query = text) }
        searchJob?.cancel()
        searchJob = launch {
            delay(SEARCH_DEBOUNCE_MS)
            val settled = text.trim().lowercase()
            if (settled == currentState.search) return@launch
            setState { copy(search = settled, page = 1) }
            load()
        }
    }

    /** Called when the production changes; the next Start reloads. */
    fun onProjectChanged() {
        searchJob?.cancel()
        reorderRefetch?.cancel()
        latestLoad++
        setState { PermissionGridUiState() }
    }

    /**
     * Swaps in the real rights once the tool grid has answered.
     *
     * `projectId` flips the moment a production is chosen, but the rights that
     * gate this screen arrive with the Home load a beat later, so the viewer
     * resolved at open is the "not yet known" one. Handed in rather than
     * resolved here: whoever shows the grid owns the permission set.
     */
    fun onRightsChanged(resolved: PermissionGridViewer) {
        val wasBlind = !currentState.viewer.canView
        setState { copy(viewer = resolved) }
        val nothingYet = currentState.grid.rows.isEmpty() && !currentState.isBusy
        if (wasBlind && resolved.canView && nothingYet) load()
    }

    /**
     * Folds the socket's rights-sync events into whatever page is on screen —
     * the web's `handleAccessGridSync` (ZL-17812) — and re-reads the crew-list
     * axis when departments are re-ordered elsewhere, twice, because the
     * backend's order settles a moment after it announces it.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.syncs.collect { sync ->
                setState { copy(grid = grid.syncedWith(sync)) }
            }
        }
        launch {
            repository.departmentsReordered.collect {
                if (currentState.axis != GridAxis.CrewList || !currentState.viewer.canView) return@collect
                load()
                reorderRefetch?.cancel()
                reorderRefetch = launch {
                    delay(REORDER_SETTLE_MS)
                    load()
                }
            }
        }
    }

    private fun load() {
        val state = currentState
        val token = ++latestLoad
        setState { copy(isBusy = true, error = null) }
        launchResult(
            block = {
                repository.load(
                    GridQuery(
                        axis = state.axis,
                        section = state.section,
                        page = state.page - 1,
                        limit = state.pageSize,
                        search = state.search,
                        designations = state.designations,
                    ),
                )
            },
            onSuccess = { page ->
                if (token == latestLoad) setState { copy(isBusy = false, grid = page) }
            },
            onError = { error ->
                if (token == latestLoad) setState { copy(isBusy = false, error = error.localised()) }
            },
        )
    }

    /**
     * One box, painted at once and then confirmed — the web's `onClickHandler`.
     *
     * The tick moves on the click; the write is still the authority. A refusal
     * (HTTP 200 with `status: 0`, most often a right the backend cascades
     * itself) puts the box back where the reader found it and says why. Only
     * the box being written is held while the request is out. No cascade is
     * issued: the backend applies post→view, view→post+download and
     * download→view itself and announces the results over the sync events.
     */
    private fun toggle(event: PermissionGridEvent.Toggle) {
        val state = currentState
        if (!state.canEdit) return
        val row = state.grid.rows.firstOrNull { it.subject.id == event.subjectId } ?: return
        if (!row.editable(event.unitName, event.kind)) return
        val cell = row.cells[event.unitName] ?: return

        patch(event) { it.granting(event.kind, event.enable).holding(event.kind, busy = true) }
        launchResult(
            block = {
                repository.setAccess(
                    axis = state.axis,
                    section = state.section,
                    entityId = event.subjectId,
                    unitId = cell.unitId,
                    kind = event.kind,
                    enable = event.enable,
                )
            },
            onSuccess = {
                patch(event) { it.holding(event.kind, busy = false) }
                // ZL-16376 — a deal memo you may see is one you may take away:
                // the web ticks Download beside it, on screen only.
                if (event.unitName == GridRow.DEAL_MEMO && event.kind == AccessKind.View && event.enable) {
                    patch(event) { it.granting(AccessKind.Download, true) }
                }
                setState { copy(toast = GridToast(updatedMessage(event.kind), success = true)) }
            },
            onError = { error ->
                patch(event) { it.granting(event.kind, !event.enable).holding(event.kind, busy = false) }
                setState { copy(toast = GridToast(error.localised(), success = false)) }
            },
        )
    }

    private fun patch(
        event: PermissionGridEvent.Toggle,
        change: (com.zillit.desktop.feature.permissiongrid.domain.GridCell) ->
        com.zillit.desktop.feature.permissiongrid.domain.GridCell,
    ) {
        setState { copy(grid = grid.changing(event.subjectId, event.unitName, change)) }
    }

    private fun updatedMessage(kind: AccessKind): String = when (kind) {
        AccessKind.View -> str(S.desktop_viewing_rights_updated)
        AccessKind.Post -> str(S.desktop_posting_rights_updated)
        AccessKind.Download -> str(S.desktop_pg_download_rights_updated)
    }

    // -- the Default Grid ---------------------------------------------------

    private fun defaults(event: PermissionGridEvent.Defaults) {
        val d = currentState.defaults
        when (event) {
            PermissionGridEvent.Defaults.Open -> if (d.loadedFor != (d.axis to d.section)) loadDefaults()

            is PermissionGridEvent.Defaults.SelectAxis -> {
                if (event.axis == d.axis) return
                updateDefaults { copy(axis = event.axis, query = "", page = 1) }
                loadDefaults()
            }

            is PermissionGridEvent.Defaults.SelectSection -> {
                if (event.section == d.section) return
                updateDefaults { copy(section = event.section, page = 1) }
                loadDefaults()
            }

            is PermissionGridEvent.Defaults.Search -> updateDefaults { copy(query = event.query, page = 1) }

            is PermissionGridEvent.Defaults.GoToPage ->
                updateDefaults { copy(page = event.page.coerceIn(1, lastPage)) }

            is PermissionGridEvent.Defaults.SetPageSize -> updateDefaults { copy(pageSize = event.size, page = 1) }

            PermissionGridEvent.Defaults.DownloadExcel -> downloadExcel()
        }
    }

    private fun updateDefaults(change: DefaultGridState.() -> DefaultGridState) {
        setState { copy(defaults = defaults.change()) }
    }

    private fun loadDefaults() {
        val wanted = currentState.defaults.axis to currentState.defaults.section
        updateDefaults { copy(isBusy = true, error = null) }
        launchResult(
            block = { repository.loadDefaults(wanted.first, wanted.second) },
            onSuccess = { page ->
                val d = currentState.defaults
                if ((d.axis to d.section) == wanted) {
                    updateDefaults { copy(isBusy = false, grid = page, loadedFor = wanted) }
                }
            },
            onError = { error -> updateDefaults { copy(isBusy = false, error = error.localised()) } },
        )
    }

    /**
     * The web's `downloadExcel`: every matching row (not just this page), a
     * `Department` column, then `<tool> - <right>` per tool in the order the
     * rows carry them, ticked `✔` or `x`. Gated on download rights on the grid
     * tool, refused into a request to an admin (ZL-15045).
     */
    private fun downloadExcel() {
        if (!currentState.viewer.canDownload) {
            rights?.ask(MODULE_LABEL, RightsKind.Download)
            val text = rightsRefusalMessage(str(S.desktop_pg_title), RightsKind.Download, rights != null)
            setState { copy(toast = GridToast(text, success = false)) }
            return
        }
        val rows = currentState.defaults.matching
        if (rows.isEmpty()) {
            setState { copy(toast = GridToast(str(S.desktop_pg_nothing_to_download), success = false)) }
            return
        }
        val units = rows.flatMap { row -> row.cells.map { it.unitName } }.distinct()
        val kinds = listOf(AccessKind.View, AccessKind.Post, AccessKind.Download)
        val header = listOf(str(S.department)) + units.flatMap { unit ->
            kinds.map { kind -> "${unit.localised()} - ${kind.label}" }
        }
        val body = rows.map { row ->
            listOf(row.name) + units.flatMap { unit ->
                val cell = row.byUnit[unit]
                kinds.map { kind -> if (cell?.granted(kind) == true) TICK else CROSS }
            }
        }
        val bytes = rightsWorkbook(str(S.desktop_permissions), header, body)
        sendEffect(PermissionGridEffect.SaveFile("${str(S.desktop_pg_department_permission_file)}.xlsx", bytes))
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val REORDER_SETTLE_MS = 1200L
        const val TICK = "✔"
        const val CROSS = "x"
    }
}

private const val MODULE_LABEL = "Viewing & Posting Rights Grid"
