package com.zillit.desktop.feature.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.ui.AccountSummary
import com.zillit.desktop.feature.settings.ui.AdminSettingsScreen
import com.zillit.desktop.feature.settings.ui.AdminSettingsUiState
import com.zillit.desktop.feature.settings.ui.SettingsScreen
import com.zillit.desktop.feature.settings.ui.SettingsTab
import com.zillit.desktop.feature.settings.ui.SettingsTabsFrame
import com.zillit.desktop.feature.settings.ui.SettingsUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Settings as the web has it: a Profile Settings tab, and an Admin Settings
 * tab that only admins are offered.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsTabsRenderTest {

    private fun state(isAdmin: Boolean) = SettingsUiState(
        account = AccountSummary(fullName = "Aisha Khan", email = "aisha@example.com", isAdmin = isAdmin),
        admin = AdminSettingsUiState(pendingNewCrew = 2),
    )

    @Test
    fun `an admin sees both tabs, and can switch to administration`() = runComposeUiTest {
        var chosen: SettingsTab? = null
        setContent {
            ZillitTheme {
                SettingsTabsFrame(active = SettingsTab.Profile, state = state(isAdmin = true), onSelect = { chosen = it }) {
                    SettingsScreen(state = state(isAdmin = true), onEvent = {}, showTitle = false)
                }
            }
        }
        onNodeWithText(str(S.desktop_profile_settings)).assertExists()
        // The desktop's own additions live under the profile tab.
        onNodeWithText(str(S.theme_mode)).assertExists()
        onNodeWithText(str(S.admin_settings)).performClick()
        assertEquals(SettingsTab.Admin, chosen)
    }

    @Test
    fun `everyone else sees only their profile`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                SettingsTabsFrame(active = SettingsTab.Profile, state = state(isAdmin = false), onSelect = {}) {
                    SettingsScreen(state = state(isAdmin = false), onEvent = {}, showTitle = false)
                }
            }
        }
        onNodeWithText(str(S.desktop_profile_settings)).assertExists()
        onNodeWithText(str(S.admin_settings)).assertDoesNotExist()
    }

    @Test
    fun `the admin tab composes the administration listing`() = runComposeUiTest {
        setContent {
            ZillitTheme {
                SettingsTabsFrame(active = SettingsTab.Admin, state = state(isAdmin = true), onSelect = {}) {
                    AdminSettingsScreen(state = state(isAdmin = true), onEvent = {}, showHeader = false)
                }
            }
        }
        onNodeWithText(str(S.desktop_search_admin_settings)).assertExists()
    }
}
