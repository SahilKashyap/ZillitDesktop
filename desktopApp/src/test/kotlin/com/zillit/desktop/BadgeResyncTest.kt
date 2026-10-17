package com.zillit.desktop

import com.zillit.desktop.core.datastore.PreferenceKey
import com.zillit.desktop.core.datastore.PreferenceScope
import com.zillit.desktop.core.datastore.PreferenceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Each launch's start-over: when a resync is due, and when a version prunes. */
class BadgeResyncTest {

    @Test
    fun `every launch is due once, until its resync lands`() = runTest {
        val preferences = FakePreferences()
        val resync = BadgeResync(preferences, launch = "run-1")
        assertTrue(resync.due())

        resync.completed()
        assertFalse(resync.due(), "the rest of this run seeds only what changed")

        assertTrue(BadgeResync(preferences, launch = "run-2").due(), "the next start of the app is due again")
    }

    @Test
    fun `a sign-out makes the next person's first session due again`() = runTest {
        val preferences = FakePreferences()
        val resync = BadgeResync(preferences, launch = "run-1")
        resync.completed()

        preferences.clear(PreferenceScope.User)

        assertTrue(resync.due())
    }

    /**
     * The prune runs per installed version, not per launch: it is the pass
     * that takes rows away, and an ordinary restart has no reason to pay for
     * it or to risk it.
     */
    @Test
    fun `a newly installed version prunes once`() = runTest {
        val preferences = FakePreferences()
        val resync = BadgeResync(preferences, installedVersion = "1.0.3", launch = "run-1")
        assertTrue(resync.pruneDue())

        resync.pruned()
        assertFalse(resync.pruneDue(), "the rest of this run leaves the ledger alone")
        assertFalse(
            BadgeResync(preferences, installedVersion = "1.0.3", launch = "run-2").pruneDue(),
            "a restart of the same build is not an update",
        )
        assertTrue(
            BadgeResync(preferences, installedVersion = "1.0.4", launch = "run-2").pruneDue(),
            "the next installed version is due one",
        )
    }

    /** A resync that fails must not record the version, or the prune is skipped for this build for good. */
    @Test
    fun `a version is due until its prune has judged a page`() = runTest {
        val preferences = FakePreferences()
        val resync = BadgeResync(preferences, installedVersion = "1.0.3", launch = "run-1")

        resync.completed()

        assertTrue(resync.pruneDue(), "the resync landing is not the prune landing")
    }

    /** Unpackaged, or a build that cannot name itself: pruning on every launch would be worse than not at all. */
    @Test
    fun `a build with no version never prunes`() = runTest {
        assertFalse(BadgeResync(FakePreferences(), launch = "run-1").pruneDue())
    }

    /** In memory, honest about defaults. */
    private class FakePreferences : PreferenceStore {
        private val values = MutableStateFlow(mapOf<String, Any>())

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> observe(key: PreferenceKey<T>): Flow<T> =
            values.map { it[key.name] as? T ?: key.default }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Any> get(key: PreferenceKey<T>): T = values.value[key.name] as? T ?: key.default

        override suspend fun <T : Any> set(key: PreferenceKey<T>, value: T) {
            values.value = values.value + (key.name to value)
        }

        override suspend fun <T : Any> remove(key: PreferenceKey<T>) {
            values.value = values.value - key.name
        }

        override suspend fun setActiveProject(projectId: String?) = Unit

        override suspend fun clear(scope: PreferenceScope) {
            values.value = emptyMap()
        }
    }
}
