// The Inbox review: the document beside the editable form, the order match, and its confirmations.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCalcField
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.AmountField
import com.zillit.desktop.feature.invoices.domain.InboxField
import com.zillit.desktop.feature.invoices.domain.InboxTriage
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.PoPick
import com.zillit.desktop.feature.invoices.domain.PoSuggestion
import com.zillit.desktop.feature.invoices.ui.AccountantPage
import com.zillit.desktop.feature.invoices.ui.InboxEvent
import com.zillit.desktop.feature.invoices.ui.InboxForm
import com.zillit.desktop.feature.invoices.ui.InboxReview
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.decodePreviewPages

/**
 * One inbox invoice under review — the web's `InboxReviewModal`: what the
 * OCR read (or nothing, for a manual entry), corrected by hand, matched to
 * one or more orders, and accepted on to pre-approval. Query stays on the
 * left of the footer even in a closed period; only Accept goes.
 */
@Composable
internal fun InboxReviewDialog(state: InvoicesUiState, review: InboxReview, onEvent: (InvoicesEvent) -> Unit) {
    val locked = state.isLocked(review.invoice)
    ZillitDialogShell(
        title = review.invoice.invoiceNumber.ifBlank { str(S.desktop_inv_invoice_review) },
        visible = true,
        onDismiss = { onEvent(InboxEvent.Close) },
        width = REVIEW_WIDTH,
        icon = ZillitIcons.Inbox,
        actions = {
            QueryButton(state, review, onEvent)
            Spacer(Modifier.weight(1f))
            if (!locked) {
                ZillitButton(
                    text = if (review.busy) str(S.txt_processing) else str(S.desktop_inv_accept_and_match),
                    onClick = { onEvent(InboxEvent.Accept) },
                    enabled = !review.loading && !review.busy,
                    loading = review.busy,
                )
            }
        },
    ) {
        if (review.loading) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitSpinner(size = LOADING_SPINNER)
                MutedLine(str(S.desktop_inv_loading_invoice_details))
            }
            return@ZillitDialogShell
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            ReviewDocument(review, Modifier.weight(1f))
            Column(
                modifier = Modifier.width(FORM_WIDTH),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                if (locked) {
                    ZillitNotice(
                        text = str(S.desktop_inv_locked_period, state.periodLock.lockedThrough),
                        tone = StatusTone.Escalated,
                    )
                }
                val enabled = !locked && !review.busy
                ReviewForm(state, review, enabled = enabled, onEvent = onEvent)
                OrderMatch(state, review, enabled = enabled, onEvent = onEvent)
                PickedOrders(state, review, enabled = enabled, onEvent = onEvent)
                CreatedBy(state, review)
                ErrorSummary(review)
            }
        }
    }
    ReviewConfirmations(state, review, onEvent)
}

/** Query, with the thread's unread — `getInvoiceLevel3Unread(invoice_register, id, "query_chat")`. */
@Composable
private fun QueryButton(state: InvoicesUiState, review: InboxReview, onEvent: (InvoicesEvent) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(
            text = str(S.ah_query_label),
            onClick = { onEvent(InboxEvent.OpenQuery) },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Chat,
        )
        val unread = AccountantPage.Register.badgeKey?.let { state.rowUnread(it, review.invoice.id) } ?: 0
        ZillitBadge(count = unread)
    }
}

@Composable
private fun ReviewDocument(review: InboxReview, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val pages = remember(review.preview) { review.preview?.bytes?.let(::decodePreviewPages).orEmpty() }
    Box(
        modifier = modifier
            .height(PREVIEW_HEIGHT)
            .background(colors.surfaceSunken, ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        val attachment = review.invoice.firstAttachment
        when {
            attachment == null -> MutedLine(str(S.ah_no_attachment))
            pages.isNotEmpty() -> PreviewPages(pages, attachment.name)
            review.previewLoading -> MutedLine(str(S.desktop_dm_loading_attachment))
            review.previewFailed -> MutedLine(str(S.desktop_attachment_load_failed))
            else -> MutedLine(attachment.name)
        }
    }
}

/** The fields; a field Accept found empty is drawn in red and named in the summary below. */
@Composable
private fun ReviewForm(
    state: InvoicesUiState,
    review: InboxReview,
    enabled: Boolean,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val form = review.form
    val edit: (InboxForm) -> Unit = { onEvent(InboxEvent.Edit(it)) }
    ZillitText(
        text = str(S.desktop_inv_invoice_details).uppercase(),
        style = ZillitTheme.typography.columnHeader,
        color = ZillitTheme.colors.textMuted,
    )
    ZillitTextField(
        value = form.invoiceNumber,
        onValueChange = { edit(form.copy(invoiceNumber = it)) },
        label = str(S.desktop_inv_invoice_number_label),
        placeholder = str(S.desktop_inv_eg_inv_001),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    LabelledField(str(S.ah_lbl_vendor), isError = InboxField.Vendor in review.errors) {
        VendorPicker(
            state = state,
            vendorId = form.vendorId,
            pendingName = form.pendingVendorName,
            onPick = { edit(form.copy(vendorId = it, pendingVendorName = null)) },
            onCreate = { onEvent(InboxEvent.CreateVendor(it)) },
            enabled = enabled,
            isError = InboxField.Vendor in review.errors,
        )
        PendingVendorHint(form.pendingVendorName?.takeIf { form.vendorId.isBlank() })
    }
    ZillitTextField(
        value = form.description,
        onValueChange = { edit(form.copy(description = it)) },
        label = str(S.description),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    FieldRow {
        ZillitDateField(
            value = form.invoiceDate,
            onValueChange = { edit(form.copy(invoiceDate = it)) },
            modifier = Modifier.weight(1f),
            label = str(S.desktop_invoice_date_title),
            enabled = enabled,
        )
        ZillitDateField(
            value = form.dueDate,
            onValueChange = { edit(form.copy(dueDate = it)) },
            modifier = Modifier.weight(1f),
            label = str(S.desktop_due_date_title),
            enabled = enabled,
        )
        ZillitDateField(
            value = form.effectiveDate,
            onValueChange = { edit(form.copy(effectiveDate = it)) },
            modifier = Modifier.weight(1f),
            label = str(S.ah_lbl_eff_date),
            enabled = enabled,
            // Red, but unworded: the summary below names it (`errCls`).
            errorText = "".takeIf { InboxField.EffectiveDate in review.errors },
            minDate = state.effectiveMinDate(),
        )
    }
    ReviewCoding(state, form, review, enabled, edit)
    ReviewAmounts(state, form, review, enabled, onEvent)
}

@Composable
private fun ReviewCoding(
    state: InvoicesUiState,
    form: InboxForm,
    review: InboxReview,
    enabled: Boolean,
    edit: (InboxForm) -> Unit,
) {
    FieldRow {
        LabelledField(str(S.department), Modifier.weight(1f), isError = InboxField.Department in review.errors) {
            IdPicker(
                value = form.departmentId,
                options = state.departmentChoices(),
                label = { state.departmentName(it) },
                placeholder = str(S.desktop_inv_select_ellipsis),
                onSelect = { edit(form.copy(departmentId = it)) },
                enabled = enabled,
                isError = InboxField.Department in review.errors,
            )
        }
        LabelledField(str(S.desktop_payment_method), Modifier.weight(1f), isError = InboxField.PayMethod in review.errors) {
            ZillitSelect(
                value = form.payMethod,
                options = REVIEW_PAY_METHODS,
                onSelect = { edit(form.copy(payMethod = it)) },
                label = { it.label },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val shown = form.currency.ifBlank { state.projectCurrency }.uppercase()
        val currencies = (listOf(state.projectCurrency, form.currency) + state.rates.rates.keys)
            .map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        LabelledField(str(S.asset_currency), Modifier.weight(1f), isError = InboxField.Currency in review.errors) {
            IdPicker(
                value = shown,
                options = currencies,
                label = { state.currencyLabel(it) },
                placeholder = str(S.desktop_ce_cards_select_currency),
                onSelect = { edit(form.copy(currency = it)) },
                enabled = enabled,
                isError = InboxField.Currency in review.errors,
            )
        }
    }
    FieldRow {
        LabelledField(str(S.company), Modifier.weight(1f)) {
            IdPicker(
                value = form.companyId,
                options = state.companies.map { it.id },
                label = { id -> state.companies.firstOrNull { it.id == id }?.name.orEmpty() },
                searchText = { id -> state.companies.firstOrNull { it.id == id }?.let { "${it.name} ${it.country}" }.orEmpty() },
                placeholder = if (state.companies.isEmpty()) {
                    str(S.desktop_inv_no_companies_configured)
                } else {
                    str(S.desktop_inv_select_company)
                },
                onSelect = { edit(form.copy(companyId = it)) },
                enabled = enabled && state.companies.isNotEmpty(),
            )
        }
        LabelledField(str(S.desktop_bank), Modifier.weight(1f)) {
            IdPicker(
                value = form.bankId,
                options = state.banks.map { it.id },
                label = { id -> state.banks.firstOrNull { it.id == id }?.displayName.orEmpty() },
                searchText = { id -> state.banks.firstOrNull { it.id == id }?.let { "${it.name} ${it.bankName}" }.orEmpty() },
                placeholder = if (state.banks.isEmpty()) str(S.desktop_inv_no_bank_accounts) else str(S.desktop_inv_select_bank),
                onSelect = { edit(form.copy(bankId = it)) },
                enabled = enabled && state.banks.isNotEmpty(),
            )
        }
        if (state.viewer.isTelevision) {
            ZillitTextField(
                value = form.episode,
                onValueChange = { edit(form.copy(episode = it)) },
                label = str(S.episode),
                placeholder = str(S.desktop_inv_eg_ep_101),
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Net, Tax and Gross as calculator fields (`CalcInput`), kept consistent; a
 * split that does not add up is said beneath, never blocked.
 */
@Composable
private fun ReviewAmounts(
    state: InvoicesUiState,
    form: InboxForm,
    review: InboxReview,
    enabled: Boolean,
    onEvent: (InvoicesEvent) -> Unit,
) {
    FieldRow {
        ZillitCalcField(
            value = form.amounts.net,
            onCommit = { onEvent(InboxEvent.EditAmount(AmountField.Net, it)) },
            label = str(S.desktop_inv_net_amount),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        ZillitCalcField(
            value = form.amounts.tax,
            onCommit = { onEvent(InboxEvent.EditAmount(AmountField.Tax, it)) },
            label = str(S.ah_lbl_tax_amt),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        ZillitCalcField(
            value = form.amounts.gross,
            onCommit = { onEvent(InboxEvent.EditAmount(AmountField.Gross, it)) },
            label = str(S.desktop_inv_gross_amount),
            placeholder = null,
            enabled = enabled,
            errorText = "".takeIf { InboxField.GrossAmount in review.errors },
            modifier = Modifier.weight(1f),
        )
    }
    SplitStrip(form.amounts, form.currency.ifBlank { state.projectCurrency })
}

/**
 * The order match: this vendor's and the reader's own orders as suggestions,
 * every open order to search, each shown with its vendor and amount — the
 * web's two `SearchableSelect`s — and, with nothing picked, the inert "No PO —
 * verified" tick.
 */
@Composable
private fun OrderMatch(
    state: InvoicesUiState,
    review: InboxReview,
    enabled: Boolean,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val picked = review.form.picks.map { it.id }.toSet()
    val suggested = (review.suggestions.vendorPos + review.suggestions.userPos)
        .distinctBy { it.poId }.filter { it.poId !in picked }
    val everyOrder = review.suggestions.allPos.filter { it.poId !in picked }
    val currency = review.form.currency.ifBlank { state.projectCurrency }
    val add = { po: PoSuggestion -> onEvent(InboxEvent.AddPo(PoPick(po.poId, po.label, po.grossAmount))) }
    FieldRow {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FieldLabel(str(S.desktop_inv_po_suggestions))
                if (review.suggestionsLoading) ZillitSpinner(size = SMALL_SPINNER)
            }
            if (review.suggestionsLoading) {
                IdPicker(
                    value = "",
                    options = emptyList(),
                    label = { it },
                    placeholder = str(S.desktop_inv_loading_dots),
                    onSelect = {},
                    enabled = false,
                )
            } else {
                OrderSelect(suggested, str(S.desktop_inv_select_suggested_po_dots), currency, enabled, add)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            FieldLabel(str(S.ah_all_purchase_orders))
            OrderSelect(everyOrder, str(S.desktop_inv_search_all_pos_dots), currency, enabled, add)
        }
    }
    if (review.form.picks.isEmpty()) {
        ZillitCheckbox(
            checked = review.noPoVerified,
            onCheckedChange = { onEvent(InboxEvent.ToggleNoPoVerified) },
            label = str(S.desktop_inv_no_po_verified),
            enabled = enabled,
        )
    }
}

@Composable
private fun OrderSelect(
    orders: List<PoSuggestion>,
    placeholder: String,
    currency: String,
    enabled: Boolean,
    onPick: (PoSuggestion) -> Unit,
) {
    ZillitSearchSelect(
        value = null,
        options = orders,
        onSelect = onPick,
        label = { it.label },
        searchText = { "${it.label} ${it.vendorName}" },
        subtitle = { po ->
            val vendor = po.vendorName.ifBlank { str(S.desktop_unknown) }
            "$vendor · ${InvoiceFormat.money(po.grossAmount, po.currency.ifBlank { currency })}"
        },
        placeholder = placeholder,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The picked orders as chips — each opens the order read-only, locked period
 * or not, and loses its ✕ in one — the balance against the gross, and the
 * note stored on every link.
 */
@Composable
private fun PickedOrders(
    state: InvoicesUiState,
    review: InboxReview,
    enabled: Boolean,
    onEvent: (InvoicesEvent) -> Unit,
) {
    if (review.form.picks.isEmpty()) return
    val currency = review.form.currency.ifBlank { state.projectCurrency }
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        review.form.picks.forEach { pick ->
            Row(
                modifier = Modifier
                    .background(ZillitTheme.colors.infoSoft, ZillitTheme.shapes.pill)
                    .padding(start = ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = pick.number.ifBlank { pick.id.take(PO_ID_CHARS) },
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .clickable { onEvent(InvoicesEvent.OpenLinkedPo(pick.id, pick.number)) }
                        .padding(vertical = ZillitTheme.spacing.xxs, horizontal = ZillitTheme.spacing.xxs),
                )
                if (!state.isLocked(review.invoice)) {
                    ZillitIconButton(
                        icon = ZillitIcons.Close,
                        contentDescription = str(S.remove),
                        onClick = { onEvent(InboxEvent.RemovePo(pick.id)) },
                        enabled = enabled,
                    )
                } else {
                    Spacer(Modifier.width(ZillitTheme.spacing.sm))
                }
            }
        }
        PoBadge(review, currency)
    }
    ZillitTextField(
        value = review.form.matchNotes,
        onValueChange = { onEvent(InboxEvent.Edit(review.form.copy(matchNotes = it))) },
        label = str(S.desktop_inv_match_notes),
        placeholder = str(S.desktop_inv_eg_vendor_confirmed),
        helperText = str(S.desktop_inv_match_notes_hint),
        singleLine = false,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Matched, Over PO or Under PO — the invoice gross against the picked
 * orders' total, their amounts read from the suggestion lists
 * (`computePoMatch`); hidden while any order's amount is unknown.
 */
@Composable
private fun PoBadge(review: InboxReview, currency: String) {
    val buckets = review.suggestions.allPos + review.suggestions.vendorPos + review.suggestions.userPos
    val amounts = buckets.groupBy { it.poId }.mapValues { (_, rows) -> rows.first().grossAmount }
    val picks = review.form.picks.map { it.copy(gross = amounts[it.id]) }
    val gross = InboxTriage.amount(review.form.amounts.gross)
    if (gross <= 0.0) return
    val balance = InboxTriage.poBalance(gross, picks) ?: return
    val total = picks.sumOf { it.gross ?: 0.0 }
    val (label, tone) = when {
        kotlin.math.abs(balance) <= PENNY -> str(S.desktop_matched) to StatusTone.Ready
        balance > 0 -> str(S.desktop_inv_over_po, InvoiceFormat.money(balance, currency)) to StatusTone.Rejected
        else -> str(S.desktop_inv_under_po, InvoiceFormat.money(-balance, currency)) to StatusTone.Pending
    }
    ZillitStatusPill(
        label = "$label · ${str(S.desktop_inv_po_total, InvoiceFormat.money(total, currency))}",
        tone = tone,
    )
}

/** "Created By": the creator's name (or raw id), designation, and when — `dd MMM yyyy at HH:mm`. */
@Composable
private fun CreatedBy(state: InvoicesUiState, review: InboxReview) {
    val invoice = review.invoice
    if (invoice.userId.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FieldLabel(str(S.created_by_new))
        ZillitText(
            text = review.creatorName.ifBlank { state.userNames[invoice.userId] ?: invoice.userId },
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        designationText(review.creatorRole).takeIf { it.isNotBlank() }?.let { MutedLine(it) }
        invoice.createdAtMs?.takeIf { it > 0 }?.let { ms ->
            val stamp = InvoiceFormat.dateTime(ms)
            val date = stamp.substringBefore(", ")
            val time = stamp.substringAfter(", ")
            MutedLine(str(S.desktop_email_date_at_time, date, time))
        }
    }
}

/** Every field Accept found missing, listed — the web's red summary box. */
@Composable
private fun ErrorSummary(review: InboxReview) {
    if (review.errors.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.dangerSoft, ZillitTheme.shapes.medium)
            .border(1.dp, ZillitTheme.colors.danger.copy(alpha = SUMMARY_BORDER_ALPHA), ZillitTheme.shapes.medium)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        InboxField.entries.filter { it in review.errors }.forEach { field ->
            ZillitText(
                text = "• " + str(S.recce_required_field, field.label()),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
            )
        }
    }
}

/** "Amounts don't match" and "No PO Selected" — asked in that order, the web's two gates. */
@Composable
private fun ReviewConfirmations(state: InvoicesUiState, review: InboxReview, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_inv_amounts_dont_match),
        visible = review.confirmSplit,
        onDismiss = { onEvent(InboxEvent.CancelConfirm) },
        icon = ZillitIcons.Warning,
        scrollable = false,
        actions = {
            ZillitButton(
                text = str(S.dm_nda_go_back),
                onClick = { onEvent(InboxEvent.CancelConfirm) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(text = str(S.desktop_inv_continue_anyway), onClick = { onEvent(InboxEvent.ConfirmSplit) })
        },
    ) {
        ZillitText(text = splitMessage(state, review.form), style = ZillitTheme.typography.bodyMedium)
    }
    ZillitDialogShell(
        title = str(S.desktop_inv_no_po_selected),
        visible = review.confirmNoPo,
        onDismiss = { onEvent(InboxEvent.CancelConfirm) },
        icon = ZillitIcons.Warning,
        scrollable = false,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InboxEvent.CancelConfirm) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(text = str(S.continue_text), onClick = { onEvent(InboxEvent.ConfirmNoPo) })
        },
    ) {
        ZillitText(text = str(S.desktop_inv_no_po_confirm), style = ZillitTheme.typography.bodyMedium)
    }
}

/** `Net £x + Tax £y = £z, but Gross is £g (£d over). Continue anyway?` — the web's words and figures. */
private fun splitMessage(state: InvoicesUiState, form: InboxForm): String {
    val currency = form.currency.ifBlank { state.projectCurrency }
    val figures = SplitFigures.of(form.amounts)
    val money = { value: Double -> InvoiceFormat.money(value, currency) }
    val drift = if (figures.diff > 0) {
        str(S.desktop_inv_amount_over, money(figures.diff))
    } else {
        str(S.desktop_inv_amount_under, money(-figures.diff))
    }
    return str(
        S.desktop_inv_split_confirm,
        money(figures.net),
        money(figures.tax),
        money(figures.sum),
        money(figures.gross),
        drift,
    )
}

/**
 * Bulk Process refused: which rows are missing what. Nothing was sent, so
 * there is nothing to choose — each row opens its own review.
 */
@Composable
internal fun BlockedProcessDialog(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val blocked = state.blockedProcess
    ZillitDialogShell(
        title = str(S.desktop_inv_some_not_ready),
        visible = blocked.isNotEmpty(),
        onDismiss = { onEvent(InboxEvent.DismissBlocked) },
        icon = ZillitIcons.Warning,
        actions = {
            ZillitButton(text = str(S.close), onClick = { onEvent(InboxEvent.DismissBlocked) })
        },
    ) {
        ZillitText(
            // "invoice is" / "invoices are", as the web switches it.
            text = if (blocked.size == 1) {
                str(S.desktop_inv_blocked_one, blocked.size, state.selected.size)
            } else {
                str(S.desktop_inv_some_not_ready_message, blocked.size, state.selected.size)
            },
            style = ZillitTheme.typography.bodyMedium,
        )
        blocked.forEach { entry ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitButton(
                    text = entry.invoice.displayNumber,
                    onClick = {
                        onEvent(InboxEvent.DismissBlocked)
                        onEvent(InboxEvent.Open(entry.invoice))
                    },
                    variant = ButtonVariant.Tertiary,
                )
                MutedLine("— " + entry.missing.joinToString(", ") { it.label() })
            }
        }
    }
}

/** The name `inboxRequiredFields` gives a missing field: "Vendor", "Effective date", "Gross amount". */
internal fun InboxField.label(): String = when (this) {
    InboxField.Vendor -> str(S.ah_lbl_vendor)
    InboxField.Department -> str(S.department)
    InboxField.Currency -> str(S.asset_currency)
    InboxField.EffectiveDate -> str(S.desktop_payroll_effective_date_title)
    InboxField.PayMethod -> str(S.desktop_payment_method_lower)
    InboxField.GrossAmount -> str(S.desktop_inv_gross_amount_lower)
}

/** The web's four pay methods on this form. */
private val REVIEW_PAY_METHODS = listOf(PayMethod.Bacs, PayMethod.Faster, PayMethod.Wire, PayMethod.Cheque)

private val REVIEW_WIDTH = 1180.dp
private val FORM_WIDTH = 560.dp
private val PREVIEW_HEIGHT = 620.dp
private val LOADING_SPINNER = 30.dp
private val SMALL_SPINNER = 12.dp
private const val PO_ID_CHARS = 8
private const val PENNY = 0.01
private const val SUMMARY_BORDER_ALPHA = 0.3f
