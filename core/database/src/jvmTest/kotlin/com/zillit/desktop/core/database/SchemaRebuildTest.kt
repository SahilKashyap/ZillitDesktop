package com.zillit.desktop.core.database

import app.cash.sqldelight.db.QueryResult
import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opening a database written by an older build.
 *
 * The mail cache added tables to a schema that had already shipped, and the
 * factory only ran `schema.create` for brand-new files — so an existing install
 * would have opened fine and then failed on `no such table: cachedEmail`. This
 * is that upgrade path.
 */
class SchemaRebuildTest {

    private val dir: File = Files.createTempDirectory("zillit-schema").toFile()
    private val path = File(dir, "cache.db").absolutePath

    private val key = object : DatabaseKeyProvider {
        override suspend fun key() = ZillitResult.Success(ByteArray(EncryptedDriverFactory.KEY_BYTES) { 7 })
    }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private suspend fun open() = EncryptedDriverFactory.create(
        location = DatabaseLocation.File(path),
        keyProvider = key,
        schema = ZillitDatabase.Schema,
    )

    private fun app.cash.sqldelight.db.SqlDriver.tableExists(name: String): Boolean =
        executeQuery(
            null,
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='$name'",
            { cursor -> QueryResult.Value(cursor.next().value && (cursor.getLong(0) ?: 0L) > 0L) },
            0,
        ).value

    @Test
    fun `a database written before the mail tables gains them on open`() = runTest {
        // Stand in for the shipped schema: a file with only some of the tables
        // and no recorded version, which is what an older build left behind.
        val first = (open() as ZillitResult.Success).data
        first.execute(null, "DROP TABLE cachedEmail", 0)
        first.execute(null, "PRAGMA user_version = 0", 0)
        first.close()

        val reopened = (open() as ZillitResult.Success).data

        assertTrue(reopened.tableExists("cachedEmail"), "the mail cache should have been rebuilt")
        assertTrue(reopened.tableExists("cachedProject"), "and the rest of the schema with it")
        reopened.close()
    }

    @Test
    fun `an up-to-date database is left alone`() = runTest {
        val first = (open() as ZillitResult.Success).data
        first.execute(null, "INSERT INTO appMeta(key, value) VALUES ('probe', 'kept')", 0)
        first.close()

        val reopened = (open() as ZillitResult.Success).data
        val kept = reopened.executeQuery(
            null,
            "SELECT value FROM appMeta WHERE key = 'probe'",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
            0,
        ).value

        assertEquals("kept", kept, "a matching schema version must not wipe the cache")
        reopened.close()
    }

    @Test
    fun `a database missing a column added later gains it`() = runTest {
        // The case a version check cannot catch: SQLDelight pins `Schema.version`
        // at 1 until migration files exist, so adding a column bumps nothing.
        // Comparing the actual CREATE statements does notice.
        val first = (open() as ZillitResult.Success).data
        first.execute(null, "DROP TABLE cachedProjectUser", 0)
        first.execute(
            null,
            "CREATE TABLE cachedProjectUser (userId TEXT NOT NULL PRIMARY KEY, projectId TEXT)",
            0,
        )
        first.close()

        val reopened = (open() as ZillitResult.Success).data
        val columns = reopened.executeQuery(
            null,
            "SELECT count(*) FROM pragma_table_info('cachedProjectUser') WHERE name = 'keepNamePrivate'",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
            0,
        ).value

        assertEquals(1L, columns, "the new column was never added")
        reopened.close()
    }
}
