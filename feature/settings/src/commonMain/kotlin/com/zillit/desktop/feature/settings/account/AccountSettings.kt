package com.zillit.desktop.feature.settings.account

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The pages behind the "Your account" rows on Settings.
 *
 * One enum rather than five providers, because they share everything that
 * matters: the same window, the same back chevron, one view model, and one
 * repository against `user/…` and `device/…`. The Settings provider reads this
 * to decide which page a route means — see `SettingsToolProvider`.
 */
enum class AccountPage(val slug: String, private val tabTitleKey: String) {
    EditProfile("profile", S.desktop_your_profile),
    RecoveryEmail("recovery-email", S.recovery_email),
    LinkedDevices("devices", S.desktop_linked_devices),
    InviteCrew("invite", S.desktop_cal_invite_crew),
    ;

    val tabTitle: String get() = str(tabTitleKey)

    companion object {
        fun fromPath(path: String): AccountPage? =
            entries.firstOrNull { path.endsWith("/${it.slug}") }
    }
}

/**
 * What a profile edit asks for.
 *
 * The four fields all three clients send, and no more. The server accepts a
 * larger body — the web's preferences page writes half a dozen other keys
 * through the same `PUT` — but a screen that submits fields it never showed is
 * a screen that silently reverts whatever another one set.
 */
data class ProfileEdit(
    val firstName: String,
    val lastName: String,
    val departmentId: String?,
    val designationId: String?,
    val keepNamePrivate: Boolean,
    /** `zillit_email_enable`; null leaves it untouched. */
    val showMailboxInCrewList: Boolean? = null,
)

/**
 * What happened to a profile edit.
 *
 * Admins write straight through; everyone else files a request that another
 * admin approves later, in the queue this module already has a page for. Two
 * outcomes rather than one because the sentence the screen shows afterwards is
 * genuinely different — "Saved" versus "Sent for approval" — and getting it
 * wrong tells a crew member their new name is live when it is not.
 */
enum class ProfileSaveOutcome { Saved, SentForApproval }

/**
 * Whether this person's name may be withheld from the crew list.
 *
 * Not a general preference: producers, main cast and studio executives get the
 * option, and nobody else does. Both phone clients and the web gate the toggle
 * on exactly these three designation keys (ZL-14876), and hide it otherwise —
 * showing it to a grip who cannot use it would be an offer the server declines.
 */
fun allowsPrivateName(designationKey: String?): Boolean =
    designationKey?.trim()?.lowercase() in PRIVATE_NAME_DESIGNATIONS

private val PRIVATE_NAME_DESIGNATIONS = setOf(
    "producer_label",
    "main_cast_label",
    "studio_executive_label",
)

/**
 * Another computer or phone signed in to Zillit as this person.
 *
 * A crew member routinely runs the phone on set and the desktop in the
 * production office, and the two are one account with two devices. This is the
 * list of them, and the way to end one remotely when a phone is lost.
 */
data class LinkedDevice(
    val id: String,
    /** What the device calls itself — "Sahil's MacBook Pro". */
    val name: String,
    /** `ios`, `android`, `desktop`, `web` — whatever the device registered as. */
    val kind: String?,
    val osVersion: String?,
    val appVersion: String?,
    val lastActiveMillis: Long?,
    /**
     * The device this app is running on.
     *
     * Unlinkable like any other, but asked about differently: it signs *you*
     * out, here, now. The list says so rather than letting someone discover it.
     */
    val isThisDevice: Boolean = false,
    /**
     * The device that owns the account, which the others were linked *to*.
     *
     * The server refuses to unlink it — a phone that scanned nothing has no
     * parent to fall back to — so the row offers no button rather than one that
     * fails.
     */
    val isPrimary: Boolean = false,
) {
    /** Never blank: a device with no name is still one you may need to sign out. */
    val displayName: String
        get() = name.ifBlank { kind?.takeIf { it.isNotBlank() }?.let(::kindLabel) ?: str(S.desktop_unnamed_device) }

    val detail: String
        get() = listOfNotNull(
            kind?.takeIf { it.isNotBlank() }?.let(::kindLabel),
            osVersion?.takeIf { it.isNotBlank() },
            appVersion?.takeIf { it.isNotBlank() }?.let { str(S.desktop_zillit_version, it) },
        ).joinToString(" · ")

    /** Refused by the server, so never offered. */
    val canUnlink: Boolean get() = !isPrimary
}

/** `ios` → `iPhone`, and so on. The wire's words are not the reader's. */
private fun kindLabel(kind: String): String = when (kind.trim().lowercase()) {
    "ios", "iphone" -> "iPhone"
    "ipad" -> "iPad"
    "android" -> "Android"
    "desktop", "mac", "macos", "windows" -> str(S.desktop_device_kind_computer)
    "web", "browser" -> str(S.desktop_device_kind_browser)
    else -> kind.replaceFirstChar { it.uppercase() }
}

/**
 * What `GET device` knows about getting back into this account.
 *
 * Either may be blank: a device registered before recovery keys existed has
 * none, and most people never set an address.
 */
data class RecoveryDetails(
    /** `projects_recovery_code` — typed on a new device to restore the productions. */
    val key: String = "",
    val email: String = "",
) {
    /** Never prints the key: this ends up in logs. */
    override fun toString(): String = "RecoveryDetails(hasKey=${key.isNotBlank()}, hasEmail=${email.isNotBlank()})"
}

/**
 * Everything the account pages read and write.
 *
 * An interface because two of these five calls are irreversible from the user's
 * side — leaving a production needs an admin to let you back in, and unlinking
 * the device you are on signs you out of it — and "does this send what I think"
 * is not a question to answer by reading an HTTP call.
 */
interface AccountRepository {

    /**
     * Writes the profile, or files a request to.
     *
     * [asAdmin] decides which: an admin's `PUT user/profile` takes effect at
     * once, everyone else's `POST user/profile/change-requests` waits for one.
     * Passed in rather than read here because a personal production makes its
     * single member an admin regardless of the flag on their profile.
     */
    suspend fun saveProfile(edit: ProfileEdit, asAdmin: Boolean): ZillitResult<ProfileSaveOutcome>

    /** Where a recovery code is sent if this person loses their devices. */
    suspend fun setRecoveryEmail(email: String): ZillitResult<Unit>

    /**
     * This person's recovery key and the address already on file — the web's
     * Recovery dialog reads both from `GET device`'s `device_setting`.
     */
    suspend fun recoveryDetails(): ZillitResult<RecoveryDetails>

    suspend fun linkedDevices(): ZillitResult<List<LinkedDevice>>

    /** Signs [deviceId] out. The device finds out when it next calls. */
    suspend fun unlinkDevice(deviceId: String): ZillitResult<Unit>

    /**
     * Takes this person off the production.
     *
     * Not a delete and not reversible from here: they keep their account, and
     * an admin has to approve them again through the join queue to return.
     */
    suspend fun leaveProduction(): ZillitResult<Unit>
}
