package com.zillit.desktop.feature.email.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * A named set of crew addresses that can be written to at once.
 *
 * Android's `EmailGroup` (`bottomNav/new_email/domain/model/EmailGroup.kt:3-7`).
 * Members are the crew's *mailbox* addresses — the phone collects them from
 * each picked user's `mail_box_detail.email_address`
 * (`EditEmailGroupActivity.kt:213`), which is why a member with no Zillit
 * mailbox cannot be added.
 */
data class EmailGroup(
    val id: String,
    val name: String,
    /**
     * Enabled members only. The server keeps disabled ones on the row
     * (`members_email[].enabled`), and Android drops them before showing the
     * group (`EmailGroupsViewModel.kt:50-52`); a member you cannot see but
     * still write to would be a surprise.
     */
    val members: List<String>,
    /**
     * The group's own address, once the server has provisioned a mailbox for
     * it (`mail_box_detail.email_address` on the row). Null until then.
     */
    val address: String? = null,
) {
    /** Never prints the members — a distribution list is personal data. */
    override fun toString(): String = "EmailGroup(id=$id, name=$name, members=${members.size})"
}

/**
 * Creating, editing and deleting distribution groups.
 *
 * Admin-only on both other clients (Android `GeneralSettingsActivity.kt:89-91`,
 * web `NewEmailSidebar.jsx:143`); the server enforces it, the screen hides it.
 */
interface EmailGroupRepository {

    suspend fun groups(): ZillitResult<List<EmailGroup>>

    suspend fun create(name: String, members: List<String>): ZillitResult<Unit>

    suspend fun update(id: String, name: String, members: List<String>): ZillitResult<Unit>

    suspend fun delete(id: String): ZillitResult<Unit>
}
