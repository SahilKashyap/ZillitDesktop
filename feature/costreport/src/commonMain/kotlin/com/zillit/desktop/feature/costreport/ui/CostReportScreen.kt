package com.zillit.desktop.feature.costreport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.costreport.domain.CostReportTab

/**
 * The crew tool — the web's `CostReportToolModule`: the shared header bar
 * with Current CR and Posted CRs, and a posted snapshot opening over both.
 */
@Composable
fun CostReportScreen(
    state: CostReportUiState,
    onEvent: (CostReportEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        val snapshot = state.snapshot
        if (snapshot != null) {
            Box(Modifier.fillMaxSize().padding(24.dp)) {
                SnapshotPage(
                    view = snapshot,
                    backLabel = str(S.cr_tab_posted),
                    resolveUser = resolveUser,
                    callbacks = SnapshotCallbacks(
                        onBack = { onEvent(CostReportEvent.CloseSnapshot) },
                        onExport = { onEvent(CostReportEvent.Export(it)) },
                        onSearch = { onEvent(CostReportEvent.SearchSnapshot(it)) },
                        onToggleSection = { onEvent(CostReportEvent.ToggleSnapshotSection(it)) },
                        onToggleHeader = { onEvent(CostReportEvent.ToggleSnapshotHeader(it)) },
                        onDismissError = { onEvent(CostReportEvent.DismissError) },
                    ),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                CrHeaderBar(
                    tabs = listOf(
                        CrHeaderTab(CostReportTab.Current.id, CostReportTab.Current.label, ZillitIcons.Monitor),
                        CrHeaderTab(CostReportTab.Posted.id, CostReportTab.Posted.label, ZillitIcons.Clock),
                    ),
                    activeId = state.tab.id,
                    onTab = { id -> onEvent(CostReportEvent.SelectTab(CostReportTab.entries.first { it.id == id })) },
                    onAnalytics = { onEvent(CostReportEvent.OpenAnalytics) },
                )
                if (state.viewer.isBlocked) {
                    ZillitNotice(
                        text = str(S.desktop_cr_no_access),
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (state.tab) {
                        CostReportTab.Current -> CurrentCrPane(state, onEvent)
                        CostReportTab.Posted -> Box(
                            Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp),
                        ) {
                            PostedCrPane(state, resolveUser, onEvent)
                        }
                    }
                }
            }
        }
        state.ledger?.let { LedgerDialog(it) { onEvent(CostReportEvent.CloseLedger) } }
    }
}
