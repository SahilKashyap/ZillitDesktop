package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.tokenauth.TokenAuthStore
import com.zillit.desktop.core.security.SecureKey
import com.zillit.desktop.core.security.SecureStore

/**
 * The token session's persistence: the refresh token in the keychain, the
 * `RefreshToken` entry the plan reserved for it (§8.5), and nothing else.
 * The mode flag it used to cache went with `token_auth_enabled` — the mode
 * is now decided by `POST /session/device`, which a stored refresh token
 * already short-circuits on a cold start.
 *
 * The phones keep the refresh token under an Android-Keystore key for the
 * same reason the keychain is used here: it is the 90-day credential, and
 * the one thing worth stealing off the disk.
 */
internal class KeychainTokenAuthStore(
    private val secureStore: SecureStore,
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

}
