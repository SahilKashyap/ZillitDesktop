package com.zillit.desktop.feature.settings

import com.zillit.desktop.feature.settings.ui.NotificationSettings
import com.zillit.desktop.feature.settings.ui.SettingsEvent
import com.zillit.desktop.feature.settings.ui.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * The per-source switches. Each one has to reach the store — a toggle that
 * only moved on screen would go back to its old position on the next launch,
 * and the user would rightly call the setting broken.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSettingsTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `each source toggles independently and is written through`() = runTest(dispatcher) {
        val messages = mutableListOf<Boolean>()
        val mail = mutableListOf<Boolean>()
        val settings = SettingsViewModel(
            setTheme = {},
            setScale = {},
            notifications = NotificationSettings(
                setMessages = messages::add,
                setMail = mail::add,
            ),
            signOut = {},
        )
        // Let the stored values land first: the screen mirrors the store, so
        // a toggle asserted before that first read would be racing it.
        runCurrent()

        settings.onEvent(SettingsEvent.NotifyMessagesChanged(false))
        assertFalse(settings.state.value.notifyMessages)
        assertTrue(settings.state.value.notifyMail, "mail is not touched by the message switch")
        assertEquals(listOf(false), messages)
        assertEquals(emptyList(), mail)

        settings.onEvent(SettingsEvent.NotifyMailChanged(false))
        assertEquals(listOf(false), mail)
    }

    @Test
    fun `the screen mirrors the stored values rather than its own copy`() = runTest(dispatcher) {
        val stored = MutableStateFlow(true)
        val settings = SettingsViewModel(
            setTheme = {},
            setScale = {},
            notifications = NotificationSettings(messages = stored),
            signOut = {},
        )

        runCurrent()
        assertTrue(settings.state.value.notifyMessages)

        // Changed elsewhere — another window, a fresh read — and the switch follows.
        stored.value = false
        runCurrent()
        assertFalse(settings.state.value.notifyMessages)
    }
}
