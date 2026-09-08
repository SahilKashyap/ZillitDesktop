package com.zillit.desktop.core.remoteconfig

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Third-party credentials the backend hands out after sign-in.
 *
 * `GET api/v2/configuration` (Android `CommonApis.getDeviceConfiguration`)
 * returns the Maps, Places, AWS and translation credentials that features need
 * to talk to services other than Zillit. They arrive AES-encrypted with the
 * header key and are decrypted client-side.
 *
 * ## Why this is not the header key's source
 *
 * The call is made with a `moduledata` header carrying device, project **and**
 * user — built with the header key — and its payload is decrypted with that
 * same key. It cannot bootstrap the key it depends on. The header key comes
 * from `zillit.properties` (see `AppConfig.headerKey`).
 *
 * ## Lifetime
 *
 * Fetched after project selection, held in memory, dropped on sign-out. Nothing
 * is written to disk. The Android client puts these in `SharedPref`, which is
 * plaintext on the device; keeping them in memory means a stolen machine yields
 * nothing and a restart simply refetches. It also avoids five keychain writes
 * per sign-in, which on macOS is a plausible way to provoke repeated
 * authorisation prompts.
 */
interface RemoteConfigRepository {

    /** Null until the first successful fetch, and again after sign-out. */
    val credentials: StateFlow<RemoteCredentials?>

    /**
     * Fetches and decrypts.
     *
     * Requires a selected project: the request carries project and user in its
     * header, and the server rejects it otherwise.
     */
    suspend fun refresh(): ZillitResult<RemoteCredentials>

    /**
     * The bundle, fetching it if it has not arrived yet.
     *
     * The at-open [refresh] is one HTTP call on a fresh machine's first
     * minute, and its failure was deliberately non-fatal — which quietly
     * became "attachments are broken until the app restarts", because
     * nothing ever asked again. Every consumer that *needs* the bundle
     * calls this instead of reading [credentials] directly, so one missed
     * fetch costs one retry, not the session.
     */
    suspend fun current(): RemoteCredentials?

    /** Drops everything held. Called on sign-out. */
    fun clear()
}

/**
 * The decrypted credentials.
 *
 * Every field is nullable because the server omits what a deployment has not
 * configured, and a feature asking for a credential that was never issued
 * should get a clear null rather than an empty string that fails later inside a
 * third-party SDK.
 */
data class RemoteCredentials(
    val googleMapsKey: String? = null,
    val googlePlacesKey: String? = null,
    val awsAccessKey: String? = null,
    val awsSecretKey: String? = null,
    val chatGptTranslationToken: String? = null,
    /** Not encrypted server-side, and not a secret — the public download page. */
    val appDownloadUrl: String? = null,
    /**
     * `token_auth_enabled`: whether requests carry a Bearer token instead of
     * the encrypted `moduledata` header. Not a credential either; it rides
     * the same answer because that is where the phones read it.
     */
    val tokenAuthEnabled: Boolean = false,
) {
    /**
     * Never prints a credential.
     *
     * These reach log lines the moment anyone debugs a Maps or upload failure,
     * and the generated `toString` would put an AWS secret key in a file on the
     * user's disk. `ZillitLog.redact` is the second line of defence, not the
     * first.
     */
    override fun toString(): String = "RemoteCredentials(" +
        "googleMapsKey=${mask(googleMapsKey)}, googlePlacesKey=${mask(googlePlacesKey)}, " +
        "awsAccessKey=${mask(awsAccessKey)}, awsSecretKey=${mask(awsSecretKey)}, " +
        "chatGptTranslationToken=${mask(chatGptTranslationToken)}, " +
        "appDownloadUrl=$appDownloadUrl, tokenAuthEnabled=$tokenAuthEnabled)"

    private fun mask(value: String?) = if (value == null) "null" else "***"
}
