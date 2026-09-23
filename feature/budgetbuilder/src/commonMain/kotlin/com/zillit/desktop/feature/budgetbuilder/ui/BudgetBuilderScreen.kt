package com.zillit.desktop.feature.budgetbuilder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The Budget Builder's launch page.
 *
 * A page rather than the tool: the budget application is a complete web app
 * hosted in the desktop's embedded Chromium, in a window of its own. This
 * screen is what the workspace shows for the tool's tab — who may enter,
 * whether the environment knows where the tool lives, and the door.
 */
@Composable
fun BudgetBuilderScreen(state: BudgetBuilderUiState, onEvent: (BudgetBuilderEvent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        ZillitPageHeader(
            eyebrow = str(S.desktop_film_tools),
            title = str(S.desktop_bb_title),
            description = str(S.desktop_bb_description),
        )

        when {
            !state.configured -> ZillitNotice(
                // The web's wording for the same condition, kept identical so
                // support hears one sentence, not two.
                text = str(S.desktop_budget_builder_not_configured),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )

            state.viewer.isBlocked -> ZillitNotice(
                text = str(S.desktop_bb_no_access),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
            )

            state.offline -> ZillitNotice(
                text = str(S.desktop_bb_offline),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )

            else -> LaunchCard(state, onEvent)
        }
    }
}

@Composable
private fun LaunchCard(state: BudgetBuilderUiState, onEvent: (BudgetBuilderEvent) -> Unit) {
    ZillitSectionCard(
        title = str(S.desktop_bb_card_title),
        icon = ZillitIcons.BarChart,
        action = {
            ZillitButton(
                text = str(S.desktop_bb_open),
                onClick = { onEvent(BudgetBuilderEvent.Open) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.BarChart,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(
                text = str(S.desktop_bb_opens_in_window),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.viewer.ready && !state.viewer.canPost) {
                ZillitText(
                    text = str(S.desktop_bb_view_only),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
