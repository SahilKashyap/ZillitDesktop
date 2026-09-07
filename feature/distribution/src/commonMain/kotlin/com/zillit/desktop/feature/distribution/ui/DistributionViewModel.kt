package com.zillit.desktop.feature.distribution.ui

import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.distribution.domain.DistributionRepository
import com.zillit.desktop.feature.distribution.domain.DistributionSection
import com.zillit.desktop.feature.distribution.domain.DistributionUser
import com.zillit.desktop.feature.distribution.domain.DistributionViewer
import kotlinx.coroutines.flow.Flow

data class DistributionUiState(
    val users: List<DistributionUser> = emptyList(),
    val isLoading: Boolean = false,
    val viewer: DistributionViewer = DistributionViewer(),
    val selectedUserId: String? = null,
    val section: DistributionSection = DistributionSection.Home,
    val query: String = "",
    val externalOnly: Boolean = false,
    /** Cells with a POST in flight — their switches hold still. */
    val pending: Set<String> = emptySet(),
    val error: String? = null,
) {
    /**
     * The roster: the server's order kept — it is department-priority order,
     * and re-sorting broke it twice on the web (ZL-16934, ZL-17426).
     */
    val listed: List<DistributionUser>
        get() = users
            .filter { it.isListed }
            .filter { !externalOnly || it.isExternal }
            .filter {
                query.isBlank() ||
                    it.userName.contains(query.trim(), ignoreCase = true) ||
                    it.outsider.contains(query.trim(), ignoreCase = true)
            }

    val selected: DistributionUser?
        get() = listed.firstOrNull { it.userId == selectedUserId } ?: listed.firstOrNull()
}

sealed interface DistributionEvent {
    data object Refresh : DistributionEvent
    data class Select(val userId: String) : DistributionEvent
    data class Section(val section: DistributionSection) : DistributionEvent
    data class Search(val query: String) : DistributionEvent
    data class ExternalOnly(val on: Boolean) : DistributionEvent
    data class Toggle(val userId: String, val unitId: String, val enabled: Boolean) : DistributionEvent
    data object DismissError : DistributionEvent
}

sealed interface DistributionEffect {
    data class Notice(val text: String) : DistributionEffect
}

/**
 * The Distribution List: who receives what, one switch per unit — the web's
 * grid, folded to a roster and a per-user panel for a desktop pane.
 */
class DistributionViewModel(
    private val repository: DistributionRepository,
    private val resolveViewer: () -> DistributionViewer,
    /** `distribution:access:update` — another device flipped a switch. */
    private val changes: Flow<Unit>? = null,
    /**
     * Where "ask an admin for this right" goes; null leaves the plain refusal.
     *
     * The frame answers it with the admin picker and sends the request as a
     * chat message — the phones' flow, hosted once. See `RightsRequestSurface`.
     */
    private val rights: RightsRequestBus? = null,
) : ZillitViewModel<DistributionUiState, DistributionEvent, DistributionEffect>(DistributionUiState()) {

    fun start() {
        setState { copy(viewer = resolveViewer()) }
        refresh()
        changes?.let { flow -> launch { flow.collect { refresh(quiet = true) } } }
    }

    override fun onEvent(event: DistributionEvent) {
        when (event) {
            DistributionEvent.Refresh -> refresh()
            is DistributionEvent.Select -> setState { copy(selectedUserId = event.userId) }
            is DistributionEvent.Section -> setState { copy(section = event.section) }
            is DistributionEvent.Search -> setState { copy(query = event.query) }
            is DistributionEvent.ExternalOnly -> setState { copy(externalOnly = event.on) }
            is DistributionEvent.Toggle -> toggle(event)
            DistributionEvent.DismissError -> setState { copy(error = null) }
        }
    }

    private fun refresh(quiet: Boolean = false) {
        if (!quiet) setState { copy(isLoading = true) }
        launchResult(
            block = { repository.allAccess() },
            onSuccess = { rows -> setState { copy(users = rows, isLoading = false) } },
            onError = { error -> setState { copy(isLoading = false, error = error.localised()) } },
        )
    }

    private fun toggle(event: DistributionEvent.Toggle) {
        if (!currentState.viewer.mayToggle) {
            rights?.ask("Distribution", RightsKind.Post)
            sendEffect(
                DistributionEffect.Notice(
                    if (rights == null) {
                        "You don't have posting rights on Distribution."
                    } else {
                        "You don't have posting rights on Distribution — asking an administrator."
                    },
                ),
            )
            return
        }
        val user = currentState.users.firstOrNull { it.userId == event.userId } ?: return
        val cell = cellKey(event.userId, event.unitId)
        if (cell in currentState.pending) return

        // Optimistic, with a rollback — the web's exact bargain.
        setState {
            copy(
                pending = pending + cell,
                users = users.withCell(event.userId, event.unitId, event.enabled),
            )
        }
        launchResult(
            block = {
                repository.setAccess(
                    userId = event.userId,
                    unitId = event.unitId,
                    enabled = event.enabled,
                    section = currentState.section,
                    isExternal = user.isExternal,
                )
            },
            onSuccess = { setState { copy(pending = pending - cell) } },
            onError = { error ->
                setState {
                    copy(
                        pending = pending - cell,
                        users = users.withCell(event.userId, event.unitId, !event.enabled),
                        error = error.localised(),
                    )
                }
            },
        )
    }

    private companion object {
        fun cellKey(userId: String, unitId: String) = "$userId:$unitId"

        fun List<DistributionUser>.withCell(
            userId: String,
            unitId: String,
            enabled: Boolean,
        ): List<DistributionUser> = map { user ->
            if (user.userId != userId) return@map user
            user.copy(
                units = user.units.map { unit ->
                    if (unit.unitId == unitId) unit.copy(toEnabled = enabled) else unit
                },
            )
        }
    }
}
