package com.zillit.desktop.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * Administration — everything this production's coordinators control.
 *
 * A page of its own rather than a section on Settings, matching all three
 * clients that already have it. There are around twenty destinations here and
 * only one of them belongs to the person reading it; folding that into their
 * own preferences would bury the theme switch under the crew list.
 *
 * Reached from the rail, and only by admins — [SettingsUiState.account] decides,
 * and a non-admin who arrives by deep link is told rather than shown an empty
 * page.
 *
 * @param onBack null when this is the top of its window, which it is when
 *   opened from the rail. A chevron that goes nowhere in particular is worse
 *   than no chevron, and Settings is no longer "above" this page to return to.
 */
@Composable
fun AdminSettingsScreen(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val admin = state.admin

    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ZillitTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                Header(admin.production, onBack)

                if (!state.account.isAdmin) {
                    NotAnAdmin()
                    return@Column
                }

                // Recomputed only when something it reads changes: the
                // catalogue allocates ~20 rows, and rebuilding it on every
                // keystroke of an unrelated field is work nobody asked for.
                val groups = remember(admin.production, admin.pendingNewCrew, admin.pendingProfileChanges) {
                    adminSettingsEntries(admin.production, admin.pendingNewCrew, admin.pendingProfileChanges)
                }
                val visible = remember(groups, admin.query) { groups.matching(admin.query) }

                ZillitSearchField(
                    value = admin.query,
                    onValueChange = { onEvent(SettingsEvent.AdminSearchChanged(it)) },
                    placeholder = "Search administration settings",
                    modifier = Modifier.fillMaxWidth(),
                )

                // Only when there is actually a "Soon" tag on screen to explain.
                // Now that the administration pages are built, most productions
                // see no planned rows at all, and a notice about a tag nobody
                // can find reads as a page that failed to render something.
                if (visible.anyPlanned) PlannedNotice()

                if (visible.isEmpty()) {
                    NoEntriesMatched(admin.query)
                } else {
                    visible.forEach { group ->
                        SettingsEntryGroup(
                            group = group,
                            onOpen = { onEvent(SettingsEvent.OpenEntry(it)) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Back first, then the title.
 *
 * The window's own back control is in the workspace chrome, which a torn-off
 * window may not be showing. A page reached from another page carries its own
 * way out — and one opened straight from the rail has nowhere to go, so it
 * carries nothing.
 */
@Composable
private fun Header(production: ProductionFacts, onBack: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        if (onBack != null) {
            ZillitIconButton(
                icon = ZillitIcons.ChevronLeft,
                contentDescription = "Back",
                onClick = onBack,
            )
        }
        Box(
            Modifier
                .width(TITLE_ACCENT_WIDTH)
                .height(TITLE_ACCENT_HEIGHT)
                .clip(ZillitTheme.shapes.pill)
                .background(ZillitTheme.colors.accent),
        )
        Column {
            ZillitText(text = "Admin settings", style = ZillitTheme.typography.displayLarge)
            ZillitText(
                // Names the production, because an admin on three of them needs
                // to know which one they are about to change.
                text = production.name.takeIf { it.isNotBlank() }
                    ?.let { "What $it's coordinators control." }
                    ?: "What this production's coordinators control.",
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/**
 * For someone who reached the page without the rights for it.
 *
 * Says so plainly instead of rendering an empty list. Admin access is granted
 * and revoked while people are signed in — the phone client boots you back to
 * the dashboard mid-session when it happens — so this is a state a real user
 * lands in, not only a deep-link edge case.
 */
@Composable
private fun NotAnAdmin() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(
            icon = ZillitIcons.Settings,
            contentDescription = null,
            tint = ZillitTheme.colors.textMuted,
            size = EMPTY_GLYPH,
        )
        ZillitText(
            text = "This page is for the production's administrators.",
            style = ZillitTheme.typography.titleSmall,
        )
        ZillitText(
            text = "Ask a coordinator if you need something changed here.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

private val CONTENT_MAX_WIDTH = 780.dp
private val TITLE_ACCENT_WIDTH = 4.dp
private val TITLE_ACCENT_HEIGHT = 40.dp
private val EMPTY_GLYPH = 28.dp
