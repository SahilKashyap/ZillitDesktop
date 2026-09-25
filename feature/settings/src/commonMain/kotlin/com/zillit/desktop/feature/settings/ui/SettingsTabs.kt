package com.zillit.desktop.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.TabStripSize
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The two halves of Settings, as the web has them (`SettingsTabs.jsx`).
 *
 * Profile Settings is the reader's own: their account, and everything this
 * computer adds on top of the web's page — theme, language, interface size,
 * notifications, the desktop widgets, the build. Admin Settings is the
 * production's, and exists only for its coordinators.
 */
enum class SettingsTab(val path: String) {
    Profile(SETTINGS_PATH),
    Admin(ADMIN_SETTINGS_PATH),
    ;

    val label: String
        get() = when (this) {
            Profile -> str(S.desktop_profile_settings)
            Admin -> str(S.admin_settings)
        }

    companion object {
        /**
         * The tab a route asks for, and the one it gets.
         *
         * A non-admin asking for the admin tab lands on their profile, as the
         * web's `handleTabChange` sends them — rights are revoked mid-session,
         * and a tab that has vanished cannot stay selected.
         */
        fun forPath(path: String, isAdmin: Boolean): SettingsTab =
            if (isAdmin && path.startsWith(ADMIN_SETTINGS_PATH)) Admin else Profile
    }
}

/**
 * The title, the tab strip, and the selected tab's page beneath them.
 *
 * The strip stays put while the page scrolls: switching halves is the one
 * thing a reader should never have to scroll back up to do.
 */
@Composable
fun SettingsTabsFrame(
    active: SettingsTab,
    state: SettingsUiState,
    onSelect: (SettingsTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tabs = buildList {
        add(ZillitTab(SettingsTab.Profile.name, SettingsTab.Profile.label))
        // Absent rather than disabled for everyone else, as on the web.
        if (state.account.isAdmin) {
            add(ZillitTab(SettingsTab.Admin.name, SettingsTab.Admin.label, count = state.admin.pendingTotal))
        }
    }

    Column(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.lg)
                .padding(top = ZillitTheme.spacing.lg),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = FRAME_MAX_WIDTH).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    Box(
                        Modifier
                            .width(TITLE_ACCENT_WIDTH)
                            .height(TITLE_ACCENT_HEIGHT)
                            .clip(ZillitTheme.shapes.pill)
                            .background(ZillitTheme.colors.accent),
                    )
                    Column {
                        ZillitText(text = str(S.settings), style = ZillitTheme.typography.displayLarge)
                        ZillitText(
                            text = when (active) {
                                SettingsTab.Profile -> str(S.desktop_settings_page_subtitle)
                                SettingsTab.Admin -> adminSubtitle(state.admin.production)
                            },
                            style = ZillitTheme.typography.bodyMedium,
                            color = ZillitTheme.colors.textMuted,
                        )
                    }
                }
                ZillitTabStrip(
                    tabs = tabs,
                    activeId = active.name,
                    onSelect = { id -> SettingsTab.entries.firstOrNull { it.name == id }?.let(onSelect) },
                    size = TabStripSize.Primary,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

private val FRAME_MAX_WIDTH = 780.dp
private val TITLE_ACCENT_WIDTH = 4.dp
private val TITLE_ACCENT_HEIGHT = 40.dp
