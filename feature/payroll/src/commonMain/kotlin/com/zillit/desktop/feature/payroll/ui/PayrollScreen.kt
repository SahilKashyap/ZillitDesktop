package com.zillit.desktop.feature.payroll.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.feature.payroll.ui.components.AdjustmentsDialog
import com.zillit.desktop.feature.payroll.ui.components.OverrideDialog
import com.zillit.desktop.feature.payroll.ui.history.HistoryPage
import com.zillit.desktop.feature.payroll.ui.landing.PayrollLandingPage
import com.zillit.desktop.feature.payroll.ui.processing.OutstandingDetailDialog
import com.zillit.desktop.feature.payroll.ui.processing.ProcessingExportDialog
import com.zillit.desktop.feature.payroll.ui.processing.ProcessingPage
import com.zillit.desktop.feature.payroll.ui.run.JournalAlertDialog
import com.zillit.desktop.feature.payroll.ui.run.JournalPostDialog
import com.zillit.desktop.feature.payroll.ui.run.RunConfirmDialog
import com.zillit.desktop.feature.payroll.ui.run.RunPage

/**
 * The Payroll tool: the landing, or the screen its route names.
 *
 * Every dialog is composed here, at the root, over whichever page is open —
 * a dialog shell composed inside a scrolling page draws at the foot of the
 * page rather than over it. Later dialogs sit on top of earlier ones, so the
 * claims dialog opened from the outstanding weeks lands over them.
 */
@Composable
fun PayrollScreen(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        when (state.destination) {
            PayrollDestination.Landing -> PayrollLandingPage(state, onEvent)
            PayrollDestination.Processing -> ProcessingPage(state, onEvent)
            PayrollDestination.Run -> RunPage(state, onEvent)
            PayrollDestination.History -> HistoryPage(state, onEvent)
        }
        RunConfirmDialog(state, onEvent)
        JournalAlertDialog(state, onEvent)
        JournalPostDialog(state, onEvent)
        ProcessingExportDialog(state, onEvent)
        OutstandingDetailDialog(state, onEvent)
        OverrideDialog(state, onEvent)
        AdjustmentsDialog(state, onEvent)
        ZillitToast(
            message = state.notice,
            onDismiss = { onEvent(PayrollEvent.ClearNotice) },
            tone = ZillitToastTone.Success,
        )
    }
}
