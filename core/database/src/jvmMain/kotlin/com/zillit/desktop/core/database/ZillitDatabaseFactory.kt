package com.zillit.desktop.core.database

import app.cash.sqldelight.db.SqlDriver
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.security.DatabaseKeyManager
import java.io.File

/**
 * Opens the application database, encrypted with a key held in the OS keychain.
 *
 * This is where the two halves meet: [DatabaseKeyManager] owns key custody
 * (generate once, keep in the OS credential store), and [EncryptedDriverFactory]
 * owns the SQLCipher connection. Neither knows about the other.
 */
class ZillitDatabaseFactory(
    private val keyManager: DatabaseKeyManager,
    private val databasePath: String = defaultPath(),
) {

    /**
     * Opens the database, generating the key on first launch.
     *
     * Fails rather than falling back to plaintext if the keychain is
     * unavailable — an unencrypted database that looks like it worked is the
     * worst outcome available (plan §8.4).
     *
     * One recovery is allowed: a file that exists but will not open with the
     * current key. That is a cache stranded by a key that no longer exists —
     * a runtime change replaces the keychain entry (macOS ACLs items to the
     * binary that made them), and the old file can never be read again by
     * anyone. Everything in it is refetchable, so it is recreated rather than
     * left to force online-only mode on every launch for good. The wipe is
     * gated on the driver's own "open failed" — a keychain that merely errored
     * must not cost the file.
     */
    suspend fun open(): ZillitResult<ZillitDatabase> {
        val first = driver().map { ZillitDatabase(it) }
        if (first is ZillitResult.Success) return first

        val failure = first as ZillitResult.Failure
        val stranded = File(databasePath).exists() &&
            failure.error.technical?.startsWith("open failed") == true
        if (!stranded) return first

        ZillitLog.w(TAG) { "cache unreadable with the current key; recreating it" }
        File(databasePath).delete()
        return driver().map { ZillitDatabase(it) }
    }

    suspend fun driver(): ZillitResult<SqlDriver> = EncryptedDriverFactory.create(
        location = DatabaseLocation.File(databasePath),
        // A fresh array per call: EncryptedDriverFactory zeroes what it is
        // given (see DatabaseKeyProvider.key).
        keyProvider = { keyManager.getOrCreate() },
        schema = ZillitDatabase.Schema,
    )

    /**
     * Discards the key and deletes the file.
     *
     * Sign-out and remote device revoke (plan §8.5). Order matters: dropping
     * the key first means an interrupted wipe still leaves an unreadable
     * database rather than a readable one.
     */
    suspend fun destroy(): ZillitResult<Unit> {
        val result = keyManager.destroy()
        File(databasePath).delete()
        return result
    }

    companion object {
        private const val TAG = "Database"

        fun defaultPath(): String =
            File(System.getProperty("user.home"), ".zillit/zillit.db").absolutePath
    }
}
