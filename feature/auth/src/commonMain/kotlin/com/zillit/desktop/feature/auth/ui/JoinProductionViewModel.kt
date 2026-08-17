package com.zillit.desktop.feature.auth.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.units.UnitRepository
import com.zillit.desktop.feature.auth.domain.JoinDraft
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.domain.ProjectRepository
import com.zillit.desktop.feature.auth.domain.validate

sealed interface JoinEvent {
    data class CodeChanged(val value: String) : JoinEvent
    data object FindProject : JoinEvent
    data class DraftChanged(val draft: JoinDraft) : JoinEvent
    data object Submit : JoinEvent
    data object Dismiss : JoinEvent
}

sealed interface JoinEffect {
    /** The request went in; the production list has to include it now. */
    data object Requested : JoinEffect
    data object Dismissed : JoinEffect
}

/**
 * Asking to join a production.
 *
 * Its own ViewModel rather than more branches on [AuthViewModel], matching
 * [CreateProductionViewModel]: both are self-contained dialogs over the
 * production list, and both end by telling the list to reload.
 */
class JoinProductionViewModel(
    private val projectRepository: ProjectRepository,
    /** Null when units cannot be listed; the form then cannot be completed. */
    private val unitRepository: UnitRepository? = null,
) : ZillitViewModel<JoinFlowState, JoinEvent, JoinEffect>(JoinFlowState()) {

    override fun onEvent(event: JoinEvent) {
        when (event) {
            is JoinEvent.CodeChanged -> setState { copy(codeText = event.value, error = null) }
            JoinEvent.FindProject -> findProject()
            is JoinEvent.DraftChanged -> onDraftChanged(event.draft)
            JoinEvent.Submit -> submit()
            JoinEvent.Dismiss -> {
                setState { JoinFlowState() }
                sendEffect(JoinEffect.Dismissed)
            }
        }
    }

    /**
     * Looks the code up, then fetches what the details step will need.
     *
     * Departments and units are loaded here rather than when the second step
     * renders, so its dropdowns are populated the moment it appears.
     */
    private fun findProject() {
        val code = currentState.codeText.trim()
        if (code.isBlank()) return

        setState { copy(isBusy = true, error = null) }

        launchResult(
            block = { projectRepository.findByCode(code) },
            onSuccess = { project ->
                setState { copy(project = project) }
                loadOptions(project)
            },
            onError = { error -> setState { copy(isBusy = false, error = error.localised()) } },
        )
    }

    /**
     * Departments and units for the production being joined.
     *
     * A personal production has no crew, so it has neither — the form for one
     * asks only for a name, as both other clients do.
     */
    private fun loadOptions(project: Project) {
        if (project.isPersonal) {
            setState { copy(isBusy = false) }
            return
        }

        launch {
            val departments = projectRepository.departments(project.id).getOrNull().orEmpty()
            val units = unitRepository?.joinUnits(project.id)?.getOrNull().orEmpty()
            setState { copy(departments = departments, units = units, isBusy = false) }
        }
    }

    /**
     * Keeps the role consistent with the department.
     *
     * A role that does not belong to the chosen department is dropped — a
     * costume role filed under camera is the kind of thing nobody notices until
     * the crew list is printed.
     *
     * Tested for *membership* rather than "did the department just change",
     * which would also discard a role supplied alongside its own department.
     */
    private fun onDraftChanged(draft: JoinDraft) {
        setState {
            val rolesHere = departments.firstOrNull { it.id == draft.departmentId }?.designations
            val roleBelongs = rolesHere.orEmpty().any { it.id == draft.designationId }
            copy(
                draft = if (draft.designationId != null && !roleBelongs) {
                    draft.copy(designationId = null)
                } else {
                    draft
                },
                errors = emptySet(),
                error = null,
            )
        }
    }

    private fun submit() {
        val project = currentState.project ?: return

        val errors = currentState.draft.validate(currentState.isPersonal)
        if (errors.isNotEmpty()) {
            setState { copy(errors = errors) }
            return
        }

        setState { copy(isBusy = true, error = null) }

        launchResult(
            block = { projectRepository.requestJoin(project.id, currentState.draft) },
            onSuccess = { status ->
                setState { copy(isBusy = false, outcome = status) }
                sendEffect(JoinEffect.Requested)
            },
            onError = { error -> setState { copy(isBusy = false, error = error.localised()) } },
        )
    }
}
