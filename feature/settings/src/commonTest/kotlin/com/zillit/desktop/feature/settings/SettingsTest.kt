package com.zillit.desktop.feature.settings

import com.zillit.desktop.core.designsystem.ThemeMode
import com.zillit.desktop.feature.settings.ui.AccountSummary
import com.zillit.desktop.feature.settings.ui.SettingsEffect
import com.zillit.desktop.feature.settings.ui.SettingsEvent
import com.zillit.desktop.feature.settings.ui.NotificationSettings
import com.zillit.desktop.feature.settings.ui.SettingsUiState
import com.zillit.desktop.feature.settings.ui.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Application settings.
 *
 * The two that matter: signing out asks first, because it takes the local cache
 * with it; and the interface size cannot be driven to a value the user can no
 * longer read well enough to undo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Recorder {
        val themes = mutableListOf<ThemeMode>()
        val scales = mutableListOf<Int>()
        val mutes = mutableListOf<Boolean>()
        var signOuts = 0
    }

    private fun viewModel(
        recorder: Recorder,
        initial: SettingsUiState = SettingsUiState(),
    ) = SettingsViewModel(
        setTheme = recorder.themes::add,
        setScale = recorder.scales::add,
        notifications = NotificationSettings(setMuted = recorder.mutes::add),
        signOut = { recorder.signOuts++ },
        initial = initial,
    )

    // -- appearance --------------------------------------------------------

    @Test
    fun `choosing a theme applies and persists it`() = runTest {
        val recorder = Recorder()
        val settings = viewModel(recorder)

        settings.onEvent(SettingsEvent.ThemeChanged(ThemeMode.Dark))

        assertEquals(ThemeMode.Dark, settings.state.value.themeMode)
        assertEquals(listOf(ThemeMode.Dark), recorder.themes)
    }

    @Test
    fun `the interface size steps and persists`() = runTest {
        val recorder = Recorder()
        val settings = viewModel(recorder)

        settings.onEvent(SettingsEvent.ScaleChanged(SettingsUiState.SCALE_STEP))

        assertEquals(110, settings.state.value.uiScalePercent)
        assertEquals(listOf(110), recorder.scales)
    }

    @Test
    fun `the size cannot be driven past what is readable`() = runTest {
        // Getting stuck at 20% would leave the user unable to read the control
        // that would put it back.
        val recorder = Recorder()
        val settings = viewModel(recorder, SettingsUiState(uiScalePercent = SettingsUiState.MIN_SCALE))

        repeat(5) { settings.onEvent(SettingsEvent.ScaleChanged(-SettingsUiState.SCALE_STEP)) }

        assertEquals(SettingsUiState.MIN_SCALE, settings.state.value.uiScalePercent)
        assertFalse(settings.state.value.canDecreaseScale)
    }

    @Test
    fun `the size cannot be driven past the top either`() = runTest {
        val recorder = Recorder()
        val settings = viewModel(recorder, SettingsUiState(uiScalePercent = SettingsUiState.MAX_SCALE))

        settings.onEvent(SettingsEvent.ScaleChanged(SettingsUiState.SCALE_STEP))

        assertEquals(SettingsUiState.MAX_SCALE, settings.state.value.uiScalePercent)
        assertFalse(settings.state.value.canIncreaseScale)
    }

    @Test
    fun `reset returns to the default`() = runTest {
        val recorder = Recorder()
        val settings = viewModel(recorder, SettingsUiState(uiScalePercent = 140))

        settings.onEvent(SettingsEvent.ScaleReset)

        assertEquals(SettingsUiState.DEFAULT_SCALE, settings.state.value.uiScalePercent)
        assertEquals(listOf(SettingsUiState.DEFAULT_SCALE), recorder.scales)
    }

    // -- signing out -------------------------------------------------------

    @Test
    fun `signing out asks first`() = runTest {
        // It removes the encrypted local cache, which is not what people expect
        // "sign out" to do.
        val recorder = Recorder()
        val settings = viewModel(recorder)

        settings.onEvent(SettingsEvent.AskSignOut)
        advanceUntilIdle()

        assertTrue(settings.state.value.isConfirmingSignOut)
        assertEquals(0, recorder.signOuts)
    }

    @Test
    fun `confirming signs out and reports it`() = runTest {
        val recorder = Recorder()
        val settings = viewModel(recorder)
        val effects = mutableListOf<SettingsEffect>()
        val job = CoroutineScope(dispatcher).launch {
            settings.effects.collect(effects::add)
        }

        settings.onEvent(SettingsEvent.AskSignOut)
        settings.onEvent(SettingsEvent.ConfirmSignOut)
        advanceUntilIdle()

        assertEquals(1, recorder.signOuts)
        assertFalse(settings.state.value.isConfirmingSignOut)
        assertTrue(effects.contains(SettingsEffect.SignedOut))
        job.cancel()
    }

    @Test
    fun `cancelling signs nobody out`() = runTest {
        val recorder = Recorder()
        val settings = viewModel(recorder)

        settings.onEvent(SettingsEvent.AskSignOut)
        settings.onEvent(SettingsEvent.DismissSignOut)
        advanceUntilIdle()

        assertEquals(0, recorder.signOuts)
        assertFalse(settings.state.value.isConfirmingSignOut)
    }

    // -- the account -------------------------------------------------------

    @Test
    fun `an account with nothing in it is not shown`() {
        assertFalse(AccountSummary().hasAccount)
        assertTrue(AccountSummary(fullName = "Aisha").hasAccount)
        assertTrue(AccountSummary(email = "a@b.com").hasAccount)
    }

    @Test
    fun `the account summary never prints an address`() {
        // Settings state reaches log files like everything else.
        val printed = AccountSummary(fullName = "Aisha", email = "aisha@prod.com").toString()

        assertFalse(printed.contains("aisha@prod.com"))
    }

    // -- notifications -----------------------------------------------------

    @Test
    fun `muting reminders persists the choice`() = runTest {
        val recorder = Recorder()
        val settings = viewModel(recorder)

        settings.onEvent(SettingsEvent.MuteNotificationsChanged(muted = true))

        assertTrue(settings.state.value.muteNotifications)
        assertEquals(listOf(true), recorder.mutes)
    }

    @Test
    fun `the screen follows the stored mute setting`() = runTest {
        // The scheduler reads the same preference, so a switch that showed its
        // own idea of the value would point the wrong way after any other
        // window changed it.
        val recorder = Recorder()
        val stored = MutableStateFlow(false)
        val settings = SettingsViewModel(
            setTheme = recorder.themes::add,
            setScale = recorder.scales::add,
            notifications = NotificationSettings(muted = stored, setMuted = recorder.mutes::add),
            signOut = { recorder.signOuts++ },
        )

        stored.value = true
        runCurrent()

        assertTrue(settings.state.value.muteNotifications)
    }
}
