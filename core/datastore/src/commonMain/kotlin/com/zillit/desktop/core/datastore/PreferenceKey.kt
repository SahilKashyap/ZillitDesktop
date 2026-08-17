package com.zillit.desktop.core.datastore

/**
 * A typed, scoped preference.
 *
 * ## Why this is not a port of `SharedPref.kt`
 *
 * The Android `SharedPref` object is 1,352 lines and 66 accessors, and it mixes
 * three unrelated things:
 *
 *  1. **Real preferences** — `fontSize`, `appLock`, `boxScheduleDefaultView`,
 *     `isMuteNotification`. Small, user-owned, rarely written.
 *  2. **Cached API responses** — `countryList`, `departmentList`, `emailFolder`,
 *     `crewListResponse`, `projectLabels`, serialised to JSON strings. These are
 *     server data with lifetimes and invalidation rules.
 *  3. **Secrets** — `chatGptToken`, `googleMapKey`, `deviceId`.
 *
 * Porting all three into one store would carry the conflation forward, and it
 * is already causing problems on Android: cached lists have no TTL, so they go
 * stale silently, and cache invalidation on project switch means hand-deleting
 * individual keys.
 *
 * On desktop they are split:
 *
 * | Category | Home |
 * |---|---|
 * | Preferences | **here** — `core:datastore` |
 * | Cached server data | `core:database` (SQLDelight, with TTL and typed rows) |
 * | Secrets | `core:security` (OS keychain) |
 *
 * **Nothing sensitive may be declared here.** This store is plaintext on disk by
 * design — preferences are not worth the cost of the keychain, and putting a
 * token here would make it look protected when it is not.
 */
sealed class PreferenceKey<T : Any>(
    val name: String,
    val default: T,
    val scope: PreferenceScope,
) {
    class BooleanKey(name: String, default: Boolean, scope: PreferenceScope) :
        PreferenceKey<Boolean>(name, default, scope)

    class IntKey(name: String, default: Int, scope: PreferenceScope) :
        PreferenceKey<Int>(name, default, scope)

    class LongKey(name: String, default: Long, scope: PreferenceScope) :
        PreferenceKey<Long>(name, default, scope)

    class StringKey(name: String, default: String, scope: PreferenceScope) :
        PreferenceKey<String>(name, default, scope)

    /**
     * The stored name, namespaced by scope.
     *
     * Project-scoped keys carry the project id, so two projects hold
     * independent values and switching between them does not leak one project's
     * view settings into another.
     */
    fun storageName(projectId: String? = null): String = when (scope) {
        PreferenceScope.Device -> name
        PreferenceScope.User -> "user.$name"
        PreferenceScope.Project -> {
            requireNotNull(projectId) { "$name is project-scoped but no project id was supplied" }
            "project.$projectId.$name"
        }
    }
}

/**
 * Lifetime of a preference — which is really a question about what wipes it.
 *
 * The Android app has no equivalent, which is why switching project there means
 * remembering to clear the right individual keys by hand.
 */
enum class PreferenceScope {
    /** Survives sign-out. Theme, window geometry, language. */
    Device,

    /** Cleared on sign-out. */
    User,

    /** Per project; cleared when that project is left or deleted. */
    Project,
}
