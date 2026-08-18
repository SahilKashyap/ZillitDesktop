package com.zillit.desktop.core.database

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.database.sync.SyncDatabase
import com.zillit.desktop.core.security.DatabaseKeyManager
import java.io.File

/**
 * Opens the durable store — the outbox and unsent drafts — encrypted with the
 * same keychain key as the cache but living in its own file, because the two
 * need opposite schema policies (see [SchemaPolicy]).
 *
 * ## What is and is not recovered
 *
 * - **Key gone.** Sign-out clears the keychain (by design: nothing on the disk
 *   is readable after it), and a runtime change can re-ACL the entry. A file
 *   that will not open with the current key is unreadable by anyone, for
 *   ever, so it is recreated — after a warning in the log, because whatever
 *   was in it is lost. This is why signing out with unsent changes asks first.
 * - **Schema newer than this build, or a migration that failed.** The file is
 *   fine and the words in it are still the user's; it is left untouched and
 *   the app runs without offline support until a build that can read it. That
 *   is the whole reason this store is not the cache.
 */
class SyncDatabaseFactory(
    private val keyManager: DatabaseKeyManager,
    private val databasePath: String = defaultPath(),
) {

    suspend fun open(): ZillitResult<SyncDatabase> {
        val first = driver().map { SyncDatabase(it) }
        if (first is ZillitResult.Success) return first

        val failure = first as ZillitResult.Failure
        val stranded = File(databasePath).exists() &&
            failure.error.technical?.startsWith(EncryptedDriverFactory.OPEN_FAILURE_PREFIX) == true
        if (!stranded) return first

        ZillitLog.w(TAG) { "durable store unreadable with the current key; recreating it — unsent work is lost" }
        File(databasePath).delete()
        return driver().map { SyncDatabase(it) }
    }

    private suspend fun driver() = EncryptedDriverFactory.create(
        location = DatabaseLocation.File(databasePath),
        keyProvider = { keyManager.getOrCreate() },
        schema = SyncDatabase.Schema,
        policy = SchemaPolicy.MigrateForward,
    )

    companion object {
        private const val TAG = "SyncDatabase"

        fun defaultPath(): String =
            File(System.getProperty("user.home"), ".zillit/zillit-sync.db").absolutePath
    }
}
