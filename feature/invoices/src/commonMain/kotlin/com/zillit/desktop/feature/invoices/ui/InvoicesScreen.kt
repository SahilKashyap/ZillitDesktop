// The page shell; the department and accountant bodies live under ui/pages.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.invoices.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.invoices.domain.HoldReason
import com.zillit.desktop.feature.invoices.ui.pages.AccountantPageContent
import com.zillit.desktop.feature.invoices.ui.pages.AssignSheet
import com.zillit.desktop.feature.invoices.ui.pages.DeleteDialog
import com.zillit.desktop.feature.invoices.ui.pages.DepartmentPage
import com.zillit.desktop.feature.invoices.ui.pages.EnterInvoiceDialog
import com.zillit.desktop.feature.invoices.ui.pages.BlockedProcessDialog
import com.zillit.desktop.feature.invoices.ui.pages.BulkUploadSheet
import com.zillit.desktop.feature.invoices.ui.pages.CreditDeleteDialog
import com.zillit.desktop.feature.invoices.ui.pages.CreditHistorySheet
import com.zillit.desktop.feature.invoices.ui.pages.CreditNotePreviewDialog
import com.zillit.desktop.feature.invoices.ui.pages.InboxReviewDialog
import com.zillit.desktop.feature.invoices.ui.pages.InvoiceDetailDialog
import com.zillit.desktop.feature.invoices.ui.pages.LedgerHistorySheet
import com.zillit.desktop.feature.invoices.ui.pages.QueryPanelSheet
import com.zillit.desktop.feature.invoices.ui.pages.QuickEntrySheet
import com.zillit.desktop.feature.invoices.ui.pages.PoReviewOverlay
import com.zillit.desktop.feature.invoices.ui.pages.ProcessSheet
import com.zillit.desktop.feature.invoices.ui.pages.RejectRunSheet
import com.zillit.desktop.feature.invoices.ui.pages.RunDetailDialog
import com.zillit.desktop.feature.invoices.ui.pages.RunAuthPickerSheet
import com.zillit.desktop.feature.invoices.ui.pages.SalesDeleteDialog
import com.zillit.desktop.feature.invoices.ui.pages.SalesInvoiceSheet
import com.zillit.desktop.feature.invoices.ui.pages.SetupConfirmSheets
import com.zillit.desktop.feature.invoices.ui.pages.TeamMemberSheet
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Invoices: the department board for crew, and for the accounts department the
 * web's sidebar over the register, the inbox, the approval queue and the
 * dashboard, plus the shared detail dialog.
 */
@Composable
fun InvoicesScreen(
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    nowMs: Long,
    /**
     * Leaves the module — the sidebar's back chip. Inside the Account Hub the
     * host returns to the hub; standalone it closes the tool's window.
     */
    onBack: () -> Unit = {},
) {
    val accountant = state.isAccountant
    val screenFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { screenFocus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.canvas)
            .focusRequester(screenFocus)
            .focusable()
            // `onKeyEvent`, not the preview: a focused text field consumes its
            // own keys first, so typing "n" in a search box types an n.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                handleShortcut(
                    shortcut = InvoiceShortcut.of(event.utf16CodePoint.toChar().toString(), event.isShiftPressed),
                    state = state,
                    onEvent = onEvent,
                    focusSearch = { runCatching { searchFocus.requestFocus() } },
                )
            },
    ) {
        if (accountant) {
            // The web's shell: the sidebar's cards down the left, and each
            // page — its own header included — in the column beside them.
            Row(modifier = Modifier.fillMaxSize()) {
                InvoiceSideRail(state = state, onEvent = onEvent, onBack = onBack)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(ZillitTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    // The coding screen carries its own top bar in the header's place.
                    if (state.ledger == null && state.credit.form == null) InvoicesPageHeader(state, onEvent)
                    InvoicesNotices(state, onEvent)
                    AccountantPageContent(state, onEvent, nowMs, searchFocus)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                InvoicesPageHeader(state, onEvent)
                InvoicesNotices(state, onEvent)
                DepartmentPage(state, onEvent)
            }
        }
        state.detail?.let { InvoiceDetailDialog(state, it, onEvent) }
        state.enter?.let { EnterInvoiceDialog(state, it, onEvent) }
        state.confirmDelete?.let { DeleteDialog(state, it, onEvent) }
        state.runDraft?.let { ProcessSheet(state, it, onEvent) }
        state.runDetail?.let { RunDetailDialog(state, it, onEvent) }
        // After the run, not before: rejecting is reached from inside it.
        state.rejectRun?.let { RejectRunSheet(it, onEvent) }
        state.assignFor?.let { AssignSheet(state, it, onEvent) }
        state.salesDraft?.let { SalesInvoiceSheet(state, it, onEvent) }
        state.confirmSalesDelete?.let { SalesDeleteDialog(it, onEvent) }
        state.review?.let { PoReviewOverlay(state, it, onEvent) }
        // After the review, not before: holding is reached from inside it, and
        // a dialog declared earlier would open behind the overlay that raised it.
        state.holdFor?.let { HoldDialog(it, onEvent) }
        state.ledger?.takeIf { it.historyOpen }?.let { LedgerHistorySheet(state, it, onEvent) }
        // Credit notes: the preview, then its history and the delete confirmation over it.
        state.credit.preview?.let { CreditNotePreviewDialog(state, it, onEvent) }
        CreditHistorySheet(state, onEvent)
        state.credit.confirmDelete?.let { CreditDeleteDialog(it, onEvent) }
        state.inboxReview?.let { InboxReviewDialog(state, it, onEvent) }
        BlockedProcessDialog(state, onEvent)
        // Enter Invoice hosts the upload panel on its own Upload tab; the sheet is the department's.
        state.bulkPick?.takeIf { state.enter == null }?.let { BulkUploadSheet(it, onEvent) }
        state.quickEntry?.let { QuickEntrySheet(state, it, onEvent) }
        // Last of the record's sheets: a query is raised from over any of them.
        state.query?.let { QueryPanelSheet(state, it, onEvent) }
        TeamMemberSheet(state, onEvent)
        RunAuthPickerSheet(state, onEvent)
        SetupConfirmSheets(state, onEvent)
        if (state.shortcutsOpen) ShortcutsDialog(onEvent)
    }
}

/**
 * The page's own header — the web's `PageHeader`: an eyebrow naming the
 * stage, the screen's title and its own sentence, rather than one module
 * heading over all sixteen. It sits in the content column, beside the
 * sidebar, as each web page renders its own.
 */
@Composable
private fun InvoicesPageHeader(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val accountant = state.isAccountant
    ZillitPageHeader(
        title = if (accountant) state.page.heading else str(S.ah_invoices),
        eyebrow = state.page.eyebrow.takeIf { accountant },
        description = if (accountant) {
            state.page.blurb
        } else {
            str(S.desktop_inv_department_intro)
        },
        // No Refresh: every page re-reads on its own socket stream, as the web's do.
        actions = {
            if (accountant && state.page == AccountantPage.Inbox) {
                ZillitButton(
                    text = str(S.desktop_enter_invoice),
                    onClick = { onEvent(InvoicesEvent.OpenEnter) },
                    leadingIcon = ZillitIcons.Add,
                )
            }
            if (!accountant && state.viewer.mayPost) {
                ZillitButton(
                    text = str(S.ah_upload_invoice),
                    onClick = { onEvent(InvoicesEvent.UploadInvoice) },
                    leadingIcon = ZillitIcons.Upload,
                    enabled = state.bulkPick == null,
                )
            }
        },
    )
}

/** The no-access notice and the page's error banner. */
@Composable
private fun InvoicesNotices(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    if (state.viewer.isBlocked) ZillitNotice(text = str(S.desktop_inv_no_access))
    state.error?.let { message ->
        ZillitNotice(
            text = message,
            tone = StatusTone.Rejected,
            action = {
                ZillitButton(
                    text = str(S.sync_action_dismiss),
                    onClick = { onEvent(InvoicesEvent.DismissError) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            },
        )
    }
}

/**
 * Holding an invoice for query — the web's `HoldForQueryModal`.
 *
 * A reason is required, and "Other" is refused without the notes that explain
 * it. A failed hold keeps what was typed, so the dialog stays until it lands.
 */
@Composable
private fun HoldDialog(request: HoldRequest, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = if (request.invoices.size == 1) {
            str(S.desktop_inv_hold_for_query)
        } else {
            str(S.desktop_inv_hold_n_invoices, request.invoices.size)
        },
        subtitle = str(S.desktop_inv_hold_subtitle),
        visible = true,
        onDismiss = { if (!request.busy) onEvent(InvoicesEvent.CancelHold) },
        icon = ZillitIcons.Warning,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InvoicesEvent.CancelHold) },
                variant = ButtonVariant.Tertiary,
                enabled = !request.busy,
            )
            ZillitButton(
                text = str(S.desktop_hold),
                onClick = { onEvent(InvoicesEvent.ConfirmHold) },
                enabled = request.isReady && !request.busy,
                loading = request.busy,
            )
        },
    ) {
        ZillitSelect(
            value = request.reason,
            options = listOf<HoldReason?>(null) + HoldReason.entries,
            onSelect = { reason -> reason?.let { onEvent(InvoicesEvent.HoldReasonChanged(it)) } },
            label = { it?.label ?: str(S.desktop_select_reason) },
            enabled = !request.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = request.notes,
            onValueChange = { onEvent(InvoicesEvent.HoldNotesChanged(it)) },
            label = if (request.reason?.needsNotes() == true) {
                str(S.desktop_inv_query_notes_required)
            } else {
                str(S.desktop_inv_query_notes)
            },
            singleLine = false,
            enabled = !request.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Acts on a keystroke, or lets it pass.
 *
 * Nothing fires while a dialog is up — it owns the keyboard — except that the
 * shortcuts sheet is what `?` opens in the first place.
 */
private fun handleShortcut(
    shortcut: InvoiceShortcut?,
    state: InvoicesUiState,
    onEvent: (InvoicesEvent) -> Unit,
    focusSearch: () -> Unit,
): Boolean {
    if (shortcut == null || state.dialogOpen) return false
    when (shortcut) {
        InvoiceShortcut.Search -> focusSearch()
        InvoiceShortcut.Help -> onEvent(InvoicesEvent.OpenShortcuts)
        InvoiceShortcut.New -> when {
            // The accountant enters one; everyone else uploads theirs.
            state.isAccountant -> onEvent(InvoicesEvent.OpenEnter)
            state.viewer.mayPost -> onEvent(InvoicesEvent.UploadInvoice)
            else -> return false
        }
    }
    return true
}

/** What the footer advertises, spelled out — the sheet `?` opens. */
@Composable
private fun ShortcutsDialog(onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_keyboard_shortcuts),
        visible = true,
        onDismiss = { onEvent(InvoicesEvent.CloseShortcuts) },
        icon = ZillitIcons.Help,
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(InvoicesEvent.CloseShortcuts) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        InvoiceShortcut.entries.forEach { shortcut ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                KeyCap(shortcut.key)
                ZillitText(text = shortcut.label, style = ZillitTheme.typography.bodyMedium)
            }
        }
    }
}

/** One key, drawn as a key — the web's `<kbd>`. */
@Composable
private fun KeyCap(text: String) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.small)
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = 1.dp),
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = colors.textSecondary,
        )
    }
}
