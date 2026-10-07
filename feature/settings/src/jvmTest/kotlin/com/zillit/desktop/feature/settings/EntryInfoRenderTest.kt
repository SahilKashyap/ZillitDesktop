package com.zillit.desktop.feature.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.ui.AccountSummary
import com.zillit.desktop.feature.settings.ui.AdminSettingsScreen
import com.zillit.desktop.feature.settings.ui.SettingsEvent
import com.zillit.desktop.feature.settings.ui.SettingsScreen
import com.zillit.desktop.feature.settings.ui.SettingsUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/** The ⓘ on both tabs: a click explains the row, and More goes to the docs. */
@OptIn(ExperimentalTestApi::class)
class EntryInfoRenderTest {

    private val state = SettingsUiState(account = AccountSummary(isAdmin = true))

    private companion object {
        /** Long enough for the dialog shell's enter animation to finish. */
        const val DIALOG_SETTLE_MS = 600L
    }

    @Test
    fun `the admin tab's info glyph explains the row and offers More`() = runComposeUiTest {
        val events = mutableListOf<SettingsEvent>()
        setContent {
            ZillitTheme { AdminSettingsScreen(state = state, onEvent = { events += it }, showHeader = false) }
        }

        onNodeWithContentDescription(str(S.desktop_sa_about_code, str(S.edit_project_name)))
            .performScrollTo()
            .performClick()
        onNodeWithText(str(S.edit_project_name_info)).assertExists()
        mainClock.advanceTimeBy(DIALOG_SETTLE_MS)
        onNodeWithText(str(S.more)).performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(
            listOf<SettingsEvent>(
                SettingsEvent.OpenLink(
                    "https://documentation.zillit.com/?for=admin&project_type=default#edit-project-name",
                ),
            ),
            events,
        )
    }

    @Test
    fun `the profile tab's info glyph explains the row`() = runComposeUiTest {
        setContent { ZillitTheme { SettingsScreen(state = state, onEvent = {}, showTitle = false) } }

        onNodeWithContentDescription(str(S.desktop_sa_about_code, str(S.desktop_cal_invite_crew))).performClick()

        onNodeWithText(str(S.desktop_info_invite_user)).assertExists()
    }
}
