package com.zillit.desktop.core.database

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.database.sync.SyncDatabase
import com.zillit.desktop.core.security.DatabaseKeyManager
import com.zillit.desktop.core.security.SecureKey
import com.zillit.desktop.core.security.SecureStore
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The durable store's schema policy: forward-only, never rebuilt, never
 * touched by a build that does not understand it. Exercised with a two-version
 * schema written here so the migration path runs today, not only on the day
 * the first real `.sqm` lands.
 */
class MigrateForwardTest {

    private val tempDir: File = Files.createTempDirectory("zillit-sync-db").toFile()
    private val key = ByteArray(EncryptedDriverFactory.KEY_BYTES).also { SecureRandom().nextBytes(it) }

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `a new file gets the current schema and version`() = runTest {
        val driver = open(V1)
        assertEquals(1L, driver.userVersion())
        driver.execute(null, "INSERT INTO note(id, body) VALUES ('n1', 'keep me')", 0)
        driver.close()
    }

    @Test
    fun `an older file is migrated forward and keeps its rows`() = runTest {
        open(V1).also {
            it.execute(null, "INSERT INTO note(id, body) VALUES ('n1', 'keep me')", 0)
            it.close()
        }

        val migrated = open(V2)
        assertEquals(2L, migrated.userVersion())
        assertEquals("keep me", migrated.scalar("SELECT body FROM note WHERE id = 'n1'"))
        // The column the migration added is usable.
        migrated.execute(null, "UPDATE note SET author = 'sam' WHERE id = 'n1'", 0)
        assertEquals("sam", migrated.scalar("SELECT author FROM note WHERE id = 'n1'"))
        migrated.close()
    }

    @Test
    fun `a newer file is refused and left intact`() = runTest {
        open(V2).also {
            it.execute(null, "INSERT INTO note(id, body, author) VALUES ('n1', 'keep me', 'sam')", 0)
            it.close()
        }

        val refused = EncryptedDriverFactory.create(
            location = DatabaseLocation.File(path),
            keyProvider = { ZillitResult.Success(key.copyOf()) },
            schema = V1,
            policy = SchemaPolicy.MigrateForward,
        )
        val failure = assertIs<ZillitResult.Failure>(refused)
        assertTrue(
            failure.error.technical.orEmpty().startsWith(EncryptedDriverFactory.SCHEMA_FAILURE_PREFIX),
            "a schema refusal must not look like an unreadable file: ${failure.error.technical}",
        )

        // Nothing was dropped: the newer build can still read its rows.
        val again = open(V2)
        assertEquals("sam", again.scalar("SELECT author FROM note WHERE id = 'n1'"))
        again.close()
    }

    @Test
    fun `the sync database factory recreates only a file the key cannot open`() = runTest {
        // A file written with another key is unreadable for ever; recreating is
        // the only way back to a working store.
        val otherKey = ByteArray(EncryptedDriverFactory.KEY_BYTES).also { SecureRandom().nextBytes(it) }
        EncryptedDriverFactory.create(
            location = DatabaseLocation.File(path),
            keyProvider = { ZillitResult.Success(otherKey.copyOf()) },
            schema = SyncDatabase.Schema,
            policy = SchemaPolicy.MigrateForward,
        ).let { assertIs<ZillitResult.Success<SqlDriver>>(it).data.close() }

        val opened = SyncDatabaseFactory(DatabaseKeyManager(MemoryStore()), path).open()
        assertIs<ZillitResult.Success<SyncDatabase>>(opened)
        assertEquals(0L, opened.data.outboxQueries.countOpenForUser("u").executeAsOne())
    }

    private val path get() = File(tempDir, "sync.db").absolutePath

    private suspend fun open(schema: SqlSchema<QueryResult.Value<Unit>>): SqlDriver =
        when (
            val result = EncryptedDriverFactory.create(
                location = DatabaseLocation.File(path),
                keyProvider = { ZillitResult.Success(key.copyOf()) },
                schema = schema,
                policy = SchemaPolicy.MigrateForward,
            )
        ) {
            is ZillitResult.Success -> result.data
            is ZillitResult.Failure -> error("open failed: ${result.error.technical}")
        }

    private fun SqlDriver.userVersion(): Long = scalarLong("PRAGMA user_version")

    private fun SqlDriver.scalar(sql: String): String? {
        var value: String? = null
        executeQuery(null, sql, { cursor ->
            if (cursor.next().value) value = cursor.getString(0)
            QueryResult.Unit
        }, 0)
        return value
    }

    private fun SqlDriver.scalarLong(sql: String): Long {
        var value = 0L
        executeQuery(null, sql, { cursor ->
            if (cursor.next().value) value = cursor.getLong(0) ?: 0L
            QueryResult.Unit
        }, 0)
        return value
    }

    /** A keychain that forgets on exit — the test must not touch the real one. */
    private class MemoryStore : SecureStore {
        private val entries = mutableMapOf<SecureKey, ByteArray>()
        override suspend fun get(key: SecureKey): ZillitResult<ByteArray?> =
            ZillitResult.Success(entries[key]?.copyOf())

        override suspend fun put(key: SecureKey, value: ByteArray): ZillitResult<Unit> {
            entries[key] = value.copyOf()
            return ZillitResult.Success(Unit)
        }

        override suspend fun delete(key: SecureKey): ZillitResult<Unit> {
            entries.remove(key)
            return ZillitResult.Success(Unit)
        }

        override suspend fun clear(): ZillitResult<Unit> {
            entries.clear()
            return ZillitResult.Success(Unit)
        }

        override suspend fun isAvailable(): Boolean = true
    }

    /** Version 1: a note has an id and a body. */
    private object V1 : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(null, "CREATE TABLE note (id TEXT NOT NULL PRIMARY KEY, body TEXT NOT NULL)", 0)
            return QueryResult.Unit
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
    }

    /** Version 2: a note also names its author. */
    private object V2 : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 2
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(
                null,
                "CREATE TABLE note (id TEXT NOT NULL PRIMARY KEY, body TEXT NOT NULL, author TEXT)",
                0,
            )
            return QueryResult.Unit
        }

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> {
            if (oldVersion < 2 && newVersion >= 2) {
                driver.execute(null, "ALTER TABLE note ADD COLUMN author TEXT", 0)
            }
            return QueryResult.Unit
        }
    }
}
