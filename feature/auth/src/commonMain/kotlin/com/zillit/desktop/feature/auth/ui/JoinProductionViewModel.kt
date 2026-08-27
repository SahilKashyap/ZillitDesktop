package com.zillit.desktop.feature.auth.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.units.UnitRepository
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.auth.domain.ChosenPhoto
import com.zillit.desktop.feature.auth.domain.CodeLookup
import com.zillit.desktop.feature.auth.domain.JoinDraft
import com.zillit.desktop.feature.auth.domain.JoinPhotoStore
import com.zillit.desktop.feature.auth.domain.JoinStatus
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.domain.ProjectRepository
import com.zillit.desktop.feature.auth.domain.validate

sealed interface JoinEvent {
    data class CodeChanged(val value: String) : JoinEvent
    data object FindProject : JoinEvent
    data class DraftChanged(val draft: JoinDraft) : JoinEvent
    data object Submit : JoinEvent
    data object Dismiss : JoinEvent

    /** Opens the picker, then stores whatever comes back. */
    data object ChoosePhoto : JoinEvent
    data object RemovePhoto : JoinEvent
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
    /**
     * Where a chosen picture goes. Null on a build with no storage configured,
     * and the form then does not offer a picture at all — better than offering
     * one that silently cannot be saved.
     */
    private val photoStore: JoinPhotoStore? = null,
    /** Opens the host's file picker. Null when the user cancels. */
    private val choosePhoto: suspend () -> ChosenPhoto? = { null },
) : ZillitViewModel<JoinFlowState, JoinEvent, JoinEffect>(JoinFlowState()) {

    init {
        // Said once, from what the host supplied: a form that offers a picture
        // it cannot store is worse than one that never offered.
        if (photoStore != null) setState { copy(canChoosePhoto = true) }
    }


    override fun onEvent(event: JoinEvent) {
        when (event) {
            is JoinEvent.CodeChanged -> setState { copy(codeText = event.value, error = null) }
            JoinEvent.FindProject -> findProject()
            is JoinEvent.DraftChanged -> onDraftChanged(event.draft)
            JoinEvent.Submit -> submit()
            JoinEvent.ChoosePhoto -> pickPhoto()
            JoinEvent.RemovePhoto -> setState { copy(photo = null, photoError = null) }
            JoinEvent.Dismiss -> {
                // Everything typed goes, but not what the host can do.
                setState { JoinFlowState(canChoosePhoto = canChoosePhoto) }
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
            onSuccess = ::onCodeResolved,
            onError = { error -> setState { copy(isBusy = false, error = error.localised()) } },
        )
    }

    /**
     * Branches on what the code turned out to be.
     *
     * A code issued to one person has already put them on the production, so
     * there is nothing to ask: the flow skips straight to the confirmation and
     * the list reloads to show the production they can now open. Asking such a
     * user for a department they have already been given would create a second
     * pending request against their own membership.
     */
    private fun onCodeResolved(lookup: CodeLookup) {
        when (lookup) {
            is CodeLookup.NeedsDetails -> {
                setState { copy(project = lookup.project) }
                loadOptions(lookup.project)
            }

            is CodeLookup.AlreadyOn -> {
                setState { copy(isBusy = false, outcome = JoinStatus.Approved) }
                sendEffect(JoinEffect.Requested)
            }
        }
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

    /**
     * Chooses a picture and stores it there and then.
     *
     * Uploaded on choosing rather than on submit, as both phones do: the wait
     * belongs while the user is still filling the form in, not bolted onto the
     * button that sends it. A failure leaves the form usable and the picture
     * unset — a photo is not worth blocking a join over.
     */
    private fun pickPhoto() {
        val store = photoStore ?: return

        launch {
            val chosen = choosePhoto() ?: return@launch
            setState { copy(isStoringPhoto = true, photoError = null) }

            when (val stored = store.store(chosen)) {
                is ZillitResult.Success -> setState {
                    copy(photo = stored.data, isStoringPhoto = false)
                }

                is ZillitResult.Failure -> setState {
                    copy(isStoringPhoto = false, photoError = stored.error.localised())
                }
            }
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
            // The picture is merged in here, the one place the request is
            // assembled — it is not something the user typed, and keeping it
            // off the draft is what stops a keystroke erasing it.
            block = {
                projectRepository.requestJoin(
                    project.id,
                    currentState.draft.copy(photo = currentState.photo),
                )
            },
            onSuccess = { status ->
                setState { copy(isBusy = false, outcome = status) }
                sendEffect(JoinEffect.Requested)
            },
            onError = { error -> setState { copy(isBusy = false, error = error.localised()) } },
        )
    }
}
