package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.network.tokenauth.TokenAuthStore
import com.zillit.desktop.core.security.SecureKey
import com.zillit.desktop.core.security.SecureStore

/**
 * The token session's persistence: the refresh token in the keychain — the
 * `RefreshToken` entry the plan reserved for it (§8.5) — and the mode flag
 * in the device preferences, which describes the backend, not a secret.
 *
 * The phones keep the refresh token under an Android-Keystore key for the
 * same reason the keychain is used here: it is the 90-day credential, and
 * the one thing worth stealing off the disk.
 */
internal class KeychainTokenAuthStore(
    private val secureStore: SecureStore,
    private val preferences: PreferenceStore,
) : TokenAuthStore {

    override suspend fun refreshToken(): String? =
        (secureStore.get(SecureKey.RefreshToken) as? ZillitResult.Success)?.data
            ?.decodeToString()
            ?.takeIf { it.isNotBlank() }

    override suspend fun saveRefreshToken(token: String): Boolean =
        secureStore.put(SecureKey.RefreshToken, token.encodeToByteArray()) is ZillitResult.Success

    override suspend fun clearRefreshToken() {
        secureStore.delete(SecureKey.RefreshToken)
    }

    override suspend fun tokenModeCache(): Boolean = preferences.get(ZillitPreferences.TokenAuthMode)

    override suspend fun cacheTokenMode(enabled: Boolean) {
        preferences.set(ZillitPreferences.TokenAuthMode, enabled)
    }
}
