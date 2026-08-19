package com.zillit.desktop.feature.email.ui.settings

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.email.domain.BccPresetRepository
import com.zillit.desktop.feature.email.domain.EmailContact
import com.zillit.desktop.feature.email.domain.bccPresetError
import com.zillit.desktop.feature.email.domain.suggestionsFor

data class BccPresetsUiState(
    val presets: List<String> = emptyList(),
    val input: String = "",
    /** Crew matching [input], for the drop-down under it (Android `EmailPresetPage.kt:205-219`). */
    val suggestions: List<EmailContact> = emptyList(),
    val inputError: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    /** Android asks before removing (`EmailPresetPage.kt:112-127`). */
    val pendingRemove: String? = null,
    val error: String? = null,
) {
    /** Never prints the addresses. */
    override fun toString(): String = "BccPresetsUiState(presets=${presets.size}, loading=$isLoading)"
}

sealed interface BccPresetsEvent {
    data object Load : BccPresetsEvent
    data class InputChanged(val value: String) : BccPresetsEvent

    /** Adds what is typed. */
    data object Add : BccPresetsEvent

    /** Adds a crew member from the drop-down. */
    data class Pick(val address: String) : BccPresetsEvent

    data class AskRemove(val address: String) : BccPresetsEvent
    data object ConfirmRemove : BccPresetsEvent
    data object DismissRemove : BccPresetsEvent
    data object DismissError : BccPresetsEvent
}

/**
 * Addresses blind-copied on everything sent — Android `EmailPresetPage`
 * (`mailing/views/EmailPresetPage.kt`).
 *
 * The server takes the whole list on every write, so add and remove are both
 * "send the list with one change" and both replace the shown list only once
 * the server has agreed. An optimistic add that the server then refused would
 * leave an address on screen that no message would ever be copied to.
 */
class BccPresetsViewModel(
    private val repository: BccPresetRepository,
    private val crew: () -> List<EmailContact> = { emptyList() },
) : ZillitViewModel<BccPresetsUiState, BccPresetsEvent, Nothing>(BccPresetsUiState()) {

    override fun onEvent(event: BccPresetsEvent) {
        when (event) {
            BccPresetsEvent.Load -> load()
            is BccPresetsEvent.InputChanged -> setState {
                copy(
                    input = event.value,
                    suggestions = crew().suggestionsFor(event.value, exclude = presets),
                    inputError = null,
                )
            }
            BccPresetsEvent.Add -> add(currentState.input)
            is BccPresetsEvent.Pick -> add(event.address)
            is BccPresetsEvent.AskRemove -> setState { copy(pendingRemove = event.address) }
            BccPresetsEvent.DismissRemove -> setState { copy(pendingRemove = null) }
            BccPresetsEvent.ConfirmRemove -> remove()
            BccPresetsEvent.DismissError -> setState { copy(error = null) }
        }
    }

    private fun load() {
        setState { copy(isLoading = presets.isEmpty(), error = null) }
        launchResult(
            block = { repository.presets() },
            onSuccess = { list -> setState { copy(isLoading = false, presets = list) } },
            onError = { setState { copy(isLoading = false, error = it.localised()) } },
        )
    }

    private fun add(candidate: String) {
        val invalid = bccPresetError(candidate, currentState.presets)
        if (invalid != null) {
            setState { copy(inputError = invalid.message) }
            return
        }
        // Lower-cased as the phone stores them (EmailPresetPage.kt:195), so
        // the duplicate check above and the server's agree.
        persist(currentState.presets + candidate.trim().lowercase()) {
            copy(input = "", suggestions = emptyList())
        }
    }

    private fun remove() {
        val address = currentState.pendingRemove ?: return
        setState { copy(pendingRemove = null) }
        persist(currentState.presets.filterNot { it.equals(address, ignoreCase = true) }) { this }
    }

    private fun persist(next: List<String>, onSaved: BccPresetsUiState.() -> BccPresetsUiState) {
        setState { copy(isSaving = true, error = null) }
        launchResult(
            block = { repository.save(next) },
            onSuccess = { setState { copy(isSaving = false, presets = next).onSaved() } },
            onError = { setState { copy(isSaving = false, error = it.localised()) } },
        )
    }
}
