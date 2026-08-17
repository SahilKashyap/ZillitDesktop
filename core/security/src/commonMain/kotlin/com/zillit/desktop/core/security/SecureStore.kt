package com.zillit.desktop.core.security

import com.zillit.desktop.core.common.ZillitResult

/**
 * OS-backed storage for secrets (plan §8.4).
 *
 * Everything here is protected by the operating system's own credential
 * store — macOS Keychain, Windows Credential Manager, Freedesktop Secret
 * Service — rather than by a file the app writes. That matters most for the
 * database key: a key kept next to the ciphertext protects nothing.
 *
 * Nothing in this store may ever be written to `DataStore`, a preferences file,
 * or a log. `ZillitLog` redacts the obvious shapes, but the rule is "never send
 * it there", not "trust the redactor".
 */
interface SecureStore {

    /** Returns null when the entry does not exist. */
    suspend fun get(key: SecureKey): ZillitResult<ByteArray?>

    suspend fun put(key: SecureKey, value: ByteArray): ZillitResult<Unit>

    suspend fun delete(key: SecureKey): ZillitResult<Unit>

    /**
     * Removes every entry this app owns.
     *
     * Called on sign-out and on remote device revoke (plan §8.5) — at which
     * point the local database is unreadable by construction, because its key
     * is gone.
     */
    suspend fun clear(): ZillitResult<Unit>

    /** Whether a usable OS backend exists on this machine. */
    suspend fun isAvailable(): Boolean
}

/**
 * Every secret the app stores, as a closed set.
 *
 * An enum rather than free-form strings so a typo cannot silently create a
 * second entry that never gets cleared on sign-out — which is how credentials
 * outlive the session that owned them.
 */
enum class SecureKey(val account: String) {
    /** 32-byte SQLCipher key for the local database. */
    DatabaseKey("database-key"),

    /** Short-lived API access token. */
    AccessToken("access-token"),

    /** Single-use refresh token (plan §8.5). */
    RefreshToken("refresh-token"),

    /** Per-device identity keypair, established at registration. */
    DeviceKey("device-key"),

    /** AES key for the legacy header scheme, fetched at runtime (plan §8.3). */
    ApiEncryptionKey("api-encryption-key"),

    /** IV for the legacy header scheme. */
    ApiIvKey("api-iv-key"),
    ;

    companion object {
        /**
         * Service name under which entries are filed.
         *
         * Visible to the user in Keychain Access and Credential Manager, so it
         * is a product name rather than a package name.
         */
        const val SERVICE = "Zillit Desktop"
    }
}
