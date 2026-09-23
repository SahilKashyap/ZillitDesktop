package com.zillit.desktop.feature.bankrec.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.ui.dialogs.BankRecDialogs
import com.zillit.desktop.feature.bankrec.ui.pages.ExceptionsPage
import com.zillit.desktop.feature.bankrec.ui.pages.FraudAlertsPage
import com.zillit.desktop.feature.bankrec.ui.pages.FxVariancesPage
import com.zillit.desktop.feature.bankrec.ui.pages.HistoryPage
import com.zillit.desktop.feature.bankrec.ui.pages.OpenBankingPage
import com.zillit.desktop.feature.bankrec.ui.pages.OverviewPage
import com.zillit.desktop.feature.bankrec.ui.pages.PortalPage
import com.zillit.desktop.feature.bankrec.ui.pages.SettingsPage
import com.zillit.desktop.feature.bankrec.ui.pages.workspace.WorkspacePage

/**
 * Bank Reconciliation.
 *
 * The web's module shell: its header, a tab strip whose chips come from the
 * notification ledger, and the active tab under it. The workspace is laid out
 * to the window and scrolls each panel itself; every other tab is one page
 * that scrolls whole — the summary travels with the list, as on the web.
 *
 * The workspace's full view gives the header and tabs back to the panels.
 * Escape leaves it, as it does on the web.
 */
@Suppress("CyclomaticComplexMethod") // One branch per tab.
@Composable
fun BankRecScreen(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val expanded = state.tab == BankTab.Workspace && state.workspace.expanded
    val focus = remember { FocusRequester() }
    LaunchedEffect(expanded) { if (expanded) runCatching { focus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(colors.canvas)
            .focusRequester(focus)
            .onKeyEvent { event ->
                val leave = event.type == KeyEventType.KeyDown && event.key == Key.Escape && expanded &&
                    !state.dialogOpen
                if (leave) onEvent(BankRecEvent.SetWorkspaceExpanded(false))
                leave
            }
            .focusable(),
    ) {
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = !expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                ModuleHeader(state, onEvent)
            }
            if (state.tab == BankTab.Workspace) {
                WorkspacePage(state, onEvent, Modifier.fillMaxWidth().weight(1f))
            } else {
                ZillitScrollColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    when (state.tab) {
                        BankTab.Overview -> OverviewPage(state, onEvent)
                        BankTab.Exceptions -> ExceptionsPage(state, onEvent)
                        BankTab.FraudAlerts -> FraudAlertsPage(state, onEvent)
                        BankTab.FxVariances -> FxVariancesPage(state, onEvent)
                        BankTab.History -> HistoryPage(state, onEvent)
                        BankTab.OpenBanking -> OpenBankingPage()
                        BankTab.GuarantorPortal -> PortalPage(state, onEvent)
                        BankTab.Settings -> SettingsPage(state, onEvent)
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
}

@Composable
private fun ModuleHeader(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.canvas)) {
        ZillitPageHeader(
            eyebrow = str(S.desktop_management),
            title = str(S.desktop_bank_reconciliation),
            description = str(S.desktop_br_page_description),
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 10.dp),
            actions = {
                ZillitIconButton(
                    icon = ZillitIcons.Reload,
                    contentDescription = str(S.refresh_text),
                    onClick = { onEvent(BankRecEvent.Refresh) },
                )
            },
        )
        ZillitTabStrip(
            tabs = BankTab.entries.map { tab ->
                ZillitTab(id = tab.slug, label = tab.label, count = tab.badgeKey?.let { state.badges[it] } ?: 0)
            },
            activeId = state.tab.slug,
            onSelect = { slug -> onEvent(BankRecEvent.OpenTab(BankTab.from(slug))) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        ZillitDivider()
    }
}
