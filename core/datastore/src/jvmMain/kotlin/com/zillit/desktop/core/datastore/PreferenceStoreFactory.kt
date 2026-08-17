package com.zillit.desktop.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import okio.Path.Companion.toOkioPath

/**
 * Builds the app's preference store.
 *
 * **Instances are cached per file, and that is load-bearing.** DataStore throws
 * `IllegalStateException: There are multiple DataStores active for the same
 * file` on the first read if two are constructed for one path — and it throws at
 * *read* time, not construction, so the crash surfaces far from the second
 * `create()` call that caused it. Caching here means a DI graph, a test, or a
 * second window cannot trip it.
 *
 * Preferences live beside the database in `~/.zillit/`, not in a platform config
 * directory, so "delete this folder" removes every trace of the app except
 * keychain entries — which is what a support engineer will ask for.
 */
object PreferenceStoreFactory {

    private val stores = ConcurrentHashMap<String, PreferenceStore>()

    fun create(file: File = defaultFile()): PreferenceStore =
        stores.computeIfAbsent(file.canonicalPath) { path ->
            File(path).parentFile?.mkdirs()
            DataStorePreferenceStore(dataStore(File(path)))
        }

    private fun dataStore(file: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath { file.toOkioPath() }

    fun defaultFile(): File = File(System.getProperty("user.home"), ".zillit/$FILE_NAME")

    /**
     * Visible for tests: drops the cache so a test can use a fresh temp file.
     *
     * Not for production use — the underlying DataStore is not released, so
     * calling this and then reopening the same path reintroduces exactly the
     * multi-instance crash the cache prevents.
     */
    internal fun resetCacheForTesting() = stores.clear()

    /**
     * DataStore requires this exact extension and appends it if missing; naming
     * it explicitly avoids a file called `zillit.preferences_pb.preferences_pb`.
     */
    private const val FILE_NAME = "zillit.preferences_pb"
}
