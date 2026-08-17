package com.zillit.desktop.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zillit.desktop.core.common.ZillitLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * [PreferenceStore] on androidx DataStore.
 *
 * DataStore rather than a hand-rolled file because it already solves the things
 * that are tedious to get right — atomic writes, corruption recovery, and a
 * change `Flow` — and because the Android app's `SharedPref` uses the same API,
 * so the team's mental model transfers.
 */
class DataStorePreferenceStore(
    private val dataStore: DataStore<Preferences>,
) : PreferenceStore {

    /**
     * Kept as state rather than a constructor parameter because the active
     * project changes at runtime, and project-scoped reads must re-emit against
     * the new project without anyone rebuilding the store.
     */
    private val activeProject = MutableStateFlow<String?>(null)

    override fun <T : Any> observe(key: PreferenceKey<T>): Flow<T> =
        combine(dataStore.data, activeProject) { preferences, projectId ->
            read(preferences, key, projectId)
        }.distinctUntilChanged()

    override suspend fun <T : Any> get(key: PreferenceKey<T>): T =
        read(dataStore.data.first(), key, activeProject.value)

    override suspend fun <T : Any> set(key: PreferenceKey<T>, value: T) {
        val storageName = storageNameOrNull(key) ?: return
        dataStore.edit { preferences ->
            when (key) {
                is PreferenceKey.BooleanKey -> preferences[booleanPreferencesKey(storageName)] = value as Boolean
                is PreferenceKey.IntKey -> preferences[intPreferencesKey(storageName)] = value as Int
                is PreferenceKey.LongKey -> preferences[longPreferencesKey(storageName)] = value as Long
                is PreferenceKey.StringKey -> preferences[stringPreferencesKey(storageName)] = value as String
            }
        }
    }

    override suspend fun <T : Any> remove(key: PreferenceKey<T>) {
        val storageName = storageNameOrNull(key) ?: return
        dataStore.edit { preferences ->
            preferences.remove(
                when (key) {
                    is PreferenceKey.BooleanKey -> booleanPreferencesKey(storageName)
                    is PreferenceKey.IntKey -> intPreferencesKey(storageName)
                    is PreferenceKey.LongKey -> longPreferencesKey(storageName)
                    is PreferenceKey.StringKey -> stringPreferencesKey(storageName)
                },
            )
        }
    }

    override suspend fun setActiveProject(projectId: String?) {
        activeProject.value = projectId?.takeIf { it.isNotBlank() }
    }

    /**
     * Clears a scope by prefix.
     *
     * Prefix matching rather than an enumerated key list, so a key that was
     * removed from [ZillitPreferences] in a past release still gets cleaned up —
     * otherwise stale entries accumulate forever in a file nobody inspects.
     */
    @Suppress("UNCHECKED_CAST")
    override suspend fun clear(scope: PreferenceScope) {
        dataStore.edit { preferences ->
            val doomed = preferences.asMap().keys.filter { scope.owns(it.name) }
            // The star projection on the key set has to be erased to call
            // remove; the value type is irrelevant, only the name matters.
            doomed.forEach { preferences.remove(it as Preferences.Key<Any>) }
            ZillitLog.i(TAG) { "Cleared ${doomed.size} ${scope.name.lowercase()}-scoped preferences" }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> read(preferences: Preferences, key: PreferenceKey<T>, projectId: String?): T {
        val storageName = key.storageNameOrNull(projectId) ?: return key.default
        val stored: Any? = when (key) {
            is PreferenceKey.BooleanKey -> preferences[booleanPreferencesKey(storageName)]
            is PreferenceKey.IntKey -> preferences[intPreferencesKey(storageName)]
            is PreferenceKey.LongKey -> preferences[longPreferencesKey(storageName)]
            is PreferenceKey.StringKey -> preferences[stringPreferencesKey(storageName)]
        }
        return (stored ?: key.default) as T
    }

    /**
     * Null when the key is project-scoped and no project is active — a read
     * during a project switch returns the default rather than throwing.
     */
    private fun storageNameOrNull(key: PreferenceKey<*>): String? =
        key.storageNameOrNull(activeProject.value)

    private companion object {
        const val TAG = "Preferences"
    }
}

private fun PreferenceKey<*>.storageNameOrNull(projectId: String?): String? =
    if (scope == PreferenceScope.Project && projectId == null) null else storageName(projectId)

/** Whether a stored key name belongs to this scope. Mirrors `PreferenceKey.storageName`. */
private fun PreferenceScope.owns(storageName: String): Boolean = when (this) {
    PreferenceScope.User -> storageName.startsWith("user.")
    PreferenceScope.Project -> storageName.startsWith("project.")
    // Device keys are unprefixed, so they are whatever is left.
    PreferenceScope.Device -> !storageName.startsWith("user.") && !storageName.startsWith("project.")
}

/** Convenience for observing with a mapping, e.g. a stored name to an enum. */
fun <T : Any, R> PreferenceStore.observeAs(key: PreferenceKey<T>, transform: (T) -> R): Flow<R> =
    observe(key).map(transform).distinctUntilChanged()
