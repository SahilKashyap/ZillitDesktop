package com.zillit.desktop.feature.email.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * Where incoming mail is copied to, outside Zillit.
 *
 * One address per user — the backend contract, and why the phone's sheet has a
 * single field and a Remove button rather than a list
 * (`GeneralSettingsActivity.kt:109-113`). Android
 * `ForwardingSettingResponse` (`ResponseDto.kt:99-104`).
 */
data class EmailForwarding(
    val address: String,
    val enabled: Boolean = true,
)

interface EmailForwardingRepository {

    /**
     * The current setting, or null when none is configured.
     *
     * Null rather than a failure: "nothing set up yet" is the ordinary first
     * state, and Android treats a failed GET the same way
     * (`SettingsViewModel.kt:62-65`).
     */
    suspend fun current(): ZillitResult<EmailForwarding?>

    /** Creates or replaces — one endpoint does both (`SettingsViewModel.kt:69`). */
    suspend fun save(address: String): ZillitResult<EmailForwarding>

    suspend fun remove(): ZillitResult<Unit>
}
