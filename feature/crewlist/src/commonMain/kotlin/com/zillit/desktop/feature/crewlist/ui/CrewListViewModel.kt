package com.zillit.desktop.feature.crewlist.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.crewlist.domain.CrewListRepository
import com.zillit.desktop.feature.crewlist.domain.CrewListTransfer
import com.zillit.desktop.feature.crewlist.domain.CrewListViewer
import com.zillit.desktop.feature.crewlist.domain.CrewUnit
import kotlinx.coroutines.flow.conflate

data class CrewListUiState(
    val units: List<CrewUnit> = emptyList(),
    val isLoading: Boolean = false,
    val viewer: CrewListViewer = CrewListViewer(),
    val query: String = "",
    /** The generate dialog when open. */
    val generating: GenerateDialog? = null,
    val error: String? = null,
)

/** The one question and the one wait. */
data class GenerateDialog(
    val hideExternalLabel: Boolean = false,
    val isWorking: Boolean = false,
)

sealed interface CrewListEvent {
    data object Refresh : CrewListEvent
    data class Search(val query: String) : CrewListEvent
    data object OpenGenerate : CrewListEvent
    data class HideExternal(val hide: Boolean) : CrewListEvent
    data object GenerateAndView : CrewListEvent
    data object CancelGenerate : CrewListEvent
    data object DismissError : CrewListEvent
}

sealed interface CrewListEffect {
    data class Notice(val text: String) : CrewListEffect
}

/**
 * The Crew List: the roster, a search, and the generate act — the phones'
 * surface of `generate_crew_list_tool`.
 */
class CrewListViewModel(
    private val repository: CrewListRepository,
    private val transfer: CrewListTransfer,
    private val resolveViewer: () -> CrewListViewer,
    /** Translates a label key for search — injected so the domain stays pure. */
    private val translate: (String) -> String = { it },
) : ZillitViewModel<CrewListUiState, CrewListEvent, CrewListEffect>(CrewListUiState()) {

    fun start() {
        setState { copy(viewer = resolveViewer()) }
        refresh()
        listenOnce()
    }

    /**
     * Reloads the roster when the socket says a department was reordered
     * elsewhere — the web's `department_reordered` handler refetches the
     * users (`NewCrewList.jsx:244`). Guarded so a second start (the window
     * reopening) does not stack collectors; `conflate()` folds a burst
     * into one reload.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.conflate().collect { refresh() }
        }
    }

    private var listening = false

    override fun onEvent(event: CrewListEvent) {
        when (event) {
            CrewListEvent.Refresh -> refresh()
            is CrewListEvent.Search -> setState { copy(query = event.query) }
            CrewListEvent.OpenGenerate -> {
                if (currentState.viewer.mayGenerate) {
                    setState { copy(generating = GenerateDialog()) }
                } else {
                    sendEffect(CrewListEffect.Notice("You don't have rights to generate the crew list."))
                }
            }
            is CrewListEvent.HideExternal -> setState {
                copy(generating = generating?.copy(hideExternalLabel = event.hide))
            }
            CrewListEvent.GenerateAndView -> generate()
            CrewListEvent.CancelGenerate -> setState { copy(generating = null) }
            CrewListEvent.DismissError -> setState { copy(error = null) }
        }
    }

    /**
     * The visible roster: units and departments pruned to the members whose
     * name or translated designation carries the query — the web's rule.
     */
    fun visibleUnits(): List<CrewUnit> {
        val needle = currentState.query.trim()
        if (needle.isBlank()) return currentState.units
        return currentState.units.mapNotNull { unit ->
            val departments = unit.departments.mapNotNull { department ->
                val members = department.members.filter { member ->
                    member.fullName.contains(needle, ignoreCase = true) ||
                        translate(member.designationName).contains(needle, ignoreCase = true)
                }
                department.copy(members = members).takeIf { members.isNotEmpty() }
            }
            unit.copy(departments = departments).takeIf { departments.isNotEmpty() }
        }
    }

    private fun refresh() {
        setState { copy(isLoading = true) }
        launchResult(
            block = { repository.roster() },
            onSuccess = { rows -> setState { copy(units = rows, isLoading = false) } },
            onError = { error -> setState { copy(isLoading = false, error = error.localised()) } },
        )
    }

    private fun generate() {
        val dialog = currentState.generating ?: return
        if (dialog.isWorking) return
        setState { copy(generating = dialog.copy(isWorking = true)) }
        launchResult(
            block = { repository.generate(dialog.hideExternalLabel) },
            onSuccess = { pdf ->
                launchResult(
                    block = { transfer.open(pdf) },
                    onSuccess = {
                        setState { copy(generating = null) }
                        sendEffect(CrewListEffect.Notice("Crew list saved to Downloads."))
                    },
                    onError = { error ->
                        setState { copy(generating = null, error = error.localised()) }
                    },
                )
            },
            onError = { error ->
                setState { copy(generating = null, error = error.localised()) }
            },
        )
    }
}
