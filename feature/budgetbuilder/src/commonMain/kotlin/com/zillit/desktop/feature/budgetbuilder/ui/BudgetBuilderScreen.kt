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
            eyebrow = "Film Tools",
            title = "Budget Builder",
            description = "Native film budget creation — chart of accounts, fringes, " +
                "multi-currency, and a published version the Cost Report reads.",
        )

        when {
            !state.configured -> ZillitNotice(
                // The web's wording for the same condition, kept identical so
                // support hears one sentence, not two.
                text = "Budget Builder isn’t configured for this environment.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )

            state.viewer.isBlocked -> ZillitNotice(
                text = "You don’t have access to Budget Builder on this production. " +
                    "Access is granted per tool, by the production’s admin.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
            )

            else -> LaunchCard(state, onEvent)
        }
    }
}

@Composable
private fun LaunchCard(state: BudgetBuilderUiState, onEvent: (BudgetBuilderEvent) -> Unit) {
    ZillitSectionCard(
        title = "The budget application",
        icon = ZillitIcons.BarChart,
        action = {
            ZillitButton(
                text = "Open Budget Builder",
                onClick = { onEvent(BudgetBuilderEvent.Open) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.BarChart,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(
                text = "Opens in its own window. Your Zillit session is the identity — " +
                    "there is no separate sign-in, and the budget belongs to this production.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.viewer.ready && !state.viewer.canPost) {
                ZillitText(
                    text = "You hold view access only — the budget opens read-only.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
