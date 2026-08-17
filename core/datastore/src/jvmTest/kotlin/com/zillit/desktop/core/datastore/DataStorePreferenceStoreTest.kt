package com.zillit.desktop.core.datastore

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DataStorePreferenceStoreTest {

    private val tempDir: File = Files.createTempDirectory("zillit-prefs").toFile()
    private var counter = 0

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    /** A fresh store on its own file, so tests cannot interfere with each other. */
    private fun store(name: String = "prefs${counter++}") =
        PreferenceStoreFactory.create(File(tempDir, "$name.preferences_pb"))

    @Test
    fun `an unset preference returns its declared default`() = runTest {
        val store = store()

        assertEquals("System", store.get(ZillitPreferences.ThemeMode))
        assertEquals(100, store.get(ZillitPreferences.UiScalePercent))
        assertTrue(store.get(ZillitPreferences.RestoreWorkspaceOnLaunch))
    }

    @Test
    fun `values round-trip through each supported type`() = runTest {
        val store = store()

        store.set(ZillitPreferences.ThemeMode, "Dark")
        store.set(ZillitPreferences.UiScalePercent, 125)
        store.set(ZillitPreferences.WindowMaximized, true)

        assertEquals("Dark", store.get(ZillitPreferences.ThemeMode))
        assertEquals(125, store.get(ZillitPreferences.UiScalePercent))
        assertTrue(store.get(ZillitPreferences.WindowMaximized))
    }

    @Test
    fun `values are written durably to disk`() = runTest {
        // The whole point: the theme choice must outlive the process. A second
        // DataStore on the same path cannot be opened in-process (see
        // `the factory returns one instance per file`), so durability is checked
        // against the bytes rather than by reopening.
        val file = File(tempDir, "persist.preferences_pb")
        PreferenceStoreFactory.create(file).set(ZillitPreferences.ThemeMode, "Dark")

        assertTrue(file.exists(), "no preferences file was written")
        val bytes = file.readBytes()
        assertTrue(bytes.containsText("theme.mode"), "the key was not persisted")
        assertTrue(bytes.containsText("Dark"), "the value was not persisted")
    }

    @Test
    fun `the factory returns one instance per file`() {
        // DataStore throws on first *read* if two instances exist for one path,
        // so the crash would surface far from the duplicate create() that caused
        // it. The factory caches to make that impossible.
        val file = File(tempDir, "singleton.preferences_pb")

        assertSame(PreferenceStoreFactory.create(file), PreferenceStoreFactory.create(file))
    }

    @Test
    fun `observe emits the current value and then changes`() = runTest {
        val store = store()

        assertEquals("System", store.observe(ZillitPreferences.ThemeMode).first())

        store.set(ZillitPreferences.ThemeMode, "Light")

        assertEquals("Light", store.observe(ZillitPreferences.ThemeMode).first())
    }

    @Test
    fun `remove restores the default`() = runTest {
        val store = store()
        store.set(ZillitPreferences.UiScalePercent, 150)

        store.remove(ZillitPreferences.UiScalePercent)

        assertEquals(100, store.get(ZillitPreferences.UiScalePercent))
    }

    // -- scoping ----------------------------------------------------------

    @Test
    fun `project-scoped values are independent per project`() = runTest {
        // On Android these are single global keys, so switching production
        // silently carries the previous one's layout over.
        val store = store()

        store.setActiveProject("feature-film")
        store.set(ZillitPreferences.BoxScheduleView, "calendar")

        store.setActiveProject("tv-series")
        assertEquals("grid", store.get(ZillitPreferences.BoxScheduleView), "must not inherit the other project's view")

        store.set(ZillitPreferences.BoxScheduleView, "list")

        store.setActiveProject("feature-film")
        assertEquals("calendar", store.get(ZillitPreferences.BoxScheduleView), "the first project's value was lost")
    }

    @Test
    fun `a project-scoped read with no active project returns the default rather than throwing`() = runTest {
        // The UI renders during a project switch; it must not crash.
        val store = store()

        assertEquals("grid", store.get(ZillitPreferences.BoxScheduleView))
    }

    @Test
    fun `a project-scoped write with no active project is dropped rather than leaking`() = runTest {
        val store = store()

        store.set(ZillitPreferences.BoxScheduleView, "calendar")
        store.setActiveProject("some-project")

        assertEquals("grid", store.get(ZillitPreferences.BoxScheduleView), "an unscoped write must not become global")
    }

    @Test
    fun `observe re-emits when the active project changes`() = runTest {
        val store = store()
        store.setActiveProject("a")
        store.set(ZillitPreferences.BoxScheduleView, "calendar")
        assertEquals("calendar", store.observe(ZillitPreferences.BoxScheduleView).first())

        store.setActiveProject("b")

        assertEquals("grid", store.observe(ZillitPreferences.BoxScheduleView).first())
    }

    // -- clearing ---------------------------------------------------------

    @Test
    fun `clearing user scope leaves device and project settings alone`() = runTest {
        // Sign-out. The theme is the user's machine preference and should
        // survive; their notification mute should not.
        val store = store()
        store.setActiveProject("p1")
        store.set(ZillitPreferences.ThemeMode, "Dark")
        store.set(ZillitPreferences.MuteNotifications, true)
        store.set(ZillitPreferences.BoxScheduleView, "calendar")

        store.clear(PreferenceScope.User)

        assertEquals("Dark", store.get(ZillitPreferences.ThemeMode), "device settings must survive sign-out")
        assertFalse(store.get(ZillitPreferences.MuteNotifications), "user settings must be cleared")
        assertEquals("calendar", store.get(ZillitPreferences.BoxScheduleView))
    }

    @Test
    fun `clearing project scope removes every project's settings`() = runTest {
        val store = store()
        store.setActiveProject("p1")
        store.set(ZillitPreferences.BoxScheduleView, "calendar")
        store.setActiveProject("p2")
        store.set(ZillitPreferences.BoxScheduleView, "list")

        store.clear(PreferenceScope.Project)

        assertEquals("grid", store.get(ZillitPreferences.BoxScheduleView))
        store.setActiveProject("p1")
        assertEquals("grid", store.get(ZillitPreferences.BoxScheduleView))
    }

    @Test
    fun `clearing device scope leaves user and project settings alone`() = runTest {
        val store = store()
        store.setActiveProject("p1")
        store.set(ZillitPreferences.ThemeMode, "Dark")
        store.set(ZillitPreferences.MuteNotifications, true)

        store.clear(PreferenceScope.Device)

        assertEquals("System", store.get(ZillitPreferences.ThemeMode))
        assertTrue(store.get(ZillitPreferences.MuteNotifications))
    }

    // -- declaration hygiene ----------------------------------------------

    @Test
    fun `no two preferences share a storage name`() {
        // A collision would silently make two settings the same setting.
        val names = ZillitPreferences.all.map { key ->
            key.storageName(projectId = if (key.scope == PreferenceScope.Project) "p" else null)
        }

        assertEquals(names.size, names.distinct().size, "duplicate preference names: ${names.duplicates()}")
    }

    @Test
    fun `no preference name looks like a secret`() {
        // This store is plaintext on disk by design. A token declared here would
        // look protected while being anything but (plan §8.4).
        val forbidden = listOf("token", "password", "secret", "key", "credential")
        val offenders = ZillitPreferences.all
            .map { it.name }
            .filter { name -> forbidden.any { name.contains(it, ignoreCase = true) } }

        assertTrue(offenders.isEmpty(), "secrets belong in core:security, not preferences: $offenders")
    }

    private fun List<String>.duplicates(): List<String> =
        groupBy { it }.filterValues { it.size > 1 }.keys.toList()

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
}
