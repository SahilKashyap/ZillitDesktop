package com.zillit.desktop.feature.email.domain

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which of the two mailboxes a production can give this user the module is
 * operating on.
 *
 * Every production hands each crew member a personal Zillit mailbox
 * (`user/profile` → `mail_box_detail`). A production can also carry one
 * shared **Accounts** mailbox (`project/{id}` → `accounts_mail_box_detail`,
 * populated only for Accounts-department members, `{}` for everyone else),
 * which the web's `MailboxSwitcher` lets those members flip to. Folders,
 * mail, drafts, contacts, signatures, forwarding and rules all belong to
 * whichever is active; every call to the mail service says which with
 * `use_project_account_mailbox=true` (web `mailboxContext.withMailboxScope`).
 */
enum class MailboxKind {
    Personal,
    Accounts,
    ;

    /** The word the web persists (`active_email_mailbox`); read back by [fromWire]. */
    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String?): MailboxKind =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: Personal
    }
}

/**
 * One mailbox as the switcher shows it: a kind, an address, a display name.
 *
 * [conversationView] and [bccPresets] ride along because the Accounts mailbox
 * keeps its own copies on the project (`accounts_mail_box_detail`) while the
 * personal one keeps them on the profile — the switcher is what decides which
 * set the settings edit.
 */
data class MailboxIdentity(
    val kind: MailboxKind,
    val address: String,
    val name: String = "",
    val conversationView: Boolean? = null,
    val bccPresets: List<String> = emptyList(),
) {
    val title: String
        get() = when (kind) {
            MailboxKind.Personal -> str(S.desktop_email_personal_mailbox)
            MailboxKind.Accounts -> str(S.email_rule_tab_accounts_mailbox)
        }

    /** What the `From:` header says — `Name <address>`, the web's `from` payload. */
    fun fromHeader(fallbackName: String = ""): String {
        val display = name.ifBlank { fallbackName }.trim()
        return if (display.isBlank()) address else "$display <$address>"
    }

    /** Never prints the address: a mailbox address is personal data. */
    override fun toString(): String = "MailboxIdentity($kind, named=${name.isNotBlank()})"
}

/**
 * Read by the data layer while building a request: is the shared Accounts
 * mailbox the one every mail-service call should address right now?
 *
 * A function rather than a value because the same repositories serve both
 * mailboxes and the answer changes when the user flips the switcher; the
 * call that is *being built* is the one that has to ask.
 */
fun interface MailboxScope {
    fun isAccountsActive(): Boolean

    companion object {
        /** A build with no switcher — every call is personal. */
        val Personal: MailboxScope = MailboxScope { false }
    }
}

/**
 * The switch itself: which mailbox is active, as observable state.
 *
 * Held apart from the mailbox's UI state so the data layer can read it
 * without knowing about view models, and so the settings window — its own
 * window, its own view models — sees the same choice the mailbox made.
 */
class ActiveMailbox(initial: MailboxKind = MailboxKind.Personal) : MailboxScope {

    private val _kind = MutableStateFlow(initial)
    val kind: StateFlow<MailboxKind> = _kind.asStateFlow()

    private val _identity = MutableStateFlow<MailboxIdentity?>(null)

    /** The active mailbox as the server described it, once the mailbox has asked; null before. */
    val identity: StateFlow<MailboxIdentity?> = _identity.asStateFlow()

    val current: MailboxKind get() = _kind.value

    /** The active mailbox's address, blank until known. */
    val address: String get() = _identity.value?.address.orEmpty()

    override fun isAccountsActive(): Boolean = _kind.value == MailboxKind.Accounts

    fun switch(kind: MailboxKind, identity: MailboxIdentity? = _identity.value?.takeIf { it.kind == kind }) {
        _kind.value = kind
        _identity.value = identity
    }

    /** The `From:` header for mail sent from here — `Name <address>` — or blank while unknown. */
    fun fromHeader(fallbackName: String = ""): String = _identity.value?.fromHeader(fallbackName).orEmpty()
}

/**
 * Where the choice is remembered between openings — per production, as the
 * web keys `active_email_mailbox` by project id: a mailbox belongs to a
 * production, and so does the preference for it.
 */
interface MailboxPreferences {
    suspend fun activeMailbox(): MailboxKind?
    suspend fun setActiveMailbox(kind: MailboxKind)

    /** Whether the switcher's one-time tour has been shown on this production. */
    suspend fun hasSeenMailboxTour(): Boolean
    suspend fun markMailboxTourSeen()

    companion object {
        /** Forgets everything: a test double, and the default for a host without preferences. */
        val None: MailboxPreferences = object : MailboxPreferences {
            override suspend fun activeMailbox(): MailboxKind? = null
            override suspend fun setActiveMailbox(kind: MailboxKind) = Unit
            override suspend fun hasSeenMailboxTour(): Boolean = true
            override suspend fun markMailboxTourSeen() = Unit
        }
    }
}

/**
 * The production's two mailboxes, as the server describes them.
 *
 * [personal] comes off `user/profile` and [accounts] off `project/{id}`;
 * both are re-read every time the mailbox opens because Accounts-department
 * membership changes while the user is elsewhere and the switcher gate must
 * not trust a stale answer (the web refetches the project on entry for the
 * same reason).
 */
interface MailboxDirectory {
    suspend fun personal(): ZillitResult<MailboxIdentity?>

    /** Null when this user is not an Accounts-department member here. */
    suspend fun accounts(): ZillitResult<MailboxIdentity?>

    /** `PATCH project/accounts-mail-box/conversation-view` — the shared mailbox's own setting. */
    suspend fun setAccountsConversationView(enabled: Boolean): ZillitResult<Unit>

    /** `PATCH project/accounts-mail-box/bcc` — the shared mailbox's own presets. */
    suspend fun setAccountsBccPresets(addresses: List<String>): ZillitResult<Unit>
}

/**
 * The server's refusals for the shared-mailbox flag — access revoked, or the
 * production never provisioned one. Either means the module must fall back
 * to the personal mailbox rather than sit on an empty shell (web
 * `ACCOUNTS_MAILBOX_ERROR_CODES`).
 */
val ACCOUNTS_MAILBOX_ERROR_CODES: Set<String> = setOf(
    "email_accounts_mailbox_access_denied",
    "email_accounts_mailbox_not_configured",
)
