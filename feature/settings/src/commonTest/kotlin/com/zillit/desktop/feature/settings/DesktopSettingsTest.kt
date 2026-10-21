package com.zillit.desktop.feature.settings

import com.zillit.desktop.feature.settings.ui.NotificationSettings
import com.zillit.desktop.feature.settings.ui.SettingsEvent
import com.zillit.desktop.feature.settings.ui.SettingsViewModel
import com.zillit.desktop.feature.settings.ui.WidgetToggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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

/** The Desktop Widgets section: each widget's switch reaches its store, and only that widget moves. */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSettingsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a widget switch writes through on its own`() = runTest(dispatcher) {
        val written = mutableListOf<Pair<String, Boolean>>()
        val settings = SettingsViewModel(
            setTheme = {},
            setScale = {},
            notifications = NotificationSettings(
                widgets = flowOf(
                    listOf(
                        WidgetToggle("Drive", "Drive widget", "", on = true),
                        WidgetToggle("Chat", "Chat widget", "", on = true),
                    ),
                ),
                setWidget = { id, on -> written += id to on },
            ),
            signOut = {},
        )
        runCurrent()

        settings.onEvent(SettingsEvent.WidgetChanged("Chat", false))
        runCurrent()

        assertFalse(settings.state.value.widgets.single { it.id == "Chat" }.on)
        assertTrue(settings.state.value.widgets.single { it.id == "Drive" }.on, "the other widget is not touched")
        assertEquals(listOf("Chat" to false), written)
    }
}
