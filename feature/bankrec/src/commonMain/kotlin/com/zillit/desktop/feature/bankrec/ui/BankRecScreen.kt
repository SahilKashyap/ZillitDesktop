package com.zillit.desktop.feature.bankrec.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.ui.pages.BankRecDialogs
import com.zillit.desktop.feature.bankrec.ui.pages.ExceptionsPage
import com.zillit.desktop.feature.bankrec.ui.pages.FraudAlertsPage
import com.zillit.desktop.feature.bankrec.ui.pages.FxVariancesPage
import com.zillit.desktop.feature.bankrec.ui.pages.HistoryPage
import com.zillit.desktop.feature.bankrec.ui.pages.OverviewPage
import com.zillit.desktop.feature.bankrec.ui.pages.PortalPage
import com.zillit.desktop.feature.bankrec.ui.pages.RulesPage
import com.zillit.desktop.feature.bankrec.ui.pages.WorkspacePage

/**
 * Bank Reconciliation.
 *
 * One month's statement against the ledger, over eight tabs: what is
 * reconciled, what is not, what the engine flagged, and what a foreign payment
 * cost. The Workspace is the surface the others feed into and out of.
 */
@Composable
fun BankRecScreen(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header(state, onEvent)
        ZillitTabStrip(
            tabs = BankTab.entries.map { ZillitTab(it.slug, it.label) },
            activeId = state.tab.slug,
            onSelect = { slug -> onEvent(BankRecEvent.OpenTab(BankTab.from(slug))) },
        )

        // The workspace draws its own two-panel layout to the window and
        // scrolls each side, so it is not put inside a scrolling column —
        // nesting one inside another gives the inner list an infinite height
        // to measure against, which Compose refuses outright.
        if (state.tab == BankTab.Workspace) {
            WorkspacePage(state, onEvent, modifier = Modifier.fillMaxWidth().weight(1f))
        } else {
            ZillitScrollColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                when (state.tab) {
                    BankTab.Overview -> OverviewPage(state, onEvent)
                    BankTab.Exceptions -> ExceptionsPage(state, onEvent)
                    BankTab.FraudAlerts -> FraudAlertsPage(state, onEvent)
                    BankTab.FxVariances -> FxVariancesPage(state, onEvent)
                    BankTab.History -> HistoryPage(state, onEvent)
                    BankTab.GuarantorPortal -> PortalPage(state, onEvent)
                    BankTab.Settings -> RulesPage(state, onEvent)
                    BankTab.Workspace -> Unit
                }
            }
        }
    }

    BankRecDialogs(state, onEvent)

    ZillitToast(
        message = state.notice,
        onDismiss = { onEvent(BankRecEvent.ClearNotice) },
        tone = ZillitToastTone.Success,
    )
}

@Composable
private fun Header(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    ZillitPageHeader(
        eyebrow = "Account Hub",
        title = "Bank Reconciliation",
        description = state.currentPeriod
            ?.let { "Working on ${state.periodLabel(it)}" }
            ?: "No period open.",
        actions = {
            ZillitButton(
                text = "Refresh",
                onClick = { onEvent(BankRecEvent.Refresh) },
                variant = ButtonVariant.Tertiary,
                leadingIcon = ZillitIcons.Reload,
                loading = state.periodsLoading,
            )
            ZillitButton(
                text = "Import statement",
                onClick = { onEvent(BankRecEvent.ComposeImport) },
                leadingIcon = ZillitIcons.Upload,
                enabled = state.canImport,
            )
        },
    )
}
