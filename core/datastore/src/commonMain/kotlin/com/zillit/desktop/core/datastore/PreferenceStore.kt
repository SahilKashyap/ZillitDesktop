package com.zillit.desktop.core.datastore

import kotlinx.coroutines.flow.Flow

/**
 * Reactive, typed preference storage.
 *
 * Reads return a [Flow] rather than a value, because preferences drive UI: the
 * theme toggle, the density setting and the workspace layout mode all have to
 * take effect the moment they change, in every window at once. The Android
 * accessors are one-shot `suspend fun get…()` calls, so a change there only
 * appears wherever someone remembered to re-read.
 *
 * The project-scoped slice needs a current project — set it via
 * [setActiveProject] on sign-in and on project switch.
 */
interface PreferenceStore {

    fun <T : Any> observe(key: PreferenceKey<T>): Flow<T>

    suspend fun <T : Any> get(key: PreferenceKey<T>): T

    suspend fun <T : Any> set(key: PreferenceKey<T>, value: T)

    suspend fun <T : Any> remove(key: PreferenceKey<T>)

    /**
     * Sets the project that project-scoped keys resolve against.
     *
     * Passing null (sign-out, or returning to the project picker) makes reads of
     * project-scoped keys return their defaults rather than throwing — a UI
     * rendering during a project switch must not crash.
     */
    suspend fun setActiveProject(projectId: String?)

    /**
     * Clears every key in [scope].
     *
     * `User` runs on sign-out, `Project` when leaving a project. Splitting these
     * is the point of [PreferenceScope]: on Android, clearing user state means
     * remembering which of 66 keys to delete by hand.
     */
    suspend fun clear(scope: PreferenceScope)
}
