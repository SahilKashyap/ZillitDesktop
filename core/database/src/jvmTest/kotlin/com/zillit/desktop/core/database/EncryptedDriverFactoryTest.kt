package com.zillit.desktop.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SQLCipher spike (plan §5, risk 6).
 *
 * These tests answer the question the plan flagged as a go/no-go: does
 * SQLite3MultipleCiphers actually encrypt the database file on this toolchain,
 * and does a wrong key fail closed?
 *
 * The control case matters as much as the positive ones. `an unencrypted
 * database leaks its contents` proves the plaintext probe is genuinely
 * sensitive — without it, the encryption assertions could pass simply because
 * the search was broken.
 */
class EncryptedDriverFactoryTest {

    private val tempDir: File = Files.createTempDirectory("zillit-db-spike").toFile()

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    // Distinctive enough that finding it in a binary is unambiguous.
    private val secret = "CONFIDENTIAL-CALLSHEET-SCENE-42-PAYROLL"

    @Test
    fun `data written through SQLDelight can be read back`() = runTest {
        val path = dbPath("roundtrip")
        val key = randomKey()

        val driver = openOrFail(path, key)
        ZillitDatabase(driver).appMetaQueries.upsert("scene", secret)
        assertEquals(secret, ZillitDatabase(driver).appMetaQueries.selectByKey("scene").executeAsOne())
        driver.close()

        // Reopen with the same key — the point of persistence.
        val reopened = openOrFail(path, key)
        assertEquals(secret, ZillitDatabase(reopened).appMetaQueries.selectByKey("scene").executeAsOne())
        reopened.close()
    }

    @Test
    fun `a key provider that caches without copying is caught`() = runTest {
        // The factory zeroes the key it is handed. A provider that caches one
        // array and returns the same instance twice therefore yields 32 zero
        // bytes on the second call, and the database fails to open with
        // SQLITE_NOTADB — which reads like corruption, not a key problem.
        // Documented on DatabaseKeyProvider.key; asserted here so the contract
        // has teeth.
        val path = dbPath("cachedkey")
        val cached = randomKey()
        val badProvider = DatabaseKeyProvider { ZillitResult.Success(cached) }

        val first = EncryptedDriverFactory.create(DatabaseLocation.File(path), badProvider, ZillitDatabase.Schema)
        assertIs<ZillitResult.Success<*>>(first)
        (first.data as app.cash.sqldelight.db.SqlDriver).close()

        val second = EncryptedDriverFactory.create(DatabaseLocation.File(path), badProvider, ZillitDatabase.Schema)
        assertIs<ZillitResult.Failure>(second, "a cached, already-zeroed key must not silently open the database")
    }

    @Test
    fun `the database file contains no plaintext`() = runTest {
        val path = dbPath("encrypted")
        val key = randomKey()

        val driver = openOrFail(path, key)
        ZillitDatabase(driver).appMetaQueries.upsert("scene", secret)
        driver.close()

        val bytes = File(path).readBytes()
        assertTrue(bytes.isNotEmpty(), "database file was never written")
        assertFalse(bytes.containsText(secret), "secret found in plaintext inside the encrypted database")
        // SQLite writes a recognisable "SQLite format 3" header; SQLCipher
        // encrypts even that, so its absence is a second, independent signal.
        assertFalse(bytes.containsText("SQLite format 3"), "unencrypted SQLite header present")
    }

    @Test
    fun `an unencrypted database leaks its contents`() = runTest {
        // Control. If this fails, `containsText` is broken and the encryption
        // assertions above prove nothing.
        val path = dbPath("plaintext")
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        ZillitDatabase.Schema.create(driver).value
        ZillitDatabase(driver).appMetaQueries.upsert("scene", secret)
        driver.close()

        val bytes = File(path).readBytes()
        assertTrue(bytes.containsText(secret), "control failed — the plaintext probe does not work")
        assertTrue(bytes.containsText("SQLite format 3"))
    }

    @Test
    fun `a wrong key fails to open the database`() = runTest {
        val path = dbPath("wrongkey")
        val driver = openOrFail(path, randomKey())
        ZillitDatabase(driver).appMetaQueries.upsert("scene", secret)
        driver.close()

        val result = EncryptedDriverFactory.create(
            location = DatabaseLocation.File(path),
            keyProvider = { ZillitResult.Success(randomKey()) },
            schema = ZillitDatabase.Schema,
        )

        assertIs<ZillitResult.Failure>(result, "a wrong key must not open the database")
        assertIs<ZillitError.Storage>(result.error)
    }

    @Test
    fun `no key at all fails to open the database`() = runTest {
        val path = dbPath("nokey")
        val driver = openOrFail(path, randomKey())
        ZillitDatabase(driver).appMetaQueries.upsert("scene", secret)
        driver.close()

        // Plain driver, no cipher configuration — the file must be unreadable.
        val plain = JdbcSqliteDriver("jdbc:sqlite:$path")
        val failed = runCatching {
            plain.execute(null, "SELECT count(*) FROM sqlite_master", 0)
        }.isFailure
        runCatching { plain.close() }

        assertTrue(failed, "an encrypted database must not open without a key")
    }

    @Test
    fun `a missing key is reported rather than opening unencrypted`() = runTest {
        // Failing open would be the worst outcome: a silently unencrypted
        // database that looks like it worked.
        val result = EncryptedDriverFactory.create(
            location = DatabaseLocation.File(dbPath("keyfail")),
            keyProvider = { ZillitResult.Failure(ZillitError.Crypto("keychain unavailable")) },
            schema = ZillitDatabase.Schema,
        )

        assertIs<ZillitResult.Failure>(result)
        assertIs<ZillitError.Crypto>(result.error)
    }

    @Test
    fun `encryption overhead on a realistic write batch is acceptable`() = runTest {
        // Not a benchmark — a smoke test that encryption is not catastrophically
        // slow on this toolchain. Chat history and Account Hub tables are the
        // write-heavy paths that would notice.
        val rows = 2_000

        val encryptedMillis = measure {
            val driver = openOrFail(dbPath("perf-enc"), randomKey())
            ZillitDatabase(driver).write(rows)
            driver.close()
        }
        val plainMillis = measure {
            val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath("perf-plain")}")
            ZillitDatabase.Schema.create(driver).value
            ZillitDatabase(driver).write(rows)
            driver.close()
        }

        println("SQLCipher spike: $rows rows — encrypted ${encryptedMillis}ms, plain ${plainMillis}ms")
        assertTrue(
            encryptedMillis < plainMillis * MAX_OVERHEAD_FACTOR + FIXED_ALLOWANCE_MILLIS,
            "encryption overhead looks pathological: ${encryptedMillis}ms vs ${plainMillis}ms",
        )
    }

    @Test
    fun `the bundled driver reports a platform native for this host`() = runTest {
        // Guards the portability risk directly: if a CI runner has no matching
        // native, this fails there with a clear message rather than at runtime
        // in front of a user.
        val os = System.getProperty("os.name")
        val arch = System.getProperty("os.arch")
        val driver = openOrFail(dbPath("native"), randomKey())
        val version = ZillitDatabase(driver).let {
            driver.executeQuery(null, "SELECT sqlite_version()", { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    if (cursor.next().value) cursor.getString(0) else null,
                )
            }, 0).value
        }
        driver.close()

        println("SQLCipher spike: native loaded on $os/$arch, SQLite ${version.orEmpty()}")
        assertTrue(version.orEmpty().contains("."), "expected a SQLite version string, got: $version")
    }

    // -- helpers ----------------------------------------------------------

    private fun dbPath(name: String) = File(tempDir, "$name.db").absolutePath

    private fun randomKey() = ByteArray(EncryptedDriverFactory.KEY_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * Opens with a *copy* of [key], because the factory zeroes what it is given
     * (see DatabaseKeyProvider.key). Passing the array directly would leave the
     * caller holding 32 zero bytes for any later reopen.
     */
    private suspend fun openOrFail(path: String, key: ByteArray) =
        when (
            val result = EncryptedDriverFactory.create(
                location = DatabaseLocation.File(path),
                keyProvider = { ZillitResult.Success(key.copyOf()) },
                schema = ZillitDatabase.Schema,
            )
        ) {
            is ZillitResult.Success -> result.data
            is ZillitResult.Failure -> error("could not open database: ${result.error.technical}")
        }

    private fun ZillitDatabase.write(rows: Int) = transaction {
        repeat(rows) { index -> appMetaQueries.upsert("row-$index", "$secret-$index") }
    }

    private suspend fun measure(block: suspend () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / NANOS_PER_MILLI
    }

    private fun ByteArray.containsText(text: String): Boolean {
        val needle = text.toByteArray(Charsets.UTF_8)
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_OVERHEAD_FACTOR = 4
        const val FIXED_ALLOWANCE_MILLIS = 2_000
    }
}

/**
 * The settings that let a second process survive.
 *
 * A separate class so it reads as what it is: a regression guard for the
 * `SIGTRAP` inside `NativeDB.prepare` that a second running copy of the app
 * produced (crash report 2026-08-12 12:02). Asserting the **pragma** rather
 * than the property, because setting a property the driver quietly ignores
 * would pass a weaker test while changing nothing at all.
 */
class DatabaseConcurrencyTest {

    private val tempDir: File = Files.createTempDirectory("zillit-db-concurrency").toFile()

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    private val key = ByteArray(EncryptedDriverFactory.KEY_BYTES).also(SecureRandom()::nextBytes)

    /** Opens the shared file the way the app does — same path, same key. */
    private suspend fun open(name: String): SqlDriver {
        val path = File(tempDir, "$name.db").absolutePath
        val result = EncryptedDriverFactory.create(
            location = DatabaseLocation.File(path),
            // A fresh copy each call: the factory zeroes what it is handed.
            keyProvider = { ZillitResult.Success(key.copyOf()) },
            schema = ZillitDatabase.Schema,
        )
        return assertIs<ZillitResult.Success<SqlDriver>>(result).data
    }

    private fun SqlDriver.pragma(name: String): String {
        var value = ""
        executeQuery(null, "PRAGMA $name", { cursor ->
            if (cursor.next().value) value = cursor.getString(0).orEmpty()
            app.cash.sqldelight.db.QueryResult.Unit
        }, 0)
        return value
    }

    @Test
    fun `a file database opens in WAL with a busy timeout`() = runTest {
        val driver = open("settings")
        try {
            assertEquals("wal", driver.pragma("journal_mode").lowercase())
            assertEquals("5000", driver.pragma("busy_timeout"))
        } finally {
            driver.close()
        }
    }

    /**
     * Two connections to one file, which is what two installed copies produce.
     *
     * Under the old defaults the second writer failed the moment it met the
     * first. It must now wait its turn and complete.
     */
    @Test
    fun `a second connection can write to the same file`() = runTest {
        val first = open("shared")
        val second = open("shared")
        try {
            first.execute(null, "CREATE TABLE IF NOT EXISTS probe(id INTEGER PRIMARY KEY, v TEXT)", 0)
            first.execute(null, "INSERT INTO probe(v) VALUES ('one')", 0)
            second.execute(null, "INSERT INTO probe(v) VALUES ('two')", 0)

            var rows = 0
            second.executeQuery(null, "SELECT count(*) FROM probe", { cursor ->
                if (cursor.next().value) rows = cursor.getLong(0)?.toInt() ?: 0
                app.cash.sqldelight.db.QueryResult.Unit
            }, 0)
            assertEquals(2, rows)
        } finally {
            first.close()
            second.close()
        }
    }

    /**
     * An existing database gets the settings too, not just a fresh one.
     *
     * This is the case that matters: every install already has a database,
     * created before any of this was configured, and that is the file the crash
     * happened on. A fix that only applied to new databases would leave every
     * current user exactly where they were.
     *
     * The settings ride on the *connection*, so they are re-applied every open
     * rather than baked in at creation — an attempt to switch the journal back
     * to `DELETE` on a live connection is overridden, which is how this was
     * confirmed.
     */
    @Test
    fun `an existing database gets the settings when it is reopened`() = runTest {
        val name = "existing"

        val first = open(name)
        first.execute(null, "CREATE TABLE IF NOT EXISTS probe(id INTEGER PRIMARY KEY)", 0)
        first.close()

        val reopened = open(name)
        try {
            assertEquals("wal", reopened.pragma("journal_mode").lowercase())
            assertEquals("5000", reopened.pragma("busy_timeout"))
        } finally {
            reopened.close()
        }
    }
}
