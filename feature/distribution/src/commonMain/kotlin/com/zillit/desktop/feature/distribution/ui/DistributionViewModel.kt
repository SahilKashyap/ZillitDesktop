package com.zillit.desktop.feature.distribution.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.distribution.domain.DISTRIBUTION_DEFAULT_PAGE_SIZE
import com.zillit.desktop.feature.distribution.domain.DistributionColumn
import com.zillit.desktop.feature.distribution.domain.DistributionDirectory
import com.zillit.desktop.feature.distribution.domain.DistributionPage
import com.zillit.desktop.feature.distribution.domain.DistributionPerson
import com.zillit.desktop.feature.distribution.domain.DistributionRepository
import com.zillit.desktop.feature.distribution.domain.DistributionSection
import com.zillit.desktop.feature.distribution.domain.DistributionUser
import com.zillit.desktop.feature.distribution.domain.DistributionViewer
import com.zillit.desktop.feature.distribution.domain.columns
import com.zillit.desktop.feature.distribution.domain.paged
import com.zillit.desktop.feature.distribution.domain.visibleRows
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate

data class DistributionUiState(
    val users: List<DistributionUser> = emptyList(),
    /** The crew and the outsiders by id — names, faces' owners, designations. */
    val people: Map<String, DistributionPerson> = emptyMap(),
    val isLoading: Boolean = false,
    val viewer: DistributionViewer = DistributionViewer(),
    val section: DistributionSection = DistributionSection.Home,
    val query: String = "",
    /** Unit ids to show; empty means every column. Cleared when the section changes, as the web does. */
    val unitFilter: List<String> = emptyList(),
    val externalOnly: Boolean = false,
    val page: Int = 1,
    val pageSize: Int = DISTRIBUTION_DEFAULT_PAGE_SIZE,
    /**
     * Which unit each user has a POST in flight for. The web disables the
     * rest of that user's row while one cell saves, and shows the spinner on
     * the cell itself — the same map drives both.
     */
    val busy: Map<String, String> = emptyMap(),
    val error: String? = null,
) {
    /** The filtered roster, in the server's department-priority order. */
    val rows: List<DistributionUser>
        get() = users.visibleRows(people, query, externalOnly)

    /** The rows on the current page. */
    val currentPage: DistributionPage<DistributionUser>
        get() = rows.paged(page, pageSize)

    /** Every column the section offers, for the filter's options. */
    fun allColumns(translate: (String) -> String): List<DistributionColumn> = users.columns(section, translate)

    /** The columns the matrix shows — all, or the picked ones in the same order. */
    fun visibleColumns(translate: (String) -> String): List<DistributionColumn> {
        val all = allColumns(translate)
        if (unitFilter.isEmpty()) return all
        val picked = unitFilter.toSet()
        return all.filter { it.unitId in picked }
    }

    fun isBusy(userId: String, unitId: String): Boolean = busy[userId] == unitId

    /** The web's rule: a row with a save in flight holds its other cells still. */
    fun isHeld(userId: String, unitId: String): Boolean = busy[userId]?.let { it != unitId } == true
}

sealed interface DistributionEvent {
    data object Refresh : DistributionEvent
    data class Section(val section: DistributionSection) : DistributionEvent
    data class Search(val query: String) : DistributionEvent
    data class UnitFilter(val unitIds: List<String>) : DistributionEvent
    data class ExternalOnly(val on: Boolean) : DistributionEvent
    data class GoToPage(val page: Int) : DistributionEvent
    data class PageSize(val size: Int) : DistributionEvent
    data class Toggle(val userId: String, val unitId: String, val enabled: Boolean) : DistributionEvent
    /** "MUST READ" — the documentation site's essential note [number] (1–3). */
    data class OpenEssentialNote(val number: Int) : DistributionEvent
    /** The admin banner's "Click Here" — the department listing order editor. */
    data object OpenListingOrder : DistributionEvent
    data object DismissError : DistributionEvent
}

sealed interface DistributionEffect {
    data class Notice(val text: String, val success: Boolean = false) : DistributionEffect
    data class OpenUrl(val url: String) : DistributionEffect
    data object OpenListingOrder : DistributionEffect
}

/**
 * The Distribution List: who receives what, one checkbox per unit — the web's
 * `DistributionAcessgrid.jsx`, the page the Film Tools tile opens.
 */
class DistributionViewModel(
    private val repository: DistributionRepository,
    private val resolveViewer: () -> DistributionViewer,
    /** The crew list and the outsiders, for the names and designations the rows lack. */
    private val directory: DistributionDirectory? = null,
    /** `distribution:access:update` — another device flipped a switch. */
    private val changes: Flow<Unit>? = null,
    /** `department:reordered` — the rows' order changed; the web refetches for admins. */
    private val reorders: Flow<Unit>? = null,
    /**
     * Where "ask an admin for this right" goes; null leaves the plain refusal.
     *
     * The frame answers it with the admin picker and sends the request as a
     * chat message — the phones' flow, hosted once. See `RightsRequestSurface`.
     */
    private val rights: RightsRequestBus? = null,
    private val translate: (String) -> String = { it.localised() },
) : ZillitViewModel<DistributionUiState, DistributionEvent, DistributionEffect>(DistributionUiState()) {

    private var listening = false

    fun start() {
        setState { copy(viewer = resolveViewer()) }
        refresh()
        listenOnce()
    }

    /** Guarded so a reopened window does not stack collectors. */
    private fun listenOnce() {
        if (listening) return
        listening = true
        changes?.let { flow -> launch { flow.conflate().collect { refresh(quiet = true) } } }
        reorders?.let { flow -> launch { flow.conflate().collect { refresh(quiet = true) } } }
    }

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one line per act.
    override fun onEvent(event: DistributionEvent) {
        when (event) {
            DistributionEvent.Refresh -> refresh()
            is DistributionEvent.Section -> setState {
                copy(section = event.section, unitFilter = emptyList(), page = 1)
            }
            is DistributionEvent.Search -> setState { copy(query = event.query, page = 1) }
            is DistributionEvent.UnitFilter -> setState { copy(unitFilter = event.unitIds) }
            is DistributionEvent.ExternalOnly -> setState { copy(externalOnly = event.on, page = 1) }
            is DistributionEvent.GoToPage -> setState { copy(page = event.page.coerceAtLeast(1)) }
            is DistributionEvent.PageSize -> setState { copy(pageSize = event.size.coerceAtLeast(1), page = 1) }
            is DistributionEvent.Toggle -> toggle(event)
            is DistributionEvent.OpenEssentialNote -> sendEffect(
                DistributionEffect.OpenUrl("$DOCUMENTATION_URL#essential-note-${event.number}"),
            )
            DistributionEvent.OpenListingOrder ->
                if (currentState.viewer.isAdmin) sendEffect(DistributionEffect.OpenListingOrder)
            DistributionEvent.DismissError -> setState { copy(error = null) }
        }
    }

    private fun refresh(quiet: Boolean = false) {
        if (!quiet) setState { copy(isLoading = true, error = null) }
        launchResult(
            block = { repository.allAccess() },
            onSuccess = { rows -> setState { copy(users = rows, isLoading = false) } },
            onError = { error -> setState { copy(isLoading = false, error = error.localised()) } },
        )
        val source = directory ?: return
        launch {
            val people = source.people()
            if (people.isNotEmpty()) setState { copy(people = people.associateBy { it.userId }) }
        }
    }

    private fun toggle(event: DistributionEvent.Toggle) {
        if (!currentState.viewer.mayToggle) {
            rights?.ask("Distribution", RightsKind.Post)
            sendEffect(
                DistributionEffect.Notice(
                    if (rights == null) {
                        str(S.desktop_dist_no_posting_rights)
                    } else {
                        str(S.desktop_dist_no_posting_rights_asking_admin)
                    },
                ),
            )
            return
        }
        val user = currentState.users.firstOrNull { it.userId == event.userId } ?: return
        if (currentState.busy.containsKey(event.userId)) return
        val section = currentState.section
        val previous = user.cell(event.unitId, section)?.toEnabled ?: false

        // Optimistic, with a rollback — the web's exact bargain.
        setState {
            copy(
                busy = busy + (event.userId to event.unitId),
                users = users.withCell(event.userId, event.unitId, section, event.enabled),
            )
        }
        launchResult(
            block = {
                repository.setAccess(
                    userId = event.userId,
                    unitId = event.unitId,
                    enabled = event.enabled,
                    section = section,
                    isExternal = user.isExternal || currentState.people[user.userId]?.isExternal == true,
                )
            },
            onSuccess = { message ->
                setState { copy(busy = busy - event.userId) }
                sendEffect(
                    DistributionEffect.Notice(
                        text = message?.localisedMessage() ?: str(S.desktop_dist_updated),
                        success = true,
                    ),
                )
            },
            onError = { error ->
                setState {
                    copy(
                        busy = busy - event.userId,
                        users = users.withCell(event.userId, event.unitId, section, previous),
                        error = error.localised(),
                    )
                }
            },
        )
    }

    private companion object {
        const val DOCUMENTATION_URL = "https://documentation.zillit.com/"

        fun List<DistributionUser>.withCell(
            userId: String,
            unitId: String,
            section: DistributionSection,
            enabled: Boolean,
        ): List<DistributionUser> = map { user ->
            if (user.userId != userId) return@map user
            user.copy(
                units = user.units.map { unit ->
                    if (unit.unitId == unitId && unit.inSection(section)) unit.copy(toEnabled = enabled) else unit
                },
            )
        }
    }
}
