// Pieces of the Register, Pre-approval and Approval Queue pages that the web
// builds as their own small components.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.DateWindow
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceExport
import com.zillit.desktop.feature.invoices.domain.InvoiceExportFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceStatus
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

/**
 * The web's `ExportMenu` trigger (`RegisterPage.jsx:454-467`): one "Export"
 * button — "Exporting…" while the file is being made — dropping the PDF and
 * Excel choices.
 */
@Composable
internal fun ExportMenu(export: InvoiceExport, busy: Boolean, onEvent: (InvoicesEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ZillitButton(
            text = if (busy) str(S.desktop_exporting) else str(S.asset_export),
            onClick = { open = true },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Download,
            enabled = !busy,
            loading = busy,
        )
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = InvoiceExportFormat.entries.map { format ->
                ZillitMenuEntry.Action(
                    label = format.label,
                    icon = ZillitIcons.File,
                    onClick = { onEvent(InvoicesEvent.Export(export, format)) },
                )
            },
        )
    }
}

/**
 * The Register's department filter — the web's `DepartmentFilterSelect`: a
 * searchable select over every department in the directory's order, headed
 * by "All departments", which is also what clearing it goes back to.
 */
@Composable
internal fun RegisterDepartmentFilter(
    state: InvoicesUiState,
    value: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    ZillitSearchSelect(
        value = value ?: ALL_DEPARTMENTS,
        options = listOf(ALL_DEPARTMENTS) + state.registerDepartmentOptions,
        onSelect = { onSelect(it.ifBlank { null }) },
        label = { id -> if (id == ALL_DEPARTMENTS) str(S.desktop_inv_all_departments) else state.departmentName(id) },
        placeholder = str(S.desktop_inv_search_department),
        modifier = modifier,
    )
}

/** The Register's date options, in the web's own words (`RegisterPage.jsx:447-452`). */
internal fun registerDateLabel(window: DateWindow): String = when (window) {
    DateWindow.All -> str(S.desktop_inv_all_dates)
    DateWindow.Week -> str(S.desktop_inv_this_week)
    DateWindow.Month -> str(S.desktop_inv_this_month)
    DateWindow.Last30 -> str(S.desktop_inv_last_30_days)
    DateWindow.Last90 -> str(S.desktop_inv_last_90_days)
}

/**
 * One amber banner per held row, under the Pre-approval table
 * (`MatchingPage.jsx:766-775`): "`ref` · `vendor` — ON HOLD", the reason in
 * bold and the notes after it, and Release.
 */
@Composable
internal fun HoldBanner(state: InvoicesUiState, invoice: Invoice, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.warningSoft, ZillitTheme.shapes.large)
            .border(1.dp, colors.warning.copy(alpha = BANNER_BORDER_ALPHA), ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(ICON_WASH).background(colors.surface, ZillitTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = ZillitIcons.Warning, tint = colors.warning, size = ICON_SIZE)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(
                text = str(S.desktop_inv_on_hold_banner, invoice.displayNumber, state.pageVendorName(invoice)),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = colors.warning,
                maxLines = 1,
            )
            ZillitText(
                text = buildAnnotatedString {
                    append(str(S.desktop_inv_reason_label))
                    append(" ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.textSecondary)) {
                        append(invoice.holdReason)
                    }
                    if (invoice.holdNote.isNotBlank()) {
                        append(" · ")
                        append(str(S.desktop_inv_notes_label))
                        append(" ")
                        append(invoice.holdNote)
                    }
                },
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 2,
            )
        }
        ZillitButton(
            text = str(S.desktop_release),
            onClick = { onEvent(InvoicesEvent.Release(invoice)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = !state.busy,
        )
    }
}

/**
 * Pre-approval's bulk bar (`MatchingPage.jsx:781-818`): the count, Send for
 * Approval ("Sending..." while it runs), Hold for Query and Clear — both
 * actions shut while either is in flight.
 */
@Composable
internal fun MatchingBulkBar(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val count = state.selected.size
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, ZillitTheme.shapes.large)
            .border(1.dp, colors.accent.copy(alpha = BANNER_BORDER_ALPHA), ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = countMeta(count, S.desktop_inv_one_invoice_selected, S.desktop_inv_n_invoices_selected_many),
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = if (state.busy) str(S.desktop_inv_sending) else str(S.cs_send_for_approval),
            onClick = { onEvent(InvoicesEvent.SendToApproval(null)) },
            size = ButtonSize.Small,
            enabled = !state.busy && state.holdFor?.busy != true,
            loading = state.busy,
        )
        ZillitButton(
            text = str(S.desktop_inv_hold_for_query_title),
            onClick = { onEvent(InvoicesEvent.StartHold(null)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = !state.busy && state.holdFor?.busy != true,
        )
        ZillitButton(
            text = str(S.ah_clear),
            onClick = { onEvent(InvoicesEvent.ClearSelection) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Close,
        )
    }
}

/**
 * The Approval Queue's hard-delete confirmation (`ApprovalPage.jsx:763-773`):
 * "Delete invoice?", the invoice and its vendor named, "Deleting…" while it
 * runs, and no way out until it answers.
 */
@Composable
internal fun QueueDeleteDialog(state: InvoicesUiState, invoice: Invoice, onEvent: (InvoicesEvent) -> Unit) {
    val deleting = state.busy
    ZillitDialogShell(
        title = str(S.desktop_inv_delete_invoice_title),
        onDismiss = { if (!deleting) onEvent(InvoicesEvent.CancelDelete) },
        visible = true,
        icon = ZillitIcons.Trash,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InvoicesEvent.CancelDelete) },
                variant = ButtonVariant.Tertiary,
                enabled = !deleting,
            )
            ZillitButton(
                text = if (deleting) str(S.ah_deleting) else str(S.delete),
                onClick = { onEvent(InvoicesEvent.ConfirmDelete) },
                variant = ButtonVariant.Danger,
                loading = deleting,
            )
        },
    ) {
        ZillitText(
            text = str(S.desktop_inv_delete_invoice_message, invoice.displayNumber, state.pageVendorName(invoice)),
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}

/**
 * The Approval Queue's Chase (`ApprovalPage.jsx:678-686`): "Chasing…" while
 * this row's chase is out (and every other row's Chase shut meanwhile), a
 * green "Chased" for two seconds after, then Chase again.
 */
@Composable
internal fun ChaseControl(state: InvoicesUiState, invoice: Invoice, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    if (invoice.id in state.chased) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = ZillitTheme.spacing.xs),
        ) {
            ZillitIcon(icon = ZillitIcons.Check, tint = colors.success, size = ICON_SIZE_SMALL)
            ZillitText(
                text = str(S.desktop_chased),
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = colors.success,
            )
        }
        return
    }
    val chasing = state.chasing == invoice.id
    ZillitButton(
        text = if (chasing) str(S.dm_row_action_chasing) else str(S.dm_row_action_chase),
        onClick = { onEvent(InvoicesEvent.Chase(invoice)) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        enabled = state.chasing == null,
        loading = chasing,
    )
}

/** Pre-approval's rows in the web's two groups: waiting first, then held. */
internal fun List<Invoice>.heldRows(): List<Invoice> = filter { it.status == InvoiceStatus.Held }

/** "All departments" in the filter's option list — never a department id. */
private const val ALL_DEPARTMENTS = ""
private const val BANNER_BORDER_ALPHA = 0.35f
private val ICON_WASH = 40.dp
private val ICON_SIZE = 20.dp
private val ICON_SIZE_SMALL = 14.dp
