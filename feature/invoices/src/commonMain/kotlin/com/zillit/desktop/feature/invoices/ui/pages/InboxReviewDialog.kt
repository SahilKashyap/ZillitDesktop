// The Inbox review: the document beside the editable form, the order match, and its confirmations.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
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
import com.zillit.desktop.feature.invoices.ui.InboxEvent
import com.zillit.desktop.feature.invoices.ui.InboxForm
import com.zillit.desktop.feature.invoices.ui.InboxReview
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.QueryEvent
import com.zillit.desktop.feature.invoices.ui.decodePreviewPages

/**
 * One inbox invoice under review — the web's `InboxReviewModal`: what the
 * OCR read (or nothing, for a manual entry), corrected by hand, matched to
 * one or more orders, and accepted on to pre-approval.
 */
@Composable
internal fun InboxReviewDialog(state: InvoicesUiState, review: InboxReview, onEvent: (InvoicesEvent) -> Unit) {
    val locked = state.isLocked(review.invoice)
    ZillitDialogShell(
        title = str(S.desktop_invoice_named, review.invoice.displayNumber),
        subtitle = state.vendorName(review.invoice),
        visible = true,
        onDismiss = { onEvent(InboxEvent.Close) },
        width = REVIEW_WIDTH,
        icon = ZillitIcons.Inbox,
        actions = {
            ZillitButton(
                text = str(S.ah_query_label),
                onClick = { onEvent(QueryEvent.Open(review.invoice)) },
                variant = ButtonVariant.Tertiary,
                leadingIcon = ZillitIcons.Chat,
            )
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InboxEvent.Close) },
                variant = ButtonVariant.Tertiary,
                enabled = !review.busy,
            )
            if (!locked) {
                ZillitButton(
                    text = str(S.desktop_inv_accept_and_match),
                    onClick = { onEvent(InboxEvent.Accept) },
                    enabled = !review.loading && !review.busy,
                    loading = review.busy,
                )
            }
        },
    ) {
        if (locked) {
            ZillitNotice(
                text = str(S.desktop_inv_locked_period, state.periodLock.lockedThrough),
                tone = StatusTone.Escalated,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            ReviewDocument(review, Modifier.weight(1f))
            Column(
                modifier = Modifier.width(FORM_WIDTH),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                if (review.loading) {
                    ZillitSpinner()
                } else {
                    ReviewForm(state, review, enabled = !locked && !review.busy, onEvent = onEvent)
                    OrderMatch(state, review, enabled = !locked && !review.busy, onEvent = onEvent)
                }
            }
        }
    }
    ReviewConfirmations(state, review, onEvent)
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
            attachment == null -> MutedLine(str(S.desktop_no_document_attached))
            pages.isNotEmpty() -> PreviewPages(pages, attachment.name)
            review.previewLoading -> ZillitSpinner()
            else -> MutedLine(
                if (review.previewFailed) str(S.desktop_inv_could_not_load_preview) else attachment.name,
            )
        }
    }
}

/** The fields, each with its own error once Accept has found it empty. */
@Composable
private fun ReviewForm(
    state: InvoicesUiState,
    review: InboxReview,
    enabled: Boolean,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val form = review.form
    val edit: (InboxForm) -> Unit = { onEvent(InboxEvent.Edit(it)) }
    val error: (InboxField, String) -> String? = { field, label ->
        str(S.recce_required_field, label).takeIf { field in review.errors }
    }
    FieldRow {
        ZillitTextField(
            value = form.invoiceNumber,
            onValueChange = { edit(form.copy(invoiceNumber = it)) },
            label = str(S.desktop_invoice_number),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        LabelledPicker(
            label = str(S.ah_lbl_vendor),
            value = form.vendorId,
            options = listOf("") + state.vendors.values.sortedBy { it.name.lowercase() }.map { it.id },
            text = { id -> state.vendors[id]?.name ?: str(S.desktop_inv_select_vendor) },
            enabled = enabled,
        ) { edit(form.copy(vendorId = it)) }
    }
    error(InboxField.Vendor, str(S.ah_lbl_vendor))?.let { FieldError(it) }
    ZillitTextField(
        value = form.description,
        onValueChange = { edit(form.copy(description = it)) },
        label = str(S.description),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
    FieldRow {
        ZillitDateField(
            form.invoiceDate,
            { edit(form.copy(invoiceDate = it)) },
            Modifier.weight(1f),
            str(S.desktop_invoice_date),
            enabled = enabled,
        )
        ZillitDateField(
            form.dueDate,
            { edit(form.copy(dueDate = it)) },
            Modifier.weight(1f),
            str(S.desktop_due_date_title),
            enabled = enabled,
        )
        ZillitDateField(
            value = form.effectiveDate,
            onValueChange = { edit(form.copy(effectiveDate = it)) },
            modifier = Modifier.weight(1f),
            label = str(S.ah_lbl_eff_date),
            enabled = enabled,
            errorText = error(InboxField.EffectiveDate, str(S.ah_lbl_eff_date)),
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
        LabelledPicker(
            label = str(S.department),
            value = form.departmentId,
            options = listOf("") + state.departmentNames.keys.sortedBy { state.departmentName(it) },
            text = { id -> if (id.isBlank()) "—" else state.departmentName(id) },
            enabled = enabled,
        ) { edit(form.copy(departmentId = it)) }
        LabelledPicker(
            label = str(S.desktop_payment_method),
            value = form.payMethod.wire,
            options = REVIEW_PAY_METHODS.map { it.wire },
            text = { wire -> PayMethod.from(wire).label },
            enabled = enabled,
        ) { edit(form.copy(payMethod = PayMethod.from(it))) }
        val currencies = (listOf(state.projectCurrency, form.currency) + state.rates.rates.keys)
            .map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        LabelledPicker(
            label = str(S.asset_currency),
            value = form.currency.ifBlank { state.projectCurrency }.uppercase(),
            options = currencies,
            text = { it },
            enabled = enabled,
        ) { edit(form.copy(currency = it)) }
    }
    if (InboxField.Department in review.errors) FieldError(str(S.recce_required_field, str(S.department)))
    FieldRow {
        LabelledPicker(
            label = str(S.company),
            value = form.companyId,
            options = listOf("") + state.companies.map { it.id },
            text = { id -> state.companies.firstOrNull { it.id == id }?.name ?: str(S.ah_select_company) },
            enabled = enabled && state.companies.isNotEmpty(),
        ) { edit(form.copy(companyId = it)) }
        LabelledPicker(
            label = str(S.desktop_bank),
            value = form.bankId,
            options = listOf("") + state.banks.map { it.id },
            text = { id -> state.banks.firstOrNull { it.id == id }?.displayName ?: str(S.desktop_inv_select_bank) },
            enabled = enabled && state.banks.isNotEmpty(),
        ) { picked ->
            val entity = state.banks.firstOrNull { it.id == picked }?.entityId.orEmpty()
            edit(form.copy(bankId = picked, companyId = form.companyId.ifBlank { entity }))
        }
        if (state.viewer.isTelevision) {
            ZillitTextField(
                value = form.episode,
                onValueChange = { edit(form.copy(episode = it)) },
                label = str(S.episode),
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Net, Tax and Gross, kept consistent; a split that does not add up is said, not blocked. */
@Composable
private fun ReviewAmounts(
    state: InvoicesUiState,
    form: InboxForm,
    review: InboxReview,
    enabled: Boolean,
    onEvent: (InvoicesEvent) -> Unit,
) {
    FieldRow {
        ZillitTextField(
            value = form.amounts.net,
            onValueChange = { onEvent(InboxEvent.EditAmount(AmountField.Net, it)) },
            label = str(S.desktop_inv_net_amount),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = form.amounts.tax,
            onValueChange = { onEvent(InboxEvent.EditAmount(AmountField.Tax, it)) },
            label = str(S.ah_lbl_tax_amt),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = form.amounts.gross,
            onValueChange = { onEvent(InboxEvent.EditAmount(AmountField.Gross, it)) },
            label = str(S.desktop_inv_gross_amount),
            enabled = enabled,
            errorText = str(S.recce_required_field, str(S.desktop_inv_gross_amount))
                .takeIf { InboxField.GrossAmount in review.errors },
            modifier = Modifier.weight(1f),
        )
    }
    if (InboxTriage.splitMismatch(form.amounts)) {
        ZillitNotice(text = splitMessage(state, form), tone = StatusTone.Pending)
    }
}

/**
 * The order match: this vendor's and the reader's own orders as suggestions,
 * every other open order to search, the picks as removable chips with the
 * balance against the gross, and the note stored on each link.
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
    val others = review.suggestions.allPos.filter { it.poId !in picked && suggested.none { s -> s.poId == it.poId } }
    val currency = review.form.currency.ifBlank { state.projectCurrency }
    FieldRow {
        LabelledPicker(
            label = str(S.desktop_inv_po_suggestions),
            value = "",
            options = listOf("") + suggested.map { it.poId },
            text = { id ->
                suggested.firstOrNull { it.poId == id }
                    ?.let { "${it.label} · ${InvoiceFormat.money(it.grossAmount, currency)}" }
                    ?: str(S.desktop_inv_select_suggested_po)
            },
            enabled = enabled && !review.suggestionsLoading && suggested.isNotEmpty(),
        ) { id ->
            suggested.firstOrNull { it.poId == id }
                ?.let { onEvent(InboxEvent.AddPo(PoPick(it.poId, it.label, it.grossAmount))) }
        }
        LabelledPicker(
            label = str(S.ah_all_purchase_orders),
            value = "",
            options = listOf("") + others.map { it.poId },
            text = { id ->
                others.firstOrNull { it.poId == id }?.let { "${it.label} · ${it.vendorName.ifBlank { "—" }}" }
                    ?: str(S.desktop_inv_search_all_pos)
            },
            enabled = enabled && others.isNotEmpty(),
        ) { id ->
            others.firstOrNull { it.poId == id }
                ?.let { onEvent(InboxEvent.AddPo(PoPick(it.poId, it.label, it.grossAmount))) }
        }
    }
    if (review.form.picks.isNotEmpty()) PickedOrders(state, review, enabled, onEvent)
}

@Composable
private fun PickedOrders(
    state: InvoicesUiState,
    review: InboxReview,
    enabled: Boolean,
    onEvent: (InvoicesEvent) -> Unit,
) {
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
                    style = ZillitTheme.typography.labelSmall,
                )
                if (enabled) {
                    ZillitIconButton(
                        icon = ZillitIcons.Close,
                        contentDescription = str(S.remove),
                        onClick = { onEvent(InboxEvent.RemovePo(pick.id)) },
                    )
                }
            }
        }
        val gross = InboxTriage.amount(review.form.amounts.gross)
        InboxTriage.poBalance(gross, review.form.picks)?.let { balance ->
            val total = review.form.picks.sumOf { it.gross ?: 0.0 }
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
    }
    ZillitTextField(
        value = review.form.matchNotes,
        onValueChange = { onEvent(InboxEvent.Edit(review.form.copy(matchNotes = it))) },
        label = str(S.desktop_inv_match_notes),
        helperText = str(S.desktop_inv_match_notes_hint),
        singleLine = false,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FieldError(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.danger)
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
    val net = InboxTriage.amount(form.amounts.net)
    val tax = InboxTriage.amount(form.amounts.tax)
    val gross = InboxTriage.amount(form.amounts.gross)
    val diff = net + tax - gross
    val money = { value: Double -> InvoiceFormat.money(value, currency) }
    val drift = if (diff > 0) {
        str(S.desktop_inv_amount_over, money(diff))
    } else {
        str(S.desktop_inv_amount_under, money(-diff))
    }
    return str(S.desktop_inv_split_confirm, money(net), money(tax), money(net + tax), money(gross), drift)
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
            text = str(S.desktop_inv_some_not_ready_message, blocked.size, state.selected.size),
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
                MutedLine(entry.missing.joinToString(", ") { it.label() })
            }
        }
    }
}

internal fun InboxField.label(): String = when (this) {
    InboxField.Vendor -> str(S.ah_lbl_vendor)
    InboxField.Department -> str(S.department)
    InboxField.Currency -> str(S.asset_currency)
    InboxField.EffectiveDate -> str(S.ah_lbl_eff_date)
    InboxField.PayMethod -> str(S.desktop_payment_method)
    InboxField.GrossAmount -> str(S.desktop_inv_gross_amount)
}

/** The web's four pay methods on this form. */
private val REVIEW_PAY_METHODS = listOf(PayMethod.Bacs, PayMethod.Wire, PayMethod.Cheque, PayMethod.Faster)

private val REVIEW_WIDTH = 1180.dp
private val FORM_WIDTH = 560.dp
private val PREVIEW_HEIGHT = 620.dp
private const val PO_ID_CHARS = 8
private const val PENNY = 0.01
