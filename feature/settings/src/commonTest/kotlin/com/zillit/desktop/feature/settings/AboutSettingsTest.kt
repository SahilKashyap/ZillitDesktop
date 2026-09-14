package com.zillit.desktop.feature.settings

import com.zillit.desktop.core.appupdate.UpdateStatus
import com.zillit.desktop.feature.settings.ui.AboutInfo
import com.zillit.desktop.feature.settings.ui.SettingsEffect
import com.zillit.desktop.feature.settings.ui.SettingsEvent
import com.zillit.desktop.feature.settings.ui.SettingsUiState
import com.zillit.desktop.feature.settings.ui.SettingsViewModel
import com.zillit.desktop.feature.settings.ui.UpdateCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The About row: the version line and the manual update check.
 *
 * The check is the reader asking, so unlike the shell's banner it has to
 * answer every time — including "you are current" and "could not check".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AboutSettingsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the version line carries the build when there is one`() {
        assertEquals("1.0.2 (2dbe8ff)", AboutInfo(version = "1.0.2", build = "2dbe8ff").versionLabel)
        assertEquals("1.0.2", AboutInfo(version = "1.0.2").versionLabel)
    }

    @Test
    fun `a check goes through Checking to the answer`() = runTest(dispatcher) {
        val answer = CompletableDeferred<UpdateStatus>()
        val settings = settings { answer.await() }

        settings.onEvent(SettingsEvent.CheckForUpdates)
        runCurrent()
        assertEquals(UpdateCheck.Checking, settings.state.value.about.updateCheck)

        answer.complete(UpdateStatus.Available("1.0.3", "https://dl.example/Zillit.dmg"))
        runCurrent()
        assertEquals(
            UpdateCheck.Available("1.0.3", "https://dl.example/Zillit.dmg", mandatory = false),
            settings.state.value.about.updateCheck,
        )
    }

    @Test
    fun `every verdict has a row to land on`() = runTest(dispatcher) {
        val cases = mapOf(
            UpdateStatus.UpToDate to UpdateCheck.UpToDate,
            UpdateStatus.Unknown to UpdateCheck.Unavailable,
            UpdateStatus.Required("2.0.0", null) to UpdateCheck.Available("2.0.0", null, mandatory = true),
        )
        cases.forEach { (status, expected) ->
            val settings = settings { status }
            settings.onEvent(SettingsEvent.CheckForUpdates)
            runCurrent()
            assertEquals(expected, settings.state.value.about.updateCheck, "for $status")
        }
    }

    /** A build that cannot check says so, rather than showing a button that does nothing. */
    @Test
    fun `no checker is Unavailable without a request`() = runTest(dispatcher) {
        val settings = SettingsViewModel(setTheme = {}, setScale = {}, signOut = {}, checkForUpdates = null)

        settings.onEvent(SettingsEvent.CheckForUpdates)
        runCurrent()

        assertEquals(UpdateCheck.Unavailable, settings.state.value.about.updateCheck)
    }

    @Test
    fun `a second click while checking asks once`() = runTest(dispatcher) {
        var asked = 0
        val gate = CompletableDeferred<UpdateStatus>()
        val settings = settings {
            asked++
            gate.await()
        }

        settings.onEvent(SettingsEvent.CheckForUpdates)
        runCurrent()
        settings.onEvent(SettingsEvent.CheckForUpdates)
        runCurrent()
        gate.complete(UpdateStatus.UpToDate)
        runCurrent()

        assertEquals(1, asked)
        assertEquals(UpdateCheck.UpToDate, settings.state.value.about.updateCheck)
    }

    /** Download is the same external-URL effect the help links use — the app's guarded launcher. */
    @Test
    fun `Download hands the URL out as an external open`() = runTest(dispatcher) {
        val settings = settings { UpdateStatus.UpToDate }

        settings.onEvent(SettingsEvent.DownloadUpdate("https://dl.example/Zillit.msi"))
        val effect = settings.effects.first()

        assertEquals(SettingsEffect.OpenExternal("https://dl.example/Zillit.msi"), effect)
    }

    private fun settings(check: suspend () -> UpdateStatus) = SettingsViewModel(
        setTheme = {},
        setScale = {},
        signOut = {},
        checkForUpdates = check,
        initial = SettingsUiState(about = AboutInfo(version = "1.0.2", build = "2dbe8ff", platform = "Mac OS X 26.5")),
    )
}
