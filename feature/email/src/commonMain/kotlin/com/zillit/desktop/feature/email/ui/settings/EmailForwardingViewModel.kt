package com.zillit.desktop.feature.email.ui.settings

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.email.domain.EmailForwarding
import com.zillit.desktop.feature.email.domain.EmailForwardingRepository
import com.zillit.desktop.feature.email.domain.looksLikeAddress

data class EmailForwardingUiState(
    /** What the server has; null when forwarding is not set up. */
    val saved: EmailForwarding? = null,
    /** What is in the field. Pre-filled from [saved] once, on load. */
    val address: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val inputError: String? = null,
    val error: String? = null,
    /** "Forwarding saved" / "Forwarding removed" — Android's toasts (`SettingsViewModel.kt:80`, `:101`). */
    val info: String? = null,
) {
    val isConfigured: Boolean get() = saved != null

    /** Save does something only when the field differs from what is stored. */
    val canSave: Boolean get() = address.isNotBlank() && !address.trim().equals(saved?.address, ignoreCase = true)
}

sealed interface EmailForwardingEvent {
    data object Load : EmailForwardingEvent
    data class AddressChanged(val value: String) : EmailForwardingEvent
    data object Save : EmailForwardingEvent
    data object Remove : EmailForwardingEvent
    data object DismissMessage : EmailForwardingEvent
}

/**
 * Auto-forwarding to one outside address — Android `SettingsViewModel`'s
 * forwarding half (`ui/settings/SettingsViewModel.kt:51-118`).
 */
class EmailForwardingViewModel(
    private val repository: EmailForwardingRepository,
) : ZillitViewModel<EmailForwardingUiState, EmailForwardingEvent, Nothing>(EmailForwardingUiState()) {

    override fun onEvent(event: EmailForwardingEvent) {
        when (event) {
            EmailForwardingEvent.Load -> load()
            is EmailForwardingEvent.AddressChanged -> setState {
                copy(address = event.value, inputError = null, info = null)
            }
            EmailForwardingEvent.Save -> save()
            EmailForwardingEvent.Remove -> remove()
            EmailForwardingEvent.DismissMessage -> setState { copy(error = null, info = null) }
        }
    }

    private fun load() {
        setState { copy(isLoading = true, error = null) }
        launchResult(
            block = { repository.current() },
            onSuccess = { current ->
                setState {
                    copy(
                        isLoading = false,
                        saved = current,
                        // Pre-filled only when nothing has been typed yet, so a
                        // slow load does not overwrite what someone is entering
                        // (Android GeneralSettingsActivity.kt:150-156).
                        address = address.ifBlank { current?.address.orEmpty() },
                    )
                }
            },
            // Not configured yet is the ordinary first state, not an error
            // (SettingsViewModel.kt:62-65).
            onError = { setState { copy(isLoading = false) } },
        )
    }

    private fun save() {
        val address = currentState.address.trim()
        if (!address.looksLikeAddress()) {
            // Android's `R.string.valid_email` (GeneralSettingsActivity.kt:128-129).
            setState { copy(inputError = str(S.desktop_please_enter_a_valid_email)) }
            return
        }
        setState { copy(isSaving = true, error = null, info = null) }
        launchResult(
            block = { repository.save(address) },
            onSuccess = { saved ->
                setState {
                    copy(
                        isSaving = false,
                        saved = saved,
                        address = saved.address,
                        info = str(S.desktop_email_forwarding_saved),
                    )
                }
            },
            onError = { setState { copy(isSaving = false, error = it.localised()) } },
        )
    }

    private fun remove() {
        setState { copy(isSaving = true, error = null, info = null) }
        launchResult(
            block = { repository.remove() },
            onSuccess = {
                setState {
                    copy(isSaving = false, saved = null, address = "", info = str(S.desktop_email_forwarding_removed))
                }
            },
            onError = { setState { copy(isSaving = false, error = it.localised()) } },
        )
    }
}
