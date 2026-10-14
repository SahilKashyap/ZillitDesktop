package com.zillit.desktop

import com.zillit.desktop.core.datastore.PreferenceKey
import com.zillit.desktop.core.datastore.PreferenceScope
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.ZillitPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The first launch after an update: when a resync is due, and what the listing's cards wear meanwhile. */
class BadgeResyncTest {

    @Test
    fun `a new version is due once, until its resync lands`() = runTest {
        val preferences = FakePreferences()
        val resync = BadgeResync(preferences, version = "1.0.8")
        assertTrue(resync.due(), "nothing remembered yet — the first launch with this counts as an update")

        resync.completed()
        assertFalse(resync.due())
        assertEquals("1.0.8", preferences.get(ZillitPreferences.BadgeResyncedVersion))

        assertTrue(BadgeResync(preferences, version = "1.0.9").due(), "the next update is due again")
    }

    @Test
    fun `the cards wear the server's totals until the resync lands, and never after`() = runTest {
        val resync = BadgeResync(FakePreferences(), version = "1.0.8")
        resync.showServerCards(mapOf("p1" to 40))
        assertEquals(mapOf("p1" to 40), resync.serverCards)

        resync.completed()
        assertNull(resync.serverCards, "the ledger's counts again")

        // A listing load racing the resync must not put the server's numbers back for good.
        resync.showServerCards(mapOf("p1" to 40))
        assertNull(resync.serverCards)
    }

    @Test
    fun `an empty server answer leaves the cards to the ledger`() = runTest {
        val resync = BadgeResync(FakePreferences(), version = "1.0.8")
        resync.showServerCards(emptyMap())
        assertNull(resync.serverCards)
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
