package com.zillit.desktop.feature.permissiongrid.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.permissiongrid.domain.AccessKind
import com.zillit.desktop.feature.permissiongrid.domain.GridAxis
import com.zillit.desktop.feature.permissiongrid.domain.GridPage
import com.zillit.desktop.feature.permissiongrid.domain.GridRow
import com.zillit.desktop.feature.permissiongrid.domain.GridSection
import com.zillit.desktop.feature.permissiongrid.domain.PermissionGridRepository
import com.zillit.desktop.feature.permissiongrid.domain.RightsSync
import com.zillit.desktop.feature.permissiongrid.domain.PermissionGridViewer

data class PermissionGridUiState(
    val viewer: PermissionGridViewer = PermissionGridViewer(ready = false),
    val axis: GridAxis = GridAxis.Crew,
    val section: GridSection = GridSection.Tools,
    /** One-based, as the footer shows it; the repository subtracts. */
    val page: Int = 1,
    val pageSize: Int = PAGE_SIZE,
    val query: String = "",
    val grid: GridPage = GridPage.Empty,
    val isBusy: Boolean = false,
    val error: String? = null,
    /** A refusal from the server, shown until dismissed. */
    val notice: String? = null,
) {
    /**
     * The rows this page shows.
     *
     * Search is client-side over the page in hand, which is what the web does:
     * the endpoint has no query parameter, and filtering server-side would
     * mean a round trip per keystroke against an endpoint that pages.
     */
    val rows: List<GridRow>
        get() {
            val needle = query.trim()
            if (needle.isEmpty()) return grid.rows
            return grid.rows.filter { row ->
                val s = row.subject
                s.name.contains(needle, true) ||
                    s.department?.contains(needle, true) == true ||
                    s.designation?.contains(needle, true) == true
            }
        }

    val columns: List<String> get() = grid.columns

    val lastPage: Int get() = ((grid.total + pageSize - 1) / pageSize).coerceAtLeast(1)

    val canGoBack: Boolean get() = page > 1
    val canGoForward: Boolean get() = page < lastPage

    /** Only an admin with posting rights may change a cell. */
    val canEdit: Boolean get() = viewer.canPost
}

private const val PAGE_SIZE = 20

sealed interface PermissionGridEvent {
    data class Start(val viewer: PermissionGridViewer) : PermissionGridEvent
    data object Reload : PermissionGridEvent
    data class SelectAxis(val axis: GridAxis) : PermissionGridEvent
    data class SelectSection(val section: GridSection) : PermissionGridEvent
    data class Search(val query: String) : PermissionGridEvent
    data class GoToPage(val page: Int) : PermissionGridEvent
    data class Toggle(
        val subjectId: String,
        val unitName: String,
        val kind: AccessKind,
        val enable: Boolean,
    ) : PermissionGridEvent
    data object DismissNotice : PermissionGridEvent
}

sealed interface PermissionGridEffect

/**
 * The production's viewing & posting rights, as a spreadsheet.
 *
 * Three axes (people, departments, designations) over two sections (the home
 * dashboard and the film-tools grid), each cell three independent rights. The
 * web is the reference; the phones only ever offer the people axis, which is
 * why their own dialog warns that departments and designations must be done
 * on the web.
 */
class PermissionGridViewModel(
    private val repository: PermissionGridRepository,
) : ZillitViewModel<PermissionGridUiState, PermissionGridEvent, PermissionGridEffect>(
    PermissionGridUiState(),
) {

    override fun onEvent(event: PermissionGridEvent) {
        when (event) {
            is PermissionGridEvent.Start -> {
                val first = currentState.grid.rows.isEmpty()
                setState { copy(viewer = event.viewer) }
                if (event.viewer.canView && first) load()
                listenOnce()
            }

            PermissionGridEvent.Reload -> load()

            is PermissionGridEvent.SelectAxis -> {
                if (event.axis == currentState.axis) return
                // Page one: row 40 of the people axis is not row 40 of the
                // department axis, and keeping the number lands on an empty page.
                setState { copy(axis = event.axis, page = 1, grid = GridPage.Empty) }
                load()
            }

            is PermissionGridEvent.SelectSection -> {
                if (event.section == currentState.section) return
                setState { copy(section = event.section, page = 1, grid = GridPage.Empty) }
                load()
            }

            is PermissionGridEvent.Search -> setState { copy(query = event.query) }

            is PermissionGridEvent.GoToPage -> {
                val target = event.page.coerceIn(1, currentState.lastPage)
                if (target == currentState.page) return
                setState { copy(page = target) }
                load()
            }

            is PermissionGridEvent.Toggle -> toggle(event)

            PermissionGridEvent.DismissNotice -> setState { copy(notice = null) }
        }
    }

    /** Called when the production changes; the next Start reloads. */
    fun onProjectChanged() {
        setState { PermissionGridUiState() }
    }

    /**
     * Folds the socket's rights-sync events into whatever page is on screen —
     * the web's `handleAccessGridSync` (ZL-17812): an edit made on another
     * client, or the backend's own cascade, lands without a reload. Guarded
     * so a second Start (the window reopening) does not stack collectors.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.syncs.collect { sync ->
                setState { copy(grid = grid.syncedWith(sync)) }
            }
        }
    }

    private var listening = false

    private fun load() {
        val state = currentState
        setState { copy(isBusy = true, error = null) }
        launchResult(
            block = { repository.load(state.axis, state.section, state.page - 1, state.pageSize) },
            onSuccess = { page -> setState { copy(isBusy = false, grid = page) } },
            onError = { error -> setState { copy(isBusy = false, error = error.localised()) } },
        )
    }

    /**
     * One cell, written and then believed.
     *
     * The box is not moved until the server agrees. An optimistic flip would
     * be wrong more often here than elsewhere: half the refusals on this
     * endpoint are rights the backend cascades itself and will not let a
     * client set, and a box that ticks and then untocks reads as a broken
     * screen rather than a refused change.
     */
    private fun toggle(event: PermissionGridEvent.Toggle) {
        val state = currentState
        if (!state.canEdit) return
        val cell = state.grid.rows
            .firstOrNull { it.subject.id == event.subjectId }
            ?.cells?.get(event.unitName)
            ?: return
        if (cell.locked(event.kind) || cell.busy) return

        mark(event.subjectId, event.unitName) { it.copy(busy = true) }
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
                mark(event.subjectId, event.unitName) {
                    it.granting(event.kind, event.enable).copy(busy = false)
                }
                // ZL-16376 — a deal memo you may see is one you may take away
                // with you; the web enables download alongside view, and the
                // backend does not do it for us.
                if (event.unitName == DEAL_MEMO && event.kind == AccessKind.View && event.enable) {
                    onEvent(event.copy(kind = AccessKind.Download))
                }
            },
            onError = { error ->
                mark(event.subjectId, event.unitName) { it.copy(busy = false) }
                setState { copy(notice = error.localised()) }
            },
        )
    }

    private fun mark(
        subjectId: String,
        unitName: String,
        change: (com.zillit.desktop.feature.permissiongrid.domain.GridCell) ->
        com.zillit.desktop.feature.permissiongrid.domain.GridCell,
    ) {
        setState {
            copy(
                grid = grid.copy(
                    rows = grid.rows.map { row ->
                        if (row.subject.id != subjectId) {
                            row
                        } else {
                            val cell = row.cells[unitName] ?: return@map row
                            row.copy(cells = row.cells + (unitName to change(cell)))
                        }
                    },
                ),
            )
        }
    }

    private companion object {
        const val DEAL_MEMO = "deal_memo_label"
    }
}
