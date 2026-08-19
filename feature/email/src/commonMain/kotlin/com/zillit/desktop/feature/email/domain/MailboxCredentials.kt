package com.zillit.desktop.feature.email.domain

import com.zillit.desktop.core.common.ZillitResult

/** One half of the mailbox — the SMTP or the IMAP endpoint. */
data class MailboxServer(
    val host: String,
    val port: Int?,
    val username: String,
) {
    /** The port as shown; blank when the profile carries none. */
    val portLabel: String get() = port?.toString().orEmpty()
}

/**
 * What an external client needs to talk to this user's Zillit mailbox —
 * everything except the password.
 *
 * Read from the profile's `mail_box_detail` (Android `MailingModel`,
 * `JoinProjectResponse.kt:224-246`; shown by `GeneralSettingsActivity.kt:214-242`).
 * The password is deliberately not here: the profile carries only `enc:v1:…`
 * ciphertext for it (F-008), and the plaintext is fetched on an explicit reveal
 * and held for as long as the screen is up — see
 * [MailboxCredentialsRepository.revealPassword].
 */
data class MailboxCredentials(
    val emailAddress: String,
    val smtp: MailboxServer,
    val imap: MailboxServer,
) {
    /** Never prints the username — it is a login. */
    override fun toString(): String = "MailboxCredentials(address=$emailAddress)"
}

interface MailboxCredentialsRepository {

    /** Null when this user has no mailbox provisioned. */
    suspend fun credentials(): ZillitResult<MailboxCredentials?>

    /**
     * The mailbox password, in plaintext.
     *
     * User action only, never on screen load: every reveal is audit-logged
     * server-side and rate-limited to one a second (Android
     * `MailCredentialsRevealer.kt:14-36`). Callers keep the answer in memory,
     * for the life of the screen, and nowhere else.
     */
    suspend fun revealPassword(): ZillitResult<String>

    suspend fun updatePassword(password: String): ZillitResult<Unit>
}

/**
 * Whether the mailbox lists mail grouped into conversations.
 *
 * Server-side, per user — Android PATCHes it and then mirrors it into
 * SharedPref (`SettingsViewModel.kt:126-159`); the initial value is the
 * profile's `mail_box_detail.conversation_view`
 * (`ConversationViewPreference.kt:26-32`).
 */
interface ConversationViewRepository {

    /** The setting as the server has it. */
    suspend fun isEnabled(): ZillitResult<Boolean>

    suspend fun setEnabled(enabled: Boolean): ZillitResult<Unit>
}
