// Invoice Entry's coding screen: the top bar, the document, and the coding column.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.EntryCheck
import com.zillit.desktop.feature.invoices.domain.EntryCoding
import com.zillit.desktop.feature.invoices.domain.EntryHeader
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.ui.EntryAction
import com.zillit.desktop.feature.invoices.ui.EntryEvent
import com.zillit.desktop.feature.invoices.ui.EntryLedger
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.QueryEvent
import com.zillit.desktop.feature.invoices.ui.decodePreviewPages

/**
 * The web's full-page ledger entry view (`EntryDetailModal`, `fullPage`).
 *
 * It takes the queue's place in the content column: a top bar with the way
 * back, the breadcrumb and every action; the invoice document on the left
 * when there is one; and the coding column. A posted invoice, or one dated in
 * a closed cost-report period, is frozen — History and Query stay, nothing
 * else does.
 */
@Composable
internal fun ColumnScope.EntryLedgerView(
    state: InvoicesUiState,
    ledger: EntryLedger,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val locked = state.isLocked(ledger.invoice)
    val frozen = ledger.readOnly || locked
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
        // The document takes a third only when there is one to show.
        if (ledger.invoice.firstAttachment != null) {
            DocumentPane(ledger, Modifier.width(DOCUMENT_WIDTH).fillMaxHeight())
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (ledger.loading) {
                ZillitSpinner(modifier = Modifier.align(Alignment.Center))
            } else {
                CodingColumn(state, ledger, frozen, onEvent)
            }
        }
    }
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
        when {
            locked -> ZillitStatusPill(
                label = str(S.desktop_inv_locked_period, state.periodLock.lockedThrough),
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
 * and Post needs the right to post (ZL-20450, ZL-20767).
 */
@Composable
private fun LedgerActions(
    state: InvoicesUiState,
    ledger: EntryLedger,
    frozen: Boolean,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val action = ledger.action
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        SmallButton(str(S.history), ZillitIcons.Clock) { onEvent(EntryEvent.ShowHistory) }
        SmallButton(str(S.ah_query_label), ZillitIcons.Chat) { onEvent(QueryEvent.Open(ledger.invoice)) }
        if (frozen) return@Row
        ZillitButton(
            text = str(S.save),
            onClick = { onEvent(EntryEvent.Save) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = action == null,
            loading = action == EntryAction.Saving,
        )
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

@Composable
private fun DocumentPane(ledger: EntryLedger, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val attachment = ledger.invoice.firstAttachment ?: return
    val pages = remember(ledger.preview) { ledger.preview?.bytes?.let(::decodePreviewPages).orEmpty() }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitSectionLabel(str(S.ah_run_detail_col_invoice))
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
                ledger.previewLoading -> ZillitSpinner()
                else -> ZillitText(
                    text = if (ledger.previewFailed) {
                        str(S.desktop_inv_could_not_load_preview)
                    } else {
                        attachment.name.ifBlank { str(S.document) }
                    },
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
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
    Row(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(scroll).padding(end = ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            HeaderCard(state, ledger, frozen, onEvent)
            if (ledger.orders.isNotEmpty()) OrdersCard(state, ledger)
            val totals = ledger.totals(state.taxTypes, state.taxTypesKnown)
            GrossMatch(
                invoiceGross = ledger.invoice.grossAmount,
                coded = totals.gross,
                currency = currencyOf(state, ledger),
            )
            EntryLinesCard(state, ledger, frozen, onEvent)
            ValidationCard(state, ledger)
        }
        ZillitScrollRail(scroll)
    }
}

internal fun currencyOf(state: InvoicesUiState, ledger: EntryLedger): String =
    ledger.header.currency.ifBlank { ledger.invoice.currency }.ifBlank { state.projectCurrency }

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
            DateInput(str(S.desktop_invoice_date), header.invoiceDate, !frozen) { edit(header.copy(invoiceDate = it)) }
            DateInput(str(S.desktop_due_date_title), header.dueDate, !frozen) { edit(header.copy(dueDate = it)) }
            ZillitDateField(
                value = header.effectiveDate,
                onValueChange = { edit(header.copy(effectiveDate = it)) },
                label = str(S.ah_lbl_eff_date),
                enabled = !frozen,
                // The server refuses a date inside the closed period; say so before it does.
                errorText = str(S.desktop_inv_locked_period, state.periodLock.lockedThrough)
                    .takeIf { !frozen && state.periodLock.isLocked(header.effectiveDate) },
                helperText = str(S.desktop_inv_cost_report_period),
                modifier = Modifier.weight(1f),
            )
            ZillitSelect(
                value = header.payMethod,
                options = LEDGER_PAY_METHODS,
                onSelect = { edit(header.copy(payMethod = it)) },
                label = { it.label },
                enabled = !frozen,
                modifier = Modifier.weight(1f),
            )
        }
        EntitiesRow(state, header, frozen, edit)
        AmountsAsEntered(state, ledger)
    }
}

@Composable
private fun EntitiesRow(state: InvoicesUiState, header: EntryHeader, frozen: Boolean, edit: (EntryHeader) -> Unit) {
    FieldRow {
        LabelledPicker(
            label = str(S.company),
            value = header.companyId,
            options = listOf("") + state.companies.map { it.id },
            text = { id -> state.companies.firstOrNull { it.id == id }?.name ?: str(S.ah_select_company) },
            enabled = !frozen && state.companies.isNotEmpty(),
        ) { edit(header.copy(companyId = it)) }
        LabelledPicker(
            label = str(S.desktop_bank) + " *",
            value = header.bankId,
            options = listOf("") + state.banks.map { it.id },
            text = { id -> state.banks.firstOrNull { it.id == id }?.displayName ?: str(S.desktop_inv_select_bank) },
            enabled = !frozen && state.banks.isNotEmpty(),
        ) { picked ->
            // A picked bank brings its company, when none is chosen yet — the web's auto-fill.
            val entity = state.banks.firstOrNull { it.id == picked }?.entityId.orEmpty()
            edit(header.copy(bankId = picked, companyId = header.companyId.ifBlank { entity }))
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

@Composable
private fun androidx.compose.foundation.layout.RowScope.Figure(label: String, value: String, bold: Boolean = false) {
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

/** The linked orders the invoice is matched to: number, vendor, description and amount. */
@Composable
private fun OrdersCard(state: InvoicesUiState, ledger: EntryLedger) {
    val currency = currencyOf(state, ledger)
    ZillitSectionCard(title = str(S.purchase_order_details), icon = ZillitIcons.Receipt) {
        ledger.orders.forEach { order ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitStatusPill(label = order.label, tone = StatusTone.Progress)
                Column(Modifier.weight(1f)) {
                    ZillitText(
                        text = order.vendorName.ifBlank { state.vendorName(ledger.invoice) },
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                    )
                    if (order.description.isNotBlank()) MutedLine(order.description)
                }
                Column(horizontalAlignment = Alignment.End) {
                    ZillitText(
                        text = str(S.desktop_inv_po_amount),
                        style = ZillitTheme.typography.columnHeader,
                        color = ZillitTheme.colors.textMuted,
                    )
                    ZillitText(
                        text = InvoiceFormat.money(order.grossTotal, order.currency.ifBlank { currency }),
                        style = ZillitTheme.typography.numeric,
                    )
                }
                if (order.status.isNotBlank()) {
                    ZillitStatusPill(label = order.status.replace('_', ' ').lowercase(), tone = StatusTone.Neutral)
                }
            }
        }
    }
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
internal fun FieldRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
        content = content,
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DateInput(
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
internal fun androidx.compose.foundation.layout.RowScope.LabelledPicker(
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

/** The web's four pay methods on this screen. */
private val LEDGER_PAY_METHODS = listOf(PayMethod.Bacs, PayMethod.Wire, PayMethod.Cheque, PayMethod.Faster)

private val DOCUMENT_WIDTH = 380.dp
private val BACK_CHIP = 32.dp
private val CHECK_ICON = 14.dp
private const val MATCH_TOLERANCE = 0.01
private const val TONE_ALPHA = 0.35f
private const val FILL_ALPHA = 0.08f
