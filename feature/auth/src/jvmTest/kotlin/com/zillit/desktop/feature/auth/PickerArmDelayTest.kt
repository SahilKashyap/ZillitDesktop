package com.zillit.desktop.feature.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.ui.AuthEvent
import com.zillit.desktop.feature.auth.ui.AuthStep
import com.zillit.desktop.feature.auth.ui.AuthUiState
import com.zillit.desktop.feature.auth.ui.ProjectListScreen
import com.zillit.desktop.core.designsystem.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The picker must not open a production the instant it appears.
 *
 * The regression this pins: the picker composes under a mouse that just
 * clicked the production switcher, and a habitual double-click's second
 * release landed on whichever card appeared beneath it — the app hopped to
 * a production nobody chose. Opens stay disarmed for a beat; everything
 * else on the screen works immediately.
 */
@OptIn(ExperimentalTestApi::class)
class PickerArmDelayTest {

    private val project = Project(
        id = "p1", name = "SG Document Distribution", code = "985073",
        type = null, region = null,
    )

    private fun state() = AuthUiState(
        step = AuthStep.ProjectSelection,
        projects = listOf(project),
    )

    @Test
    fun `a click in the arming window opens nothing, a settled click opens`() = runComposeUiTest {
        val events = mutableListOf<AuthEvent>()
        setContent {
            ZillitTheme(darkTheme = false) {
                ProjectListScreen(
                    state = state(),
                    onEvent = { events += it },
                    themeMode = ThemeMode.Light,
                    onThemeModeChange = {},
                )
            }
        }

        // The double-click echo: a click before the guard arms.
        mainClock.autoAdvance = false
        onNodeWithText("SG Document Distribution").performClick()
        mainClock.advanceTimeBy(100)
        assertTrue(
            events.none { it is AuthEvent.SelectProject },
            "a click during the arming window must not open a project",
        )

        // Past the guard, the same click opens.
        mainClock.advanceTimeBy(1_000)
        onNodeWithText("SG Document Distribution").performClick()
        mainClock.advanceTimeBy(100)
        val opened = events.filterIsInstance<AuthEvent.SelectProject>()
        assertEquals(listOf(project), opened.map { it.project })
    }
}
