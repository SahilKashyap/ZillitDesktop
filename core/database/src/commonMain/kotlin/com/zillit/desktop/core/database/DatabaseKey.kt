package com.zillit.desktop.core.database

import com.zillit.desktop.core.common.ZillitResult

/**
 * Supplies the database encryption key.
 *
 * The key lives in the OS keychain and is never written beside the database
 * (plan §8.4) — a key stored next to the ciphertext protects nothing. This
 * indirection is what lets `core:security` own key custody without
 * `core:database` knowing how it is stored.
 */
fun interface DatabaseKeyProvider {

    /**
     * Returns the raw key bytes.
     *
     * `ByteArray` rather than `String`: JVM string interning leaves key
     * material recoverable from a heap dump long after use.
     *
     * **Ownership transfers to the caller, which zeroes the array once the
     * connection is open.** Every invocation must therefore return a *fresh*
     * array — an implementation that caches one and hands out the same instance
     * will return 32 zero bytes on its second call, and the database then fails
     * to open with `SQLITE_NOTADB` ("file is not a database"), which reads like
     * corruption rather than a key problem.
     *
     * If your source caches (a keychain lookup usually should), return
     * `cached.copyOf()`.
     */
    suspend fun key(): ZillitResult<ByteArray>
}

/** How the database is opened. */
sealed interface DatabaseLocation {

    /** On disk at [path]. Production. */
    data class File(val path: String) : DatabaseLocation

    /**
     * In memory.
     *
     * Encryption is meaningless here — nothing is written — so tests that care
     * about encryption must use [File].
     */
    data object InMemory : DatabaseLocation
}
