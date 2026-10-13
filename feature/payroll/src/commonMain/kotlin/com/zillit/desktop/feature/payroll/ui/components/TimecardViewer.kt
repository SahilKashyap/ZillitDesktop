package com.zillit.desktop.feature.payroll.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.TimecardAttachment
import com.zillit.desktop.feature.payroll.ui.PayrollEvent
import com.zillit.desktop.feature.payroll.ui.PayrollUiState

/**
 * "View Time Card": the crew member's week in the wide read-only modal, over
 * whichever accountant screen asked — the web's `ReadOnlyWeeklyView` in an
 * `OverlayModal`, with the Notes button in its header.
 *
 * Composed at the screen root with the other dialogs, and after the
 * Outstanding detail, so it lands on top of the weeks it was opened from.
 */
@Composable
fun TimecardViewDialog(state: PayrollUiState, onEvent: (PayrollEvent) -> Unit) {
    val view = state.timecardView
    val shown = remember(view != null) { view } ?: view
    val open = view ?: shown
    val timecard = open?.timecard
    val name = timecard?.let { state.nameOf(it.userId) }.orEmpty()
    ZillitDialogShell(
        title = timecard?.let { titleOf(name, it) }.orEmpty(),
        icon = ZillitIcons.File,
        visible = view != null,
        width = VIEWER_WIDTH,
        maxHeight = VIEWER_MAX_HEIGHT,
        scrollable = false,
        onDismiss = { onEvent(PayrollEvent.CloseTimecardView) },
        headerTrailing = {
            if (timecard != null) {
                ZillitStatusPill(label = timecard.status.label, tone = timecard.status.tone, dot = true)
                TimecardNotesButton(state.viewer, timecard, name, onEvent)
            }
        },
    ) {
        if (open == null) return@ZillitDialogShell
        Box(Modifier.fillMaxWidth().height(VIEWER_BODY_HEIGHT)) {
            ReadOnlyWeeklyView(
                timecard = timecard.takeUnless { open.loading },
                loading = open.loading,
                emptyMessage = "",
                onOpenInvoice = { onEvent(PayrollEvent.OpenInvoice(it)) },
                holidayPay = state.holidayPayFor(timecard),
            )
        }
    }
}

/** `Name — W/E 10 May 2026`, the web's modal title. */
private fun titleOf(name: String, timecard: PayrollTimecard): String =
    timecard.weekStarting?.let { "$name — ${str(S.desktop_payroll_week_ending, PayPeriod.weekEnding(it))}" } ?: name

/**
 * The invoice a crew member attached to their week. Presence-gated on the
 * attachment itself, never on an employment-type guess about who might have
 * invoiced — the web's `InvoiceViewerButton`.
 */
@Composable
internal fun InvoiceViewButton(
    attachment: TimecardAttachment?,
    onOpen: (TimecardAttachment) -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Secondary,
) {
    if (attachment == null) return
    ZillitButton(
        text = str(S.desktop_payroll_view_invoice),
        onClick = { onOpen(attachment) },
        variant = variant,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Paperclip,
        modifier = modifier,
    )
}

/** The drawer and week-card button that opens [TimecardViewDialog]. */
@Composable
internal fun ViewTimecardButton(timecard: PayrollTimecard, onEvent: (PayrollEvent) -> Unit) {
    ZillitButton(
        text = str(S.desktop_payroll_view_time_card),
        onClick = { onEvent(PayrollEvent.OpenTimecardView(timecard)) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.File,
    )
}

private val VIEWER_WIDTH = 1280.dp
private val VIEWER_MAX_HEIGHT = 820.dp
private val VIEWER_BODY_HEIGHT = 640.dp
