package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState

/**
 * Every period the production has reconciled.
 *
 * The same table as Overview, without the current period's figures over it —
 * this tab is the record rather than the state of play.
 */
@Composable
fun ColumnScope.HistoryPage(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val signedOff = state.periods.count { !it.isOpen }
    ZillitNotice(
        text = "$signedOff of ${state.periods.size} period(s) signed off. " +
            "Deleting an open period also removes its transactions, exceptions, " +
            "fraud alerts and FX variances.",
        tone = StatusTone.Neutral,
        icon = ZillitIcons.Info,
        modifier = Modifier.fillMaxWidth(),
    )
    PeriodTable(state, onEvent, title = "All periods")
}
