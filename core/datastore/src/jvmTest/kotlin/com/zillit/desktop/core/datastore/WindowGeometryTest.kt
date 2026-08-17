package com.zillit.desktop.core.datastore

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the save/restore round trip that was previously only exercisable by
 * launching the app and closing the window by hand — which is exactly why it
 * went unverified.
 */
class WindowGeometryTest {

    private val tempDir: File = Files.createTempDirectory("zillit-geometry").toFile()
    private var counter = 0

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    private fun store() = PreferenceStoreFactory.create(File(tempDir, "geo${counter++}.preferences_pb"))

    @Test
    fun `geometry round-trips`() = runTest {
        val store = store()

        store.saveWindowGeometry(WindowGeometry(width = 1600, height = 1000, x = 120, y = 80))

        assertEquals(WindowGeometry(1600, 1000, 120, 80), store.loadWindowGeometry())
    }

    @Test
    fun `a first launch returns the declared defaults with no position`() = runTest {
        val geometry = store().loadWindowGeometry()

        assertEquals(1440, geometry.width)
        assertEquals(900, geometry.height)
        assertFalse(geometry.hasPosition, "a fresh install must let the platform place the window")
        assertNull(geometry.x)
    }

    @Test
    fun `saving without a position leaves an existing one intact`() = runTest {
        // Overwriting a good position with the sentinel would make the window
        // forget where it was on every restart.
        val store = store()
        store.saveWindowGeometry(WindowGeometry(1600, 1000, x = 120, y = 80))

        store.saveWindowGeometry(WindowGeometry(1200, 800))

        val geometry = store.loadWindowGeometry()
        assertEquals(1200, geometry.width)
        assertEquals(120, geometry.x, "the saved position should have survived")
        assertEquals(80, geometry.y)
    }

    @Test
    fun `a half-saved position is treated as no position`() = runTest {
        // Placing a window at a half-known coordinate can put it somewhere
        // unreachable — off the edge of a smaller display than the one it was
        // last used on.
        val store = store()
        store.set(ZillitPreferences.WindowX, 500)
        // WindowY deliberately left at its UNSET sentinel.

        val geometry = store.loadWindowGeometry()

        assertFalse(geometry.hasPosition)
        assertNull(geometry.x)
        assertNull(geometry.y)
    }

    @Test
    fun `a position at the origin is a real position, not an absent one`() = runTest {
        // 0,0 is a legitimate top-left placement; only the sentinel means absent.
        val store = store()
        store.saveWindowGeometry(WindowGeometry(1440, 900, x = 0, y = 0))

        val geometry = store.loadWindowGeometry()

        assertTrue(geometry.hasPosition)
        assertEquals(0, geometry.x)
    }

    @Test
    fun `geometry survives a restart`() = runTest {
        val file = File(tempDir, "restart.preferences_pb")
        PreferenceStoreFactory.create(file).saveWindowGeometry(WindowGeometry(1280, 720, 40, 40))

        // Same file, same process — DataStore permits only one instance per
        // path, and the factory returns the cached one (see the singleton test).
        assertEquals(
            WindowGeometry(1280, 720, 40, 40),
            PreferenceStoreFactory.create(file).loadWindowGeometry(),
        )
    }

    @Test
    fun `a nonsensical size is rejected at construction`() {
        // A zero-size window is invisible and unrecoverable without editing the
        // preferences file by hand.
        assertFailsWith<IllegalArgumentException> { WindowGeometry(width = 0, height = 900) }
        assertFailsWith<IllegalArgumentException> { WindowGeometry(width = 1440, height = -1) }
    }
}
