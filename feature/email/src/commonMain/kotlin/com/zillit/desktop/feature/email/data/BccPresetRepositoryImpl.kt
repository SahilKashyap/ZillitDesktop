package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.jsonBody
import com.zillit.desktop.feature.email.domain.BccPresetRepository
import com.zillit.desktop.feature.email.domain.MailboxDirectory
import com.zillit.desktop.feature.email.domain.MailboxScope
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * BCC presets, on the *core* service — they are a profile field.
 *
 * Read: the profile's `bcc` list. Write: `PATCH user/update-bcc-preset`
 * (Android `ApiUrl.kt:115`, `PATCH_BCC_UPDATE = "${USER}update-bcc-preset"`;
 * called from `SettingPageVM.updateBccPreset`, `SettingPageVM.kt:114-136`;
 * web `emailApi.js:136-144`).
 */
class BccPresetRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    private val profile: MailboxProfileSource = MailboxProfileSource(apiClient, config),
    /**
     * The shared Accounts mailbox keeps its presets on the project
     * (`accounts_mail_box_detail.bcc`, `PATCH project/accounts-mail-box/bcc`)
     * — the web's `BccPresetModal` reads and writes there while it is active.
     */
    private val scope: MailboxScope = MailboxScope.Personal,
    private val directory: MailboxDirectory? = null,
) : BccPresetRepository {

    override suspend fun presets(): ZillitResult<List<String>> =
        if (scope.isAccountsActive() && directory != null) {
            directory.accounts().map { it?.bccPresets.orEmpty() }
        } else {
            profile.profile().map { it.bccPresets }
        }

    /**
     * `{"bcc": [{"email_address": "…"}, …]}` — the whole list, every time.
     *
     * Android sends a `UserData(bcc = list)` through Gson, which serialises
     * that one populated field (`EmailPresetPage.kt:124`, `:196`); the web
     * sends `{ bcc }` with the same element shape (`BccPresetModal.jsx:60`,
     * `:196`). Addresses are lower-cased as the phone does on add
     * (`EmailPresetPage.kt:195`).
     */
    override suspend fun save(addresses: List<String>): ZillitResult<Unit> =
        if (scope.isAccountsActive() && directory != null) {
            directory.setAccountsBccPresets(addresses.map { it.trim().lowercase() })
        } else {
            saveOnProfile(addresses)
        }

    private suspend fun saveOnProfile(addresses: List<String>): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Patch,
            url = "${config.apiV2()}user/update-bcc-preset",
            module = RequestModule.ProjectUser,
            body = jsonBody(bccBody(addresses)),
        ).map { }
}

/** The PATCH body; top level so a test can pin the shape without a client. */
internal fun bccBody(addresses: List<String>) = buildJsonObject {
    putJsonArray("bcc") {
        addresses.forEach { address ->
            add(buildJsonObject { put("email_address", address.trim().lowercase()) })
        }
    }
}
