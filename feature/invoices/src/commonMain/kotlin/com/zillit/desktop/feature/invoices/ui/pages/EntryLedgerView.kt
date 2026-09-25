// Invoice Entry's coding screen: the top bar, the document, and the coding column.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollRail
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.CountryCurrency
import com.zillit.desktop.feature.invoices.domain.EntryCheck
import com.zillit.desktop.feature.invoices.domain.EntryCoding
import com.zillit.desktop.feature.invoices.domain.EntryHeader
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceLabels
import com.zillit.desktop.feature.invoices.domain.LinkedPoDetail
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PeriodLock
import com.zillit.desktop.feature.invoices.ui.EntryAction
import com.zillit.desktop.feature.invoices.ui.EntryEvent
import com.zillit.desktop.feature.invoices.ui.EntryLedger
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.QueryEvent
import com.zillit.desktop.feature.invoices.ui.decodePreviewPages
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * The web's full-page ledger entry view (`EntryDetailModal`, `fullPage`).
 *
 * It takes the queue's place in the content column: a top bar with the way
 * back, the breadcrumb and every action; the invoice document on the left
 * when there is one, a third of the width; and the coding column. A posted
 * invoice, or one dated in a closed cost-report period, is frozen — History
 * and Query stay, nothing else does.
 */
@Composable
internal fun ColumnScope.EntryLedgerView(
    state: InvoicesUiState,
    ledger: EntryLedger,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val locked = state.isLocked(ledger.invoice)
    val frozen = ledger.readOnly || locked
    // Banks or companies that land after the view opened still fill (`:512-517`).
    LaunchedEffect(state.banks, state.companies, ledger.loading) {
        if (!ledger.loading) onEvent(EntryEvent.AutoFill)
    }
    LedgerTopBar(state, ledger, locked, onEvent)
    ledger.problem?.let { message ->
        ZillitNotice(
            text = message,
            tone = StatusTone.Rejected,
            action = {
                ZillitButton(
                    text = str(S.sync_action_dismiss),
                    onClick = { onEvent(EntryEvent.DismissProblem) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            },
        )
    }
    Row(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        // The document takes a third only when there is one to show (`w-1/3`).
        if (ledger.invoice.firstAttachment != null) {
            DocumentPane(ledger, onEvent, Modifier.weight(DOCUMENT_SHARE).fillMaxHeight())
        }
        Box(Modifier.weight(CODING_SHARE).fillMaxHeight()) {
            if (ledger.loading) {
                ZillitSpinner(modifier = Modifier.align(Alignment.Center))
            } else {
                CodingColumn(state, ledger, frozen, onEvent)
            }
        }
    }
}

/** What the coding screen raises over everything — the fullscreen document. */
@Composable
internal fun LedgerOverlays(ledger: EntryLedger, onEvent: (InvoicesEvent) -> Unit) {
    if (ledger.fullscreen) FullscreenDocument(ledger, onEvent)
}

// -- the top bar ----------------------------------------------------------------

@Composable
private fun LedgerTopBar(
    state: InvoicesUiState,
    ledger: EntryLedger,
    locked: Boolean,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(BACK_CHIP)
                .clip(ZillitTheme.shapes.medium)
                .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIconButton(
                icon = ZillitIcons.ArrowLeft,
                contentDescription = str(S.back),
                onClick = { onEvent(EntryEvent.Close) },
            )
        }
        ZillitText(
            text = str(S.desktop_inv_eyebrow_invoices_ap).uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.accentText,
            maxLines = 1,
        )
        Crumb("/")
        ZillitText(text = str(S.desktop_invoice_entry), style = ZillitTheme.typography.bodySmall, maxLines = 1)
        Crumb("/")
        ZillitText(
            text = ledger.invoice.displayNumber,
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Crumb("· ${state.vendorName(ledger.invoice)}")
        ZillitStatusPill(label = str(S.desktop_inv_all_validated), tone = StatusTone.Ready)
        when {
            locked -> ZillitStatusPill(
                label = str(S.desktop_inv_locked_period_banner, state.periodLock.lockedThrough),
                tone = StatusTone.Escalated,
            )
            ledger.readOnly -> ZillitStatusPill(label = str(S.desktop_inv_posted_read_only), tone = StatusTone.Neutral)
        }
        Spacer(Modifier.weight(1f))
        LedgerActions(state, ledger, frozen = ledger.readOnly || locked, onEvent = onEvent)
    }
}

@Composable
private fun Crumb(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
    )
}

/**
 * History and Query always; the rest only while the invoice can change.
 * Submit for Review is a junior's hand-off, so a senior is not offered it,
 * and Post needs the right to post (ZL-20450, ZL-20767). Save and Post say
 * why they would be refused, as the web's `title` hints do (`:1158`, `:1169`).
 */
@Composable
private fun LedgerActions(
    state: InvoicesUiState,
    ledger: EntryLedger,
    frozen: Boolean,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val action = ledger.action
    val mismatch = !ledger.currencyChanged(state.projectCurrency) && EntryCoding.amountMismatch(
        ledger.totals(state.taxTypes, state.taxTypesKnown).gross,
        ledger.invoice.grossAmount,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        SmallButton(str(S.history), ZillitIcons.Clock) { onEvent(EntryEvent.ShowHistory) }
        SmallButton(str(S.ah_query_label), ZillitIcons.Chat) { onEvent(QueryEvent.Open(ledger.invoice)) }
        if (frozen) return@Row
        ZillitTooltip(text = if (mismatch) str(S.desktop_inv_lines_must_match_save) else "") {
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(EntryEvent.Save) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = action == null,
                loading = action == EntryAction.Saving,
            )
        }
        if (!state.viewer.isSenior) {
            ZillitButton(
                text = str(S.desktop_submit_for_review),
                onClick = { onEvent(EntryEvent.SubmitForReview) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = action == null,
                loading = action == EntryAction.Reviewing,
            )
        }
        ZillitButton(
            text = if (ledger.invoice.assignedTo.isBlank()) str(S.assign) else str(S.desktop_po_reassign),
            onClick = { onEvent(EntryEvent.Assign) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = action == null,
        )
        ZillitButton(
            text = str(
                if (action == EntryAction.Returning) S.desktop_inv_returning else S.desktop_inv_return_to_approval,
            ),
            onClick = { onEvent(EntryEvent.ReturnToApproval) },
            variant = ButtonVariant.Danger,
            size = ButtonSize.Small,
            enabled = action == null,
        )
        if (state.viewer.canPostToLedger) {
            val hint = when {
                ledger.header.bankId.isBlank() -> str(S.desktop_inv_bank_before_post)
                ledger.header.effectiveDate.isBlank() -> str(S.desktop_po_effective_date_before_posting)
                mismatch -> str(S.desktop_inv_lines_must_match_post)
                else -> ""
            }
            ZillitTooltip(text = hint) {
                ZillitButton(
                    text = str(S.ah_post_to_ledger),
                    onClick = { onEvent(EntryEvent.Post) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Check,
                    // The bank is required at post; the button says so by staying off.
                    enabled = action == null && ledger.header.bankId.isNotBlank(),
                    loading = action == EntryAction.Posting,
                )
            }
        }
    }
}

@Composable
private fun SmallButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ZillitButton(
        text = text,
        onClick = onClick,
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = icon,
    )
}

// -- the document --------------------------------------------------------------

/** The invoice, inline — "Invoice" over it, and Fullscreen once there is something to show. */
@Composable
private fun DocumentPane(ledger: EntryLedger, onEvent: (InvoicesEvent) -> Unit, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val attachment = ledger.invoice.firstAttachment ?: return
    val pages = remember(ledger.preview) { ledger.preview?.bytes?.let(::decodePreviewPages).orEmpty() }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ZillitSectionLabel(str(S.ah_run_detail_col_invoice))
            Spacer(Modifier.weight(1f))
            if (pages.isNotEmpty()) {
                ZillitButton(
                    text = str(S.desktop_inv_fullscreen),
                    onClick = { onEvent(EntryEvent.ShowFullscreen) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(ZillitTheme.shapes.medium)
                .background(colors.surfaceSunken)
                .border(1.dp, colors.border, ZillitTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            when {
                pages.isNotEmpty() -> PreviewPages(pages, attachment.name)
                ledger.previewLoading -> DocumentState(str(S.docusign_template_detail_loading), spinner = true)
                else -> DocumentState(str(S.desktop_inv_document_unavailable))
            }
        }
    }
}

@Composable
private fun DocumentState(text: String, spinner: Boolean = false) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        if (spinner) ZillitSpinner()
        ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
    }
}

/** The web's fullscreen viewer: "Image Viewer" or "PDF Viewer", the document filling the stage. */
@Composable
private fun FullscreenDocument(ledger: EntryLedger, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val attachment = ledger.invoice.firstAttachment ?: return
    val pages = remember(ledger.preview) { ledger.preview?.bytes?.let(::decodePreviewPages).orEmpty() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim.copy(alpha = SCRIM_ALPHA))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onEvent(EntryEvent.HideFullscreen) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(FULLSCREEN_SHARE)
                .clip(ZillitTheme.shapes.large)
                .background(colors.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceSunken)
                    .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = str(if (attachment.isImage) S.desktop_inv_image_viewer else S.desktop_inv_pdf_viewer),
                    style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = str(S.close),
                    onClick = { onEvent(EntryEvent.HideFullscreen) },
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f).background(colors.surfaceSunken)) {
                if (pages.isNotEmpty()) PreviewPages(pages, attachment.name)
            }
        }
    }
}

// -- the coding column ------------------------------------------------------------

@Composable
private fun CodingColumn(
    state: InvoicesUiState,
    ledger: EntryLedger,
    frozen: Boolean,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val scroll = rememberScrollState()
    val currencyChanged = ledger.currencyChanged(state.projectCurrency)
    Row(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(scroll).padding(end = ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            // Outside the frozen fields: a locked invoice's orders stay viewable.
            if (ledger.invoice.linkedPos.isNotEmpty()) DocumentsRow(state, ledger, onEvent)
            HeaderCard(state, ledger, frozen, onEvent)
            ledger.shownOrder?.let { OrdersCard(state, ledger, it, onEvent) }
            // The old gross is another currency's figure once the currency changed (`:1779`).
            if (!currencyChanged) {
                val totals = ledger.totals(state.taxTypes, state.taxTypesKnown)
                GrossMatch(
                    invoiceGross = ledger.invoice.grossAmount,
                    coded = totals.gross,
                    currency = currencyOf(state, ledger),
                )
            }
            EntryLinesCard(state, ledger, frozen, onEvent)
            ValidationCard(state, ledger)
        }
        ZillitScrollRail(scroll)
    }
}

internal fun currencyOf(state: InvoicesUiState, ledger: EntryLedger): String =
    ledger.header.currency.ifBlank { ledger.invoice.currency }.ifBlank { state.projectCurrency }

/**
 * Documents — a card per linked order: "PO {no}", vendor · amount and its
 * description; a click opens the order read-only (`LinkedPoViewer`,
 * `EntryDetailModal.jsx:1297-1349`).
 */
@Composable
private fun DocumentsRow(state: InvoicesUiState, ledger: EntryLedger, onEvent: (InvoicesEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitSectionLabel(str(S.txt_documents))
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ledger.invoice.linkedPos.forEach { link ->
                val order = ledger.orders.firstOrNull { it.poId == link.poId }
                val number = order?.poNumber?.ifBlank { null } ?: link.poNumber
                val vendor = state.vendors[order?.vendorId.orEmpty()]?.name
                    ?: state.vendors[link.poVendorId]?.name
                    ?: order?.vendorName.orEmpty()
                val gross = order?.grossTotal ?: link.poGrossTotal
                val sub = listOfNotNull(
                    vendor.ifBlank { null },
                    gross?.let { amount ->
                        InvoiceFormat.money(amount, order?.currency?.ifBlank { null } ?: ledger.invoice.currency)
                    },
                ).joinToString(" · ")
                ZillitTooltip(text = str(S.desktop_inv_view_po_details)) {
                    PoDocumentCard(
                        title = str(S.desktop_inv_po_number, number),
                        subtitle = sub.ifBlank { str(S.view_details) },
                        description = order?.description.orEmpty(),
                    ) { onEvent(InvoicesEvent.OpenLinkedPo(link.poId, number)) }
                }
            }
        }
    }
}

@Composable
private fun PoDocumentCard(title: String, subtitle: String, description: String, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .widthIn(max = PO_CARD_MAX)
            .clip(ZillitTheme.shapes.large)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .background(colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(PO_CARD_ICON).clip(ZillitTheme.shapes.medium).background(colors.tealSoft),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = ZillitIcons.File, tint = colors.teal, size = CHECK_ICON)
        }
        Column {
            ZillitText(
                text = title,
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            ZillitText(
                text = subtitle,
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
            if (description.isNotBlank()) {
                ZillitText(
                    text = description,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Number, dates, pay method; then company, bank, currency and episode; then the document's own amounts. */
@Composable
private fun HeaderCard(state: InvoicesUiState, ledger: EntryLedger, frozen: Boolean, onEvent: (InvoicesEvent) -> Unit) {
    val header = ledger.header
    val edit: (EntryHeader) -> Unit = { onEvent(EntryEvent.EditHeader(it)) }
    ZillitSectionCard(title = str(S.ah_run_detail_col_invoice), icon = ZillitIcons.File) {
        FieldRow {
            ZillitTextField(
                value = header.invoiceNumber,
                onValueChange = { edit(header.copy(invoiceNumber = it)) },
                label = str(S.desktop_invoice_no),
                enabled = !frozen,
                modifier = Modifier.weight(1f),
            )
            DateInput(str(S.desktop_invoice_date_title), header.invoiceDate, !frozen) {
                edit(header.copy(invoiceDate = it))
            }
            DateInput(str(S.desktop_due_date_title), header.dueDate, !frozen) { edit(header.copy(dueDate = it)) }
            ZillitDateField(
                value = header.effectiveDate,
                onValueChange = { edit(header.copy(effectiveDate = it)) },
                label = str(S.ah_lbl_eff_date),
                enabled = !frozen,
                // The closed period cannot be picked (`min`, `:1396`); a typed one is refused.
                minDate = lockMinDate(state.periodLock),
                errorText = str(S.desktop_inv_locked_period, state.periodLock.lockedThrough)
                    .takeIf {
                        !frozen && header.effectiveDate.length == DATE_LENGTH &&
                            state.periodLock.isLocked(header.effectiveDate)
                    },
                helperText = str(S.desktop_inv_cost_report_period),
                modifier = Modifier.weight(1f),
            )
            PayMethodPicker(header, !frozen, edit)
        }
        EntitiesRow(state, header, frozen, edit)
        if (ledger.currencyChanged(state.projectCurrency)) {
            CurrencyChangedNotice(state, ledger)
        } else {
            AmountsAsEntered(state, ledger)
        }
    }
}

/**
 * The ledger's four pay methods (`:1403-1414`), labelled as the web labels
 * them there — and a stored method this client has no entry for, kept and
 * shown humanised rather than overwritten with BACs (`payMethodCode`).
 */
@Composable
private fun RowScope.PayMethodPicker(header: EntryHeader, enabled: Boolean, edit: (EntryHeader) -> Unit) {
    val options = LEDGER_PAY_METHODS.map { it.wire } + listOfNotNull(header.payMethodCode.ifBlank { null })
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = str(S.desktop_pay_method),
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitSelect(
            value = header.payWire,
            options = options,
            onSelect = { code ->
                val method = PayMethod.entries.firstOrNull { it.wire == code }
                edit(
                    if (method != null) {
                        header.copy(payMethod = method, payMethodCode = "")
                    } else {
                        header.copy(payMethodCode = code)
                    },
                )
            },
            label = { code -> ledgerPayLabel(code) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun ledgerPayLabel(code: String): String = when (code) {
    PayMethod.Bacs.wire -> str(S.desktop_inv_method_bacs)
    PayMethod.Wire.wire -> str(S.desktop_inv_method_wire_transfer)
    PayMethod.Cheque.wire -> str(S.ah_run_card_method_cheque)
    PayMethod.Faster.wire -> str(S.desktop_faster_payment)
    else -> PayMethod.entries.firstOrNull { it.wire == code }?.label ?: InvoiceLabels.format(code)
}

/**
 * Company, Bank, Currency and — on a television project — Episode.
 *
 * Picking a company brings its country's currency (`:1430-1437`); the bank
 * is marked required, and outlined when missing, only for someone who may
 * post (`:1448`, `:1460`). An empty list says so instead of a dead select.
 */
@Composable
private fun EntitiesRow(state: InvoicesUiState, header: EntryHeader, frozen: Boolean, edit: (EntryHeader) -> Unit) {
    val canPost = state.viewer.canPostToLedger
    FieldRow {
        LabelledPicker(
            label = str(S.company),
            value = header.companyId,
            options = listOf("") + state.companies.map { it.id },
            text = { id ->
                state.companies.firstOrNull { it.id == id }?.name ?: if (state.companies.isEmpty()) {
                    str(S.desktop_inv_no_companies_configured)
                } else {
                    str(S.ah_select_company)
                }
            },
            enabled = !frozen && state.companies.isNotEmpty(),
        ) { picked ->
            val company = state.companies.firstOrNull { it.id == picked }
            val currency = company?.let { CountryCurrency.forCountry(it.country, state.currencyCatalogue) }
            edit(header.copy(companyId = picked, currency = currency ?: header.currency))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            Row {
                ZillitText(
                    text = str(S.desktop_bank),
                    style = ZillitTheme.typography.label,
                    color = ZillitTheme.colors.textSecondary,
                )
                if (canPost) {
                    ZillitText(text = " *", style = ZillitTheme.typography.label, color = ZillitTheme.colors.danger)
                }
            }
            val missing = canPost && header.bankId.isBlank() && !frozen
            Box(
                Modifier
                    .fillMaxWidth()
                    .let { if (missing) it.border(1.dp, ZillitTheme.colors.danger, ZillitTheme.shapes.medium) else it },
            ) {
                ZillitSelect(
                    value = header.bankId,
                    options = listOf("") + state.banks.map { it.id },
                    onSelect = { picked -> edit(header.copy(bankId = picked)) },
                    label = { id ->
                        state.banks.firstOrNull { it.id == id }?.displayName ?: if (state.banks.isEmpty()) {
                            str(S.desktop_inv_no_bank_accounts)
                        } else {
                            str(S.desktop_inv_select_bank)
                        }
                    },
                    enabled = !frozen && state.banks.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        val currencies = (listOf(state.projectCurrency, header.currency) + state.rates.rates.keys)
            .map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        LabelledPicker(
            label = str(S.asset_currency),
            value = header.currency.ifBlank { state.projectCurrency }.uppercase(),
            options = currencies,
            text = { it },
            enabled = !frozen,
        ) { edit(header.copy(currency = it)) }
        if (state.viewer.isTelevision) {
            ZillitTextField(
                value = header.episode,
                onValueChange = { edit(header.copy(episode = it)) },
                label = str(S.episode),
                enabled = !frozen,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The document's own split, read-only — the figure the coded lines must reconcile to. */
@Composable
private fun AmountsAsEntered(state: InvoicesUiState, ledger: EntryLedger) {
    val invoice = ledger.invoice
    val currency = currencyOf(state, ledger)
    ZillitSectionLabel("${str(S.desktop_inv_invoice_amounts)} · ${str(S.desktop_inv_as_entered)}")
    FieldRow {
        Figure(str(S.desktop_net), InvoiceFormat.money(invoice.netAmount, currency))
        Figure(str(S.ah_lbl_vat), InvoiceFormat.money(invoice.taxAmount, currency))
        Figure(str(S.desktop_gross), InvoiceFormat.money(invoice.grossAmount, currency), bold = true)
    }
}

/**
 * In place of the amounts once the currency changed: they are in the old
 * currency and would read falsely under the new symbol (`:1496-1511`).
 */
@Composable
private fun CurrencyChangedNotice(state: InvoicesUiState, ledger: EntryLedger) {
    val picked = currencyOf(state, ledger)
    val old = ledger.invoice.currency.ifBlank { state.projectCurrency }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.warningSoft)
            .border(1.dp, ZillitTheme.colors.warning.copy(alpha = TONE_ALPHA), ZillitTheme.shapes.medium)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = str(S.desktop_inv_currency_changed_to, picked),
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.warning,
        )
        ZillitText(
            text = str(S.desktop_inv_amounts_entered_in, old),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

@Composable
private fun RowScope.Figure(label: String, value: String, bold: Boolean = false) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(text = label, style = ZillitTheme.typography.columnHeader, color = ZillitTheme.colors.textMuted)
        ZillitText(
            text = value,
            style = ZillitTheme.typography.numeric.copy(
                fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            ),
        )
    }
}

/**
 * Purchase Order Details (`:1539-1767`): one order at a time — a select when
 * several are linked — with its status, the vendor's contact block, the
 * delivery address, then Department, PO Amount, Eff. Date and Delivery Date.
 * An order in another currency than the invoice is flagged: posting relieves
 * it by the invoice gross without converting.
 */
@Suppress("LongMethod") // The web's card, section by section.
@Composable
private fun OrdersCard(
    state: InvoicesUiState,
    ledger: EntryLedger,
    order: LinkedPoDetail,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val invoiceCurrency = currencyOf(state, ledger)
    ZillitSectionCard(
        title = str(S.purchase_order_details),
        icon = ZillitIcons.Receipt,
        action = {
            if (ledger.orders.size > 1) {
                ZillitSelect(
                    value = ledger.orders.indexOf(order).coerceAtLeast(0),
                    options = ledger.orders.indices.toList(),
                    onSelect = { onEvent(EntryEvent.SelectPo(it)) },
                    label = { index -> ledger.orders.getOrNull(index)?.label.orEmpty() },
                )
            } else {
                ZillitStatusPill(label = order.label, tone = StatusTone.Progress)
            }
            ZillitStatusPill(
                label = InvoiceLabels.format(order.status.ifBlank { UNKNOWN_STATUS }.lowercase().replace('_', ' ')),
                tone = poStatusTone(order.status),
            )
        },
    ) {
        FieldRow {
            VendorBlock(state, ledger, order, Modifier.weight(1f))
            DeliveryBlock(order, Modifier.weight(1f))
        }
        FieldRow {
            Figure(str(S.department), state.departmentName(order.departmentId))
            Figure(
                str(S.desktop_inv_po_amount),
                InvoiceFormat.money(order.grossTotal, order.currency.ifBlank { invoiceCurrency }),
            )
            Figure(str(S.ah_row_eff_date_upper), InvoiceFormat.date(order.effectiveDateMs))
            Figure(str(S.ah_lbl_delivery_date), InvoiceFormat.date(order.deliveryDateMs))
        }
        // Silent when the order names no currency: unknown is not a mismatch.
        if (order.currency.isNotBlank() && !order.currency.equals(invoiceCurrency, ignoreCase = true)) {
            ZillitNotice(
                text = str(S.desktop_inv_po_currency_differs, order.currency, invoiceCurrency),
                tone = StatusTone.Pending,
            )
        }
    }
}

@Composable
private fun VendorBlock(state: InvoicesUiState, ledger: EntryLedger, order: LinkedPoDetail, modifier: Modifier) {
    val vendor = state.vendors[ledger.invoice.vendorId]
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = str(S.ah_lbl_vendor),
            style = ZillitTheme.typography.columnHeader,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(
            text = state.vendorName(ledger.invoice).ifBlank { order.vendorName },
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
        vendor?.contactPerson?.takeIf { it.isNotBlank() }?.let { MutedLine(it) }
        vendor?.address?.takeIf { it.isNotBlank() }?.let { MutedLine(it) }
        listOfNotNull(vendor?.phone?.ifBlank { null }, vendor?.email?.ifBlank { null })
            .takeIf { it.isNotEmpty() }?.let { MutedLine(it.joinToString(" · ")) }
    }
}

@Composable
private fun DeliveryBlock(order: LinkedPoDetail, modifier: Modifier) {
    val address = order.deliveryAddress
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = str(S.delivery_address),
            style = ZillitTheme.typography.columnHeader,
            color = ZillitTheme.colors.textMuted,
        )
        if (address == null) {
            MutedLine("—")
        } else {
            if (address.name.isNotBlank()) {
                ZillitText(
                    text = address.name,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            if (address.lines.isNotBlank()) MutedLine(address.lines)
            listOfNotNull(address.email.ifBlank { null }, address.phone.ifBlank { null })
                .takeIf { it.isNotEmpty() }?.let { MutedLine(it.joinToString(" · ")) }
        }
    }
}

/** Approved or posted green, rejected red, anything else amber (`:1616-1625`). */
private fun poStatusTone(status: String): StatusTone = when (status.lowercase()) {
    "approved", "posted" -> StatusTone.Ready
    "rejected" -> StatusTone.Rejected
    else -> StatusTone.Pending
}

/**
 * Coded gross against the invoice's gross — the web's `GrossMatchBar`:
 * matched, still to allocate, or over. Informational; Save and Post enforce it.
 */
@Composable
private fun GrossMatch(invoiceGross: Double, coded: Double, currency: String) {
    val colors = ZillitTheme.colors
    val diff = EntryCoding.round2(coded - invoiceGross)
    val (text, tone) = when {
        kotlin.math.abs(diff) <= MATCH_TOLERANCE -> str(S.desktop_matched) to colors.success
        diff < 0 -> str(S.desktop_inv_to_allocate, InvoiceFormat.money(-diff, currency)) to colors.warning
        else -> str(S.desktop_inv_amount_over, InvoiceFormat.money(diff, currency)) to colors.danger
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Row(
            modifier = Modifier
                .clip(ZillitTheme.shapes.medium)
                .border(1.dp, tone.copy(alpha = TONE_ALPHA), ZillitTheme.shapes.medium)
                .background(tone.copy(alpha = FILL_ALPHA))
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MatchFigure(str(S.desktop_invoice_gross), InvoiceFormat.money(invoiceGross, currency))
            MatchFigure(str(S.desktop_ce_coded), InvoiceFormat.money(coded, currency))
            ZillitText(
                text = text,
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                color = tone,
            )
        }
    }
}

@Composable
private fun MatchFigure(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.columnHeader,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(text = value, style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold))
    }
}

/** The web's checklist; three checks it cannot run yet pass, exactly as they do there. */
@Composable
private fun ValidationCard(state: InvoicesUiState, ledger: EntryLedger) {
    val colors = ZillitTheme.colors
    val checks = EntryCoding.validation(ledger.invoice, state.vendors[ledger.invoice.vendorId])
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.success.copy(alpha = FILL_ALPHA))
            .border(1.dp, colors.success.copy(alpha = TONE_ALPHA), ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitSectionLabel(str(S.desktop_inv_data_validation))
        checks.forEach { (check, pass) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitIcon(
                    icon = if (pass) ZillitIcons.Check else ZillitIcons.Close,
                    tint = if (pass) colors.success else colors.danger,
                    size = CHECK_ICON,
                )
                ZillitText(
                    text = check.label(),
                    style = ZillitTheme.typography.bodySmall,
                    color = if (pass) colors.textPrimary else colors.danger,
                )
            }
        }
    }
}

private fun EntryCheck.label(): String = when (this) {
    EntryCheck.VendorExists -> str(S.desktop_inv_check_vendor_exists)
    EntryCheck.DateInPeriod -> str(S.desktop_inv_check_date_in_period)
    EntryCheck.NoDuplicate -> str(S.desktop_inv_check_no_duplicate)
    EntryCheck.ApprovalComplete -> str(S.desktop_inv_check_approval_complete)
    EntryCheck.BankVerified -> str(S.desktop_inv_check_bank_verified)
    EntryCheck.NominalsValid -> str(S.desktop_inv_check_nominals_valid)
}

// -- small parts ---------------------------------------------------------------------

@Composable
internal fun FieldRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
        content = content,
    )
}

@Composable
private fun RowScope.DateInput(
    label: String,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    ZillitDateField(
        value = value,
        onValueChange = onChange,
        label = label,
        enabled = enabled,
        modifier = Modifier.weight(1f),
    )
}

/** A select under its own label; weighted, so it never crushes its neighbours. */
@Composable
internal fun RowScope.LabelledPicker(
    label: String,
    value: String,
    options: List<String>,
    text: (String) -> String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(text = label, style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
        ZillitSelect(
            value = value,
            options = options,
            onSelect = onSelect,
            label = text,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The first day a closed period allows — `lockedMinDateInput`: the day after
 * the boundary, or null when nothing is locked.
 */
internal fun lockMinDate(lock: PeriodLock): LocalDate? =
    lock.lockedThrough.takeIf { lock.isSet }
        ?.let { runCatching { LocalDate.parse(it).plus(1, DateTimeUnit.DAY) }.getOrNull() }

/** The web's four pay methods on this screen. */
private val LEDGER_PAY_METHODS = listOf(PayMethod.Bacs, PayMethod.Wire, PayMethod.Cheque, PayMethod.Faster)

private const val DOCUMENT_SHARE = 1f
private const val CODING_SHARE = 2f
private const val FULLSCREEN_SHARE = 0.9f
private const val SCRIM_ALPHA = 0.7f
private const val DATE_LENGTH = 10
private const val UNKNOWN_STATUS = "unknown"
private val BACK_CHIP = 32.dp
private val CHECK_ICON = 14.dp
private val PO_CARD_ICON = 28.dp
private val PO_CARD_MAX = 300.dp
private const val MATCH_TOLERANCE = 0.01
private const val TONE_ALPHA = 0.35f
private const val FILL_ALPHA = 0.08f
