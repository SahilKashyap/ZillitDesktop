package com.zillit.desktop.feature.payroll.ui.run

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.RowAction
import com.zillit.desktop.feature.payroll.domain.RunAction
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import com.zillit.desktop.feature.payroll.domain.rowActionFor
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollUiState
import com.zillit.desktop.feature.payroll.ui.RunConfirm
import com.zillit.desktop.feature.payroll.ui.RunEvent
import com.zillit.desktop.feature.payroll.ui.components.CrewDrawer
import com.zillit.desktop.feature.payroll.ui.components.DialogActions

/**
 * The Run's drawer: the shared crew drawer with the Run's footer — the row's
 * own action, and Unlock beside Mark Paid on a locked week for the payroll
 * accountant. No Mark Unpaid here: on the web that lives on the row.
 */
@Composable
internal fun RunDrawer(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit, modifier: Modifier) {
    CrewDrawer(
        state = state,
        timecard = state.run.drawer,
        loading = state.run.drawerLoading,
        onClose = { onEvent(RunEvent.OpenDrawer(null)) },
        onEvent = onEvent,
        modifier = modifier,
    ) { timecard ->
        val busy = state.run.busyRowId == timecard.id
        if (timecard.status == TimecardStatus.Locked && state.viewer.isPayrollAccountant) {
            ZillitButton(
                text = str(S.unlock_txt),
                onClick = { onEvent(RunEvent.Unlock(timecard.id)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = state.run.busyRowId == null,
                loading = busy,
            )
        }
        val action = rowActionFor(timecard.status, state.viewer, state.canOverride)
        if (action != RowAction.View && action != RowAction.MarkUnpaid) {
            ZillitButton(
                text = if (action == RowAction.Override) str(S.desktop_payroll_override_approval) else action.label,
                onClick = { onEvent(RunEvent.Row(timecard.id, action)) },
                size = ButtonSize.Small,
                enabled = state.run.busyRowId == null,
                loading = busy,
            )
        }
    }
}

/**
 * The toolbar's confirm — the web's `ApprovalConfirmModal`: what the batch
 * would move out of what was selected, and what that means for the crew.
 */
@Composable
internal fun RunConfirmDialog(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val confirm = state.run.confirm
    val shown = remember(confirm != null) { confirm } ?: confirm
    ZillitDialogShell(
        title = shown?.title().orEmpty(),
        icon = if (shown?.action == RunAction.MarkPaid) ZillitIcons.Check else ZillitIcons.Lock,
        visible = confirm != null,
        width = DIALOG_WIDTH,
        onDismiss = { if (confirm?.working != true) onEvent(RunEvent.DismissConfirm) },
    ) {
        val open = confirm ?: shown ?: return@ZillitDialogShell
        ZillitText(
            text = open.intro(state),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitText(text = open.impact(), style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
        DialogActions(
            cancel = { onEvent(RunEvent.DismissConfirm) },
            confirmText = if (open.working) str(S.desktop_working_ellipsis) else open.buttonText(),
            confirm = { onEvent(RunEvent.Confirm) },
            busy = open.working,
            enabled = open.ids.isNotEmpty(),
        )
    }
}

private fun RunConfirm.title(): String = when (action) {
    RunAction.FinalApprove -> str(S.desktop_payroll_confirm_approve_title, ids.size)
    RunAction.FinalApproveAndLock -> str(S.desktop_payroll_confirm_approve_lock_title, ids.size)
    RunAction.Lock -> str(S.desktop_payroll_confirm_lock_title, ids.size)
    RunAction.MarkPaid -> str(S.desktop_payroll_confirm_paid_title, ids.size)
}

private fun RunConfirm.buttonText(): String {
    val base = when (action) {
        RunAction.MarkPaid -> "${str(S.desktop_mark_paid)} · ${ids.size}"
        else -> "${action.label} ${ids.size}"
    }
    return if (thenPost) str(S.desktop_payroll_and_post, base) else base
}

private fun RunConfirm.intro(state: PayrollUiState): String {
    val scope = if (runScope) {
        str(S.desktop_payroll_scope_run, ids.size, selectedTotal)
    } else {
        str(S.desktop_payroll_scope_selected, ids.size, selectedTotal)
    }
    return when (action) {
        RunAction.FinalApprove -> str(S.desktop_payroll_intro_approve, scope)
        RunAction.FinalApproveAndLock -> str(S.desktop_payroll_intro_approve_lock, scope)
        RunAction.Lock -> str(S.desktop_payroll_intro_lock, scope)
        RunAction.MarkPaid -> {
            val statuses = state.run.timecards.filter { it.id in ids }.map { it.status }.toSet()
            val source = when {
                TimecardStatus.Locked in statuses && TimecardStatus.Unpaid in statuses ->
                    str(S.desktop_payroll_source_both)
                TimecardStatus.Unpaid in statuses -> str(S.desktop_payroll_source_unpaid)
                else -> str(S.desktop_payroll_source_locked)
            }
            str(S.desktop_payroll_intro_paid, scope, source)
        }
    }
}

private fun RunConfirm.impact(): String = when (action) {
    RunAction.FinalApprove -> str(S.desktop_payroll_impact_approve)
    RunAction.FinalApproveAndLock, RunAction.Lock -> str(S.desktop_payroll_impact_lock)
    RunAction.MarkPaid -> str(S.desktop_payroll_impact_paid)
}

private val DIALOG_WIDTH = 480.dp
