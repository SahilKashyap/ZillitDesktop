package com.zillit.desktop.feature.permissiongrid.ui

import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.permissions.rightsRefusalMessage
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

    /**
     * "Ask an admin" on the read-only notice.
     *
     * The ask lives on the notice rather than on the cells: a grid whose boxes
     * tick and then untick reads as a broken screen, which is the same reason
     * [PermissionGridViewModel.toggle] refuses silently.
     */
    data object RequestPostingRights : PermissionGridEvent
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
    /** Carries a refused press to the app frame, which offers to ask an admin. */
    private val rights: RightsRequestBus? = null,
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
            PermissionGridEvent.RequestPostingRights -> askForPostingRights()

            is PermissionGridEvent.Toggle -> toggle(event)

            else -> narrow(event)
        }
    }

    /**
     * The four events that only change what is being looked at.
     *
     * Split out to keep [onEvent] under detekt's branch ceiling; they belong
     * together anyway — each one re-reads the grid under a new slice.
     */
    private fun narrow(event: PermissionGridEvent) {
        when (event) {
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

            PermissionGridEvent.DismissNotice -> setState { copy(notice = null) }

            else -> Unit
        }
    }

    /** Called when the production changes; the next Start reloads. */
    fun onProjectChanged() {
        setState { PermissionGridUiState() }
    }

    /**
     * Swaps in the real rights once the tool grid has answered.
     *
     * `projectId` flips the moment a production is chosen, but the rights that
     * gate this screen arrive with the Home load a beat later — so the viewer
     * resolved at open is the "not yet known" one, and nothing used to replace
     * it. Seen live 2026-08-27: Document Distribution offered no publish
     * destination at all on a production with 42 tools switched on. Only the
     * viewer changes here; the open page and its data are already right.
     */
    /**
     * Swaps in the real rights once the tool grid has answered.
     *
     * `projectId` flips the moment a production is chosen, but the rights that
     * gate this screen arrive with the Home load a beat later, so the viewer
     * resolved at open is the "not yet known" one and nothing used to replace
     * it. Handed in rather than resolved here: unlike its siblings this view
     * model has no permission set of its own — whoever shows the grid owns
     * that, and passes the answer down.
     */
    fun onRightsChanged(resolved: PermissionGridViewer) {
        setState { copy(viewer = resolved) }
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
    /**
     * Asks an admin for the right to edit this grid.
     *
     * Raised from the read-only notice rather than from a cell: the boxes stay
     * inert on purpose — see [toggle].
     */
    private fun askForPostingRights() {
        rights?.ask(MODULE_LABEL, RightsKind.Post)
        // This screen has no effect channel; its own notice bar is where
        // everything else it has to say already goes.
        setState { copy(notice = rightsRefusalMessage(MODULE_LABEL, RightsKind.Post, rights != null)) }
    }

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

private const val MODULE_LABEL = "Viewing & Posting Rights Grid"
