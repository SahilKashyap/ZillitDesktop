package com.zillit.desktop.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import java.io.File
import java.util.Properties
import org.sqlite.mc.SQLiteMCSqlCipherConfig

/**
 * Opens the application database with page-level encryption.
 *
 * Backed by `io.github.willena:sqlite-jdbc` — the Xerial driver plus
 * [SQLite3MultipleCiphers](https://utelle.github.io/SQLite3MultipleCiphers/),
 * configured for SQLCipher v4 (AES-256-CBC, HMAC-SHA512, 256k KDF iterations).
 *
 * Chosen over `net.zetetic` (Android-only) and the various `sqlcipher-jdbc`
 * forks (not published to Maven Central) because it is the only candidate that
 * is both on Central and ships natives for every target we need. See
 * `docs/spikes/sqlcipher.md` for the verification.
 *
 * Failures are returned, not thrown: a database that will not open is a
 * first-class product state — it means the user's key is wrong or the file is
 * corrupt, and the app must say so rather than crash on launch.
 */
object EncryptedDriverFactory {

    /**
     * Opens the database, creating the schema if the file is new.
     *
     * **Consumes the key**: the array returned by [keyProvider] is zeroed before
     * this returns, so the provider must hand back a fresh array each call
     * (see [DatabaseKeyProvider.key]).
     */
    suspend fun create(
        location: DatabaseLocation,
        keyProvider: DatabaseKeyProvider,
        schema: app.cash.sqldelight.db.SqlSchema<app.cash.sqldelight.db.QueryResult.Value<Unit>>,
    ): ZillitResult<SqlDriver> {
        val key = when (val result = keyProvider.key()) {
            is ZillitResult.Success -> result.data
            is ZillitResult.Failure -> return result
        }

        return try {
            val url = when (location) {
                is DatabaseLocation.File -> {
                    File(location.path).parentFile?.mkdirs()
                    "jdbc:sqlite:${location.path}"
                }
                // The shared-cache in-memory URL, so multiple connections in a
                // test see the same database.
                DatabaseLocation.InMemory -> JdbcSqliteDriver.IN_MEMORY
            }

            val isNew = location !is DatabaseLocation.File || !File(location.path).exists()

            val driver = JdbcSqliteDriver(url, cipherProperties(key, location))

            // Verify the key actually opened the database. SQLCipher does not
            // fail on connect — it fails on the first read, because a wrong key
            // simply decrypts the header to garbage. Without this probe a bad
            // key surfaces later as an unrelated-looking SQL error.
            //
            // Before the schema work, so a wrong key is reported as a wrong key
            // rather than as a failure to create tables.
            driver.execute(null, "SELECT count(*) FROM sqlite_master", 0)

            if (isNew) schema.create(driver).value else driver.reconcileSchema(schema)

            ZillitResult.Success(driver)
        } catch (@Suppress("TooGenericExceptionCaught") throwable: Throwable) {
            ZillitLog.e(TAG, throwable) { "Could not open the database" }
            ZillitResult.Failure(
                ZillitError.Storage("open failed: ${throwable::class.simpleName}: ${throwable.message}"),
            )
        } finally {
            // The driver has copied what it needs; do not leave the key in the
            // heap (plan §8.4).
            key.fill(0)
        }
    }

    /**
     * SQLCipher v4 parameters.
     *
     * `withRawUnsaltedKey` passes the 32 bytes straight through as the cipher
     * key rather than running a passphrase KDF over them — correct here,
     * because the key already comes from the OS keychain as high-entropy random
     * bytes. Treating it as a passphrase would add cost without adding entropy.
     */
    private fun cipherProperties(key: ByteArray, location: DatabaseLocation): Properties =
        if (location == DatabaseLocation.InMemory) {
            // Nothing is written, so there is nothing to encrypt. Tests that
            // care about encryption must use a file.
            Properties()
        } else {
            SQLiteMCSqlCipherConfig.getV4Defaults()
                .withRawUnsaltedKey(key)
                .build()
                .toProperties()
                .withConcurrencySettings()
        }

    /**
     * What makes a second process survive rather than crash.
     *
     * ## Two apps, one file
     *
     * The database lives at a fixed path in the user's home, so *any* two copies
     * of Zillit share it — an install in `/Applications` and one still sitting in
     * `~/Downloads` are different bundles that macOS will happily run at once.
     * Both then open the same file.
     *
     * With the defaults that is a rollback journal and **no** busy handler: the
     * second writer gets `SQLITE_BUSY` immediately, and the failure surfaced as a
     * hard `SIGTRAP` inside `NativeDB.prepare` rather than as an exception any
     * Kotlin code could catch (crash report 2026-08-12 12:02).
     *
     *  - **WAL** lets readers and one writer work concurrently instead of
     *    excluding each other outright.
     *  - **`busy_timeout`** turns a collision into a wait rather than an instant
     *    failure — without it, WAL alone still fails the moment two writers meet.
     *
     * Set as connection properties rather than as `PRAGMA` statements after
     * opening, because the driver may open more than one connection and a pragma
     * issued on one of them binds only that one. `busy_timeout` is per
     * connection; WAL is a property of the file and persists in its header.
     *
     * The single-instance guard in the app is the other half of this. This is the
     * half that has to hold when the guard is bypassed — two different bundles,
     * or a build run straight from the command line.
     */
    private fun Properties.withConcurrencySettings(): Properties = apply {
        setProperty("journal_mode", "WAL")
        setProperty("busy_timeout", BUSY_TIMEOUT_MS.toString())
    }

    /**
     * How long a blocked statement waits before giving up.
     *
     * Long enough to outlast another process's write — which here is a cache
     * refill of a few hundred rows — and short enough that a genuinely stuck
     * lock still surfaces as an error rather than hanging the screen.
     */
    private const val BUSY_TIMEOUT_MS = 5_000

    const val KEY_BYTES = 32
    const val TAG = "Database"
}

/**
 * Brings an existing database up to the current schema.
 *
 * ## Rebuild, not migrate
 *
 * Every table here is a cache — profile, project, crew, tools, notices and
 * mail, all re-fetchable from the server. Nothing in it is the only copy of
 * anything: tokens and keys live in the OS keychain, not the database.
 *
 * So when the schema changes, the cheapest correct thing is to throw the cache
 * away and let it refill. The cost is one slower load; the alternative is a set
 * of hand-written migrations whose failure mode is a corrupt cache that reads
 * as a server bug. When something non-recoverable is stored here — an outbox,
 * unsent drafts — this must become real forward-only migrations (`.sqm` files,
 * with `verifyMigrations` turned on).
 *
 * ## Why the schema is compared, not versioned
 *
 * The obvious check is `SqlSchema.version`, and it does not work: SQLDelight
 * derives that from the number of migration files, so with none it is pinned at
 * `1` no matter how many tables change. A version check therefore fires exactly
 * once — on the transition from an unversioned file — and silently stops
 * working forever after, leaving a database missing whatever column was added
 * most recently.
 *
 * Comparing the actual `CREATE` statements against a freshly built schema has
 * no such trap: it notices every change, including ones nobody remembered to
 * record. It costs one in-memory database per launch.
 */
private fun SqlDriver.reconcileSchema(
    schema: app.cash.sqldelight.db.SqlSchema<app.cash.sqldelight.db.QueryResult.Value<Unit>>,
) {
    val expected = freshSchemaSql(schema)
    if (schemaSql() == expected) return

    ZillitLog.i(EncryptedDriverFactory.TAG) { "cache schema changed, rebuilding" }

    dropAllTables()
    schema.create(this).value
}

/** The `CREATE` statements a brand-new database would have. */
private fun freshSchemaSql(
    schema: app.cash.sqldelight.db.SqlSchema<app.cash.sqldelight.db.QueryResult.Value<Unit>>,
): List<String> {
    val probe = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    return try {
        schema.create(probe).value
        probe.schemaSql()
    } finally {
        probe.close()
    }
}

/** Every object's definition, ordered so two equal schemas compare equal. */
private fun SqlDriver.schemaSql(): List<String> {
    val statements = mutableListOf<String>()
    executeQuery(null, "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY name", { cursor ->
        while (cursor.next().value) {
            cursor.getString(0)?.let(statements::add)
        }
        app.cash.sqldelight.db.QueryResult.Unit
    }, 0)
    return statements
}

private fun SqlDriver.dropAllTables() {
    val tables = mutableListOf<String>()
    executeQuery(null, "SELECT name FROM sqlite_master WHERE type = \'table\'", { cursor ->
        while (cursor.next().value) {
            cursor.getString(0)?.let(tables::add)
        }
        app.cash.sqldelight.db.QueryResult.Unit
    }, 0)

    tables
        // SQLite's own bookkeeping tables cannot be dropped, and trying throws.
        .filterNot { it.startsWith("sqlite_") }
        .forEach { execute(null, "DROP TABLE IF EXISTS \"$it\"", 0) }
}
