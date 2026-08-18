// The shell: header, tabs, and the dialogs that branch on the state.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.costreport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.feature.costreport.domain.CostReportTab

/** Cost Report: the live worksheet and the posted timeline, one tab each; a snapshot opens over both. */
@Composable
fun CostReportScreen(
    state: CostReportUiState,
    onEvent: (CostReportEvent) -> Unit,
    resolveUser: (String) -> String?,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            val snapshot = state.snapshot
            if (snapshot != null) {
                SnapshotPage(state, snapshot, resolveUser, onEvent)
            } else {
                ZillitPageHeader(
                    eyebrow = "Reports",
                    title = "Cost Report",
                    description = "See the live project cost report and review every posted snapshot in one place.",
                )
                if (state.viewer.isBlocked) ZillitNotice(text = "You do not have access to the Cost Report tool.")
                ZillitTabStrip(
                    tabs = CostReportTab.entries.map { ZillitTab(it.id, it.label) },
                    activeId = state.tab.id,
                    onSelect = { id ->
                        onEvent(CostReportEvent.SelectTab(CostReportTab.entries.first { it.id == id }))
                    },
                )
                when (state.tab) {
                    CostReportTab.Current -> CurrentCrPane(state, onEvent)
                    CostReportTab.Posted -> PostedCrPane(state, resolveUser, onEvent)
                }
            }
        }
        state.ledger?.let { LedgerDialog(it, onEvent) }
    }
}
