package com.zillit.desktop.core.network.tokenauth

/**
 * What the token session keeps across process starts.
 *
 * Only the device REFRESH token — the long-lived credential — is persisted,
 * and the host keeps it in the keychain. Access tokens live in memory by
 * contract and are never written here. The mode flag is cached so a cold
 * start knows which credential to send before any configuration call has
 * answered (the phones' `tokenAuthEnabledCache`).
 */
interface TokenAuthStore {
    suspend fun refreshToken(): String?

    /** False when the write did not land — the caller then clears, never uses. */
    suspend fun saveRefreshToken(token: String): Boolean

    suspend fun clearRefreshToken()

    suspend fun tokenModeCache(): Boolean

    suspend fun cacheTokenMode(enabled: Boolean)
}
