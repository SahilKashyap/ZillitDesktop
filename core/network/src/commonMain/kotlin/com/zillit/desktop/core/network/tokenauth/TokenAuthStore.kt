package com.zillit.desktop.core.network.tokenauth

/**
 * What the token session keeps across process starts.
 *
 * Only the device REFRESH token — the long-lived credential — is persisted,
 * and the host keeps it in the keychain. Access tokens live in memory by
 * contract and are never written here.
 *
 * There is no mode cache any more: the mode is decided by
 * `POST /session/device` itself, and a stored refresh token already
 * short-circuits that call on a cold start.
 */
interface TokenAuthStore {
    suspend fun refreshToken(): String?

    /** False when the write did not land — the caller then clears, never uses. */
    suspend fun saveRefreshToken(token: String): Boolean

    suspend fun clearRefreshToken()
}
