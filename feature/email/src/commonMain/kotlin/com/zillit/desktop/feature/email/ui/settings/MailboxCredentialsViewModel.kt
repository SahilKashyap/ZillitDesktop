package com.zillit.desktop.feature.email.ui.settings

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.email.domain.MailboxCredentials
import com.zillit.desktop.feature.email.domain.MailboxCredentialsRepository
import kotlin.time.Clock

data class MailboxCredentialsUiState(
    /** Null while loading, or when this user has no mailbox. */
    val credentials: MailboxCredentials? = null,
    val isLoading: Boolean = false,
    /**
     * The password, in plaintext, while it is shown. Memory only: it arrives
     * from the reveal endpoint on a click and goes when hidden, when the page
     * is left, and when the window closes.
     */
    val revealedPassword: String? = null,
    val isRevealing: Boolean = false,
    /** The change-password dialog is up. */
    val isChangingPassword: Boolean = false,
    val newPassword: String = "",
    val isUpdatingPassword: Boolean = false,
    val error: String? = null,
    val info: String? = null,
) {
    val isRevealed: Boolean get() = revealedPassword != null

    /** Android's `addTextNotEmptyValidation` (`GeneralSettingsActivity.kt:202`). */
    val canUpdatePassword: Boolean get() = newPassword.isNotBlank()

    /** Never prints either password — this state is what ends up in a log line. */
    override fun toString(): String =
        "MailboxCredentialsUiState(loaded=${credentials != null}, revealed=$isRevealed, changing=$isChangingPassword)"
}

sealed interface MailboxCredentialsEvent {
    data object Load : MailboxCredentialsEvent

    /** Show the password if hidden; hide — and forget — it if shown. */
    data object ToggleReveal : MailboxCredentialsEvent

    /** Forget the plaintext. Sent when the page is left or the window closes. */
    data object Hide : MailboxCredentialsEvent

    /** Put the password on the clipboard, fetching it first if it is not up. */
    data object CopyPassword : MailboxCredentialsEvent

    data object ChangePassword : MailboxCredentialsEvent
    data class NewPasswordChanged(val value: String) : MailboxCredentialsEvent
    data object ConfirmNewPassword : MailboxCredentialsEvent
    data object CancelNewPassword : MailboxCredentialsEvent
    data object DismissMessage : MailboxCredentialsEvent
}

sealed interface MailboxCredentialsEffect {
    /**
     * Text for the clipboard. The password goes this way rather than through
     * state so it is never *held* for copying — only handed over.
     */
    data class Copy(val text: String) : MailboxCredentialsEffect {
        override fun toString(): String = "Copy(…)"
    }
}

/**
 * The "Email Setup Externally" page — Android `GeneralSettingsActivity`'s
 * credentials sheet (`GeneralSettingsActivity.kt:175-250`) and the
 * `RevealablePasswordBinder` that drives its password row.
 *
 * ## The rules the password follows
 *
 * It is fetched only on a click, never on load — every reveal is audit-logged
 * server-side, so a page-load fetch would forge a trail of reveals nobody
 * asked for. Hiding it forgets it: showing it again is another explicit action
 * and another audit line, which is the point (`RevealablePasswordBinder.kt:42-50`).
 * Two clicks inside a second are refused locally before the server's own
 * 1 req/s limit turns the second into a 429 (`MailCredentialsRevealer.kt:39-40`).
 */
class MailboxCredentialsViewModel(
    private val repository: MailboxCredentialsRepository,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ZillitViewModel<MailboxCredentialsUiState, MailboxCredentialsEvent, MailboxCredentialsEffect>(
    MailboxCredentialsUiState(),
) {

    private var lastRevealAt = 0L

    override fun onEvent(event: MailboxCredentialsEvent) {
        when (event) {
            MailboxCredentialsEvent.Load -> load()
            MailboxCredentialsEvent.ToggleReveal ->
                if (currentState.isRevealed) hide() else withPassword { setState { copy(revealedPassword = it) } }
            MailboxCredentialsEvent.Hide -> hide()
            MailboxCredentialsEvent.CopyPassword -> withPassword { sendEffect(MailboxCredentialsEffect.Copy(it)) }
            MailboxCredentialsEvent.ChangePassword ->
                setState { copy(isChangingPassword = true, newPassword = "", error = null, info = null) }
            is MailboxCredentialsEvent.NewPasswordChanged -> setState { copy(newPassword = event.value) }
            MailboxCredentialsEvent.ConfirmNewPassword -> updatePassword()
            MailboxCredentialsEvent.CancelNewPassword -> setState { copy(isChangingPassword = false, newPassword = "") }
            MailboxCredentialsEvent.DismissMessage -> setState { copy(error = null, info = null) }
        }
    }

    private fun load() {
        setState { copy(isLoading = true, error = null) }
        launchResult(
            block = { repository.credentials() },
            onSuccess = { found -> setState { copy(isLoading = false, credentials = found) } },
            onError = { setState { copy(isLoading = false, error = it.localised()) } },
        )
    }

    private fun hide() = setState { copy(revealedPassword = null) }

    /**
     * Runs [action] with the plaintext — the copy already up, or one fetched
     * now. Android's `withPlaintext` (`RevealablePasswordBinder.kt:82-102`).
     */
    private fun withPassword(action: (String) -> Unit) {
        currentState.revealedPassword?.let { action(it); return }
        if (currentState.isRevealing) return

        val now = nowMillis()
        if (now - lastRevealAt < MIN_REVEAL_INTERVAL_MS) {
            setState { copy(error = TOO_FAST) }
            return
        }
        lastRevealAt = now

        setState { copy(isRevealing = true, error = null) }
        launchResult(
            block = { repository.revealPassword() },
            onSuccess = { password ->
                setState { copy(isRevealing = false) }
                action(password)
            },
            onError = { error -> setState { copy(isRevealing = false, error = error.revealMessage()) } },
        )
    }

    /**
     * `PUT imap-credentials/update`, then the profile is what Android
     * refreshes (`GeneralSettingsActivity.kt:268-273`); here the revealed copy
     * is dropped instead, since it is now wrong.
     */
    private fun updatePassword() {
        val password = currentState.newPassword.takeIf { it.isNotBlank() } ?: return
        setState { copy(isUpdatingPassword = true, error = null) }
        launchResult(
            block = { repository.updatePassword(password) },
            onSuccess = {
                setState {
                    copy(
                        isUpdatingPassword = false,
                        isChangingPassword = false,
                        newPassword = "",
                        revealedPassword = null,
                        info = "Password updated",
                    )
                }
            },
            onError = { setState { copy(isUpdatingPassword = false, error = it.localised()) } },
        )
    }

    private companion object {
        /** Android leaves headroom over the server's 1 req/s (`MailCredentialsRevealer.kt:40`). */
        const val MIN_REVEAL_INTERVAL_MS = 1_200L

        // Android's strings for the three outcomes (res/values/strings.xml).
        const val TOO_FAST = "Please wait a moment before trying again."
        const val NOT_AVAILABLE = "No mailbox is set up for your account yet."
        const val FAILED = "Couldn't fetch the password. Please try again."

        /** Server code for "no mailbox provisioned" — `MailCredentialsRevealer.ERROR_NOT_AVAILABLE`. */
        const val NOT_AVAILABLE_CODE = "email_credentials_not_available"

        private fun ZillitError.revealMessage(): String = when {
            this is ZillitError.Http && serverMessage?.contains(NOT_AVAILABLE_CODE) == true -> NOT_AVAILABLE
            // A translated server message is worth showing; anything else
            // (transport, decode) gets the generic line, as the phone does
            // (RevealablePasswordBinder.kt:104-114).
            this is ZillitError.Http && !serverMessage.isNullOrBlank() -> localised()
            else -> FAILED
        }
    }
}
