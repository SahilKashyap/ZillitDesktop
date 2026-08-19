package com.zillit.desktop.feature.email.ui.settings

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.email.domain.ConversationViewRepository

/**
 * The sub-pages of Email Settings, in the order Android lists the cards
 * (`res/layout/email_fragment_settings.xml`). Signatures is not here: it is
 * a window of its own already, and the card opens it.
 */
enum class EmailSettingsSection {
    Groups,
    BccPresets,
    Forwarding,
    Credentials,
}

data class EmailSettingsUiState(
    /**
     * Whether mail is listed as conversations. Starts on, as the phone does
     * when it has nothing better (`ConversationViewPreference.kt:30`), and is
     * replaced by the server's answer on load.
     */
    val conversationView: Boolean = true,
    val isLoading: Boolean = false,
    val isSavingConversationView: Boolean = false,
    /** Which sub-page is showing; null is the card list. */
    val section: EmailSettingsSection? = null,
    /**
     * Whether the Email Groups card shows. Admin-only on both other clients
     * (`GeneralSettingsActivity.kt:89-91`, `NewEmailSidebar.jsx:143`).
     */
    val canManageGroups: Boolean = true,
    val error: String? = null,
)

sealed interface EmailSettingsEvent {
    data object Load : EmailSettingsEvent

    data class ConversationViewChanged(val enabled: Boolean) : EmailSettingsEvent

    data class Open(val section: EmailSettingsSection) : EmailSettingsEvent

    /** Back to the card list. */
    data object Back : EmailSettingsEvent

    data object DismissError : EmailSettingsEvent
}

/**
 * The settings hub: the card list, and the one setting that lives on it.
 *
 * Each sub-page has its own view model — they are four unrelated endpoint
 * families, and one class holding all of them would be over the size limit
 * before it did anything. This one knows which page is open and owns the
 * conversation-view toggle, which has no page of its own.
 */
class EmailSettingsViewModel(
    private val repository: ConversationViewRepository,
    /**
     * Read at load rather than passed as a value: the settings window can
     * outlive a production switch, and admin status is per production.
     */
    private val isAdmin: () -> Boolean = { true },
) : ZillitViewModel<EmailSettingsUiState, EmailSettingsEvent, Nothing>(EmailSettingsUiState()) {

    override fun onEvent(event: EmailSettingsEvent) {
        when (event) {
            EmailSettingsEvent.Load -> load()
            is EmailSettingsEvent.ConversationViewChanged -> setConversationView(event.enabled)
            is EmailSettingsEvent.Open -> setState { copy(section = event.section, error = null) }
            EmailSettingsEvent.Back -> setState { copy(section = null) }
            EmailSettingsEvent.DismissError -> setState { copy(error = null) }
        }
    }

    private fun load() {
        setState { copy(isLoading = true, error = null, canManageGroups = isAdmin()) }
        launchResult(
            block = { repository.isEnabled() },
            onSuccess = { enabled -> setState { copy(isLoading = false, conversationView = enabled) } },
            // The toggle keeps its default: a settings page that will not open
            // because one read failed is worse than one whose switch may be
            // a step behind the server.
            onError = { setState { copy(isLoading = false, error = it.localised()) } },
        )
    }

    /**
     * Optimistic, and reverted on failure — Android's exact sequence
     * (`SettingsViewModel.kt:133-158`): flip, PATCH, put it back if the
     * server said no.
     */
    private fun setConversationView(enabled: Boolean) {
        val previous = currentState.conversationView
        if (previous == enabled) return
        setState { copy(conversationView = enabled, isSavingConversationView = true, error = null) }
        launchResult(
            block = { repository.setEnabled(enabled) },
            onSuccess = { setState { copy(isSavingConversationView = false) } },
            onError = { error ->
                setState {
                    copy(
                        conversationView = previous,
                        isSavingConversationView = false,
                        error = error.localised(),
                    )
                }
            },
        )
    }
}
