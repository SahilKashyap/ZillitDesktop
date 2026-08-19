package com.zillit.desktop.feature.email.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * Addresses blind-copied on every message this user sends.
 *
 * They live on the user's *profile* (`bcc[].email_address` — Android
 * `UserData.bcc`, `JoinProjectResponse.kt:152`), not on the mail service, and
 * the server takes the whole list on every write rather than an add or a
 * remove (Android `EmailPresetPage.kt:121-124` and `:193-196` both send the
 * full list; `SettingPageVM.kt:114-118`). So the contract here is "replace",
 * and the screen does the set arithmetic.
 */
interface BccPresetRepository {

    /** The current list, lowest-cased as the phone stores them. */
    suspend fun presets(): ZillitResult<List<String>>

    /** Replaces the whole list. */
    suspend fun save(addresses: List<String>): ZillitResult<Unit>
}

/**
 * Whether [candidate] can be added to [existing].
 *
 * The same three checks as Android's `EmailPresetPage.validate()`
 * (`EmailPresetPage.kt:183-191`): not blank, shaped like an address, not
 * already there. Case-insensitive because the phone lower-cases on save.
 */
fun bccPresetError(candidate: String, existing: List<String>): BccPresetError? {
    val trimmed = candidate.trim()
    return when {
        trimmed.isEmpty() -> BccPresetError.Empty
        !trimmed.looksLikeAddress() -> BccPresetError.Invalid
        existing.any { it.equals(trimmed, ignoreCase = true) } -> BccPresetError.Duplicate
        else -> null
    }
}

enum class BccPresetError {
    Empty,
    Invalid,
    Duplicate,
    ;

    /** Android's strings, `res/values/strings.xml`. */
    val message: String
        get() = when (this) {
            Empty -> "Please add email to continue"
            Invalid -> "Please enter a valid email"
            Duplicate -> "Email already in preset list."
        }
}

/**
 * The loosest useful shape: something, an `@`, something with a dot.
 *
 * Android uses `Patterns.EMAIL_ADDRESS`; the web uses a similar regex. Neither
 * is a full RFC 5322 check and nor is this — the server has the final say.
 */
fun String.looksLikeAddress(): Boolean = ADDRESS_SHAPE.matches(trim())

private val ADDRESS_SHAPE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
