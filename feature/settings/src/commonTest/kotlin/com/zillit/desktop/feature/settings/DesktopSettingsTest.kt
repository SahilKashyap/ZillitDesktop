package com.zillit.desktop.feature.settings

import com.zillit.desktop.feature.settings.ui.NotificationSettings
import com.zillit.desktop.feature.settings.ui.SettingsEvent
import com.zillit.desktop.feature.settings.ui.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * The Desktop section: the two floating cards, close-to-tray and start-at-login.
 * Each has to reach its store, and the login switch has to report when there is
 * no installed app for the OS to start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSettingsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `each desktop switch writes through on its own`() = runTest(dispatcher) {
        val callWidget = mutableListOf<Boolean>()
        val closeToTray = mutableListOf<Boolean>()
        val startAtLogin = mutableListOf<Boolean>()
        val settings = SettingsViewModel(
            setTheme = {},
            setScale = {},
            notifications = NotificationSettings(
                startAtLoginAvailable = false,
                setCallWidget = callWidget::add,
                setCloseToTray = closeToTray::add,
                setStartAtLogin = startAtLogin::add,
            ),
            signOut = {},
        )
        runCurrent()
        assertFalse(settings.state.value.startAtLoginAvailable, "a development run has no launcher to register")
        assertTrue(settings.state.value.callWidget)
        assertTrue(settings.state.value.closeToTray)

        settings.onEvent(SettingsEvent.CallWidgetChanged(false))
        settings.onEvent(SettingsEvent.CloseToTrayChanged(false))
        settings.onEvent(SettingsEvent.StartAtLoginChanged(true))
        runCurrent()

        assertFalse(settings.state.value.callWidget)
        assertFalse(settings.state.value.closeToTray)
        assertTrue(settings.state.value.startAtLogin)
        assertTrue(settings.state.value.messageWidget, "the message card is not touched by the others")
        assertEquals(listOf(false), callWidget)
        assertEquals(listOf(false), closeToTray)
        assertEquals(listOf(true), startAtLogin)
    }
}
