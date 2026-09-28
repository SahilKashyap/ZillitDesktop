// The accountant's Enter Invoice dialog: Upload (the bulk upload) and Manual (the form) tabs.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCalcField
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.AmountField
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.ui.EnterEvent
import com.zillit.desktop.feature.invoices.ui.EnterField
import com.zillit.desktop.feature.invoices.ui.EnterInvoiceForm
import com.zillit.desktop.feature.invoices.ui.EnterTab
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

/**
 * Enter Invoice — the web's `EnterInvoiceModal`: the Upload tab is the bulk
 * upload with Paid offered; the Manual tab is the form, in the web's rows,
 * with every missing field named at once and a split that does not add up
 * confirmed before it is created.
 */
@Composable
internal fun EnterInvoiceDialog(state: InvoicesUiState, form: EnterInvoiceForm, onEvent: (InvoicesEvent) -> Unit) {
    val change = { updated: EnterInvoiceForm -> onEvent(InvoicesEvent.EnterChanged(updated)) }
    val close = { if (!form.busy) onEvent(InvoicesEvent.CloseEnter) }
    ZillitDialogShell(
        title = str(S.desktop_enter_invoice),
        onDismiss = close,
        visible = true,
        width = ENTER_WIDTH,
        actions = {
            val pick = state.bulkPick
            if (form.tab == EnterTab.Upload) {
                // The web's Upload tab is the bulk upload; its footer is lifted up here.
                if (pick != null) BulkActions(pick, onEvent, cancel = close)
            } else {
                val error = form.error
                if (error != null) {
                    ZillitText(
                        text = error,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.danger,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                ZillitButton(
                    text = str(S.cancel),
                    onClick = close,
                    variant = ButtonVariant.Secondary,
                    enabled = !form.busy,
                )
                ZillitButton(
                    text = if (form.saving) str(S.desktop_inv_creating) else str(S.desktop_inv_create_invoice),
                    onClick = { onEvent(InvoicesEvent.SubmitEnter) },
                    leadingIcon = ZillitIcons.Add,
                    enabled = !form.saving,
                    loading = form.saving,
                )
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitTabStrip(
                tabs = EnterTab.entries.map { ZillitTab(it.id, it.label) },
                activeId = form.tab.id,
                onSelect = { id -> onEvent(InvoicesEvent.SelectEnterTab(EnterTab.entries.first { it.id == id })) },
            )
            if (form.tab == EnterTab.Upload) {
                // Files only: extraction, the vendor and the invoice are the server's work.
                state.bulkPick?.let { BulkPanel(it, onEvent) }
                return@Column
            }
            ManualForm(state, form, change, onEvent)
        }
    }
    SplitConfirm(state, form, onEvent)
}

@Composable
private fun ManualForm(
    state: InvoicesUiState,
    form: EnterInvoiceForm,
    change: (EnterInvoiceForm) -> Unit,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val errors = form.errors
    val required = { label: String -> "$label *" }
    FieldRow {
        LabelledField(str(S.ah_lbl_vendor), Modifier.weight(1f), isError = EnterField.Vendor in errors) {
            VendorPicker(
                state = state,
                vendorId = form.vendorId,
                pendingName = form.pendingVendorName,
                onPick = { change(form.copy(vendorId = it, pendingVendorName = null)) },
                onCreate = { onEvent(EnterEvent.CreateVendor(it)) },
                isError = EnterField.Vendor in errors,
            )
            PendingVendorHint(form.pendingVendorName?.takeIf { form.vendorId.isBlank() })
        }
        ZillitTextField(
            value = form.invoiceNumber,
            onValueChange = { change(form.copy(invoiceNumber = it)) },
            label = required(str(S.desktop_inv_invoice_number_label)),
            placeholder = str(S.desktop_inv_eg_inv_plain),
            errorText = "".takeIf { EnterField.InvoiceNumber in errors },
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = form.description,
            onValueChange = { change(form.copy(description = it)) },
            label = str(S.description),
            placeholder = str(S.desktop_inv_eg_studio_hire),
            modifier = Modifier.weight(1f),
        )
    }
    FieldRow {
        ZillitDateField(
            value = form.invoiceDate,
            onValueChange = { change(form.copy(invoiceDate = it)) },
            label = required(str(S.desktop_invoice_date_title)),
            errorText = "".takeIf { EnterField.InvoiceDate in errors },
            modifier = Modifier.weight(1f),
        )
        ZillitDateField(
            value = form.dueDate,
            onValueChange = { change(form.copy(dueDate = it)) },
            label = str(S.desktop_due_date_title),
            modifier = Modifier.weight(1f),
        )
        ZillitDateField(
            value = form.effectiveDate,
            onValueChange = { change(form.copy(effectiveDate = it)) },
            label = required(str(S.ah_lbl_eff_date)),
            errorText = "".takeIf { EnterField.EffectiveDate in errors },
            minDate = state.effectiveMinDate(),
            modifier = Modifier.weight(1f),
        )
    }
    FieldRow {
        LabelledField(required(str(S.department)), Modifier.weight(1f), isError = EnterField.Department in errors) {
            IdPicker(
                value = form.departmentId,
                options = state.departmentChoices(),
                label = { state.departmentName(it) },
                placeholder = str(S.desktop_inv_select_ellipsis),
                onSelect = { change(form.copy(departmentId = it)) },
                isError = EnterField.Department in errors,
            )
        }
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
                onSelect = { change(form.copy(companyId = it)) },
                enabled = state.companies.isNotEmpty(),
            )
        }
        LabelledField(str(S.desktop_payment_method), Modifier.weight(1f)) {
            ZillitSelect(
                value = form.payMethod,
                options = ENTER_PAY_METHODS,
                onSelect = { change(form.copy(payMethod = it)) },
                label = { it.label },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    FieldRow {
        LabelledField(str(S.desktop_bank), Modifier.weight(1f)) {
            IdPicker(
                value = form.bankId,
                options = state.banks.map { it.id },
                label = { id -> state.banks.firstOrNull { it.id == id }?.displayName.orEmpty() },
                searchText = { id -> state.banks.firstOrNull { it.id == id }?.let { "${it.name} ${it.bankName}" }.orEmpty() },
                placeholder = if (state.banks.isEmpty()) str(S.desktop_inv_no_bank_accounts) else str(S.desktop_inv_select_bank),
                onSelect = { change(form.copy(bankId = it)) },
                enabled = state.banks.isNotEmpty(),
            )
        }
        if (state.viewer.isTelevision) {
            ZillitTextField(
                value = form.episode,
                onValueChange = { change(form.copy(episode = it)) },
                label = str(S.episode),
                placeholder = str(S.desktop_inv_eg_ep_101),
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
    }
    FieldRow {
        LabelledField(str(S.asset_currency), Modifier.weight(1f)) {
            val currency = form.currency.ifBlank { state.projectCurrency }
            IdPicker(
                value = currency,
                options = (state.currencyOptions + currency).distinct(),
                label = { state.currencyLabel(it) },
                placeholder = str(S.desktop_ce_cards_select_currency),
                onSelect = { change(form.copy(currency = it)) },
            )
        }
        ZillitTextField(
            value = form.poNumber,
            onValueChange = { change(form.copy(poNumber = it)) },
            label = str(S.desktop_inv_po_reference_title),
            placeholder = str(S.desktop_inv_eg_po_0087),
            modifier = Modifier.weight(1f),
        )
        LabelledField(str(S.desktop_paid), Modifier.weight(1f)) {
            PaidSwitch(form, change)
        }
    }
    FieldRow {
        ZillitCalcField(
            value = form.net,
            onCommit = { onEvent(EnterEvent.Amount(AmountField.Net, it)) },
            label = str(S.desktop_inv_net_amount),
            modifier = Modifier.weight(1f),
        )
        ZillitCalcField(
            value = form.tax,
            onCommit = { onEvent(EnterEvent.Amount(AmountField.Tax, it)) },
            label = str(S.ah_lbl_tax_amt),
            modifier = Modifier.weight(1f),
        )
        ZillitCalcField(
            value = form.gross,
            onCommit = { onEvent(EnterEvent.Amount(AmountField.Gross, it)) },
            label = required(str(S.desktop_inv_gross_amount)),
            errorText = "".takeIf { EnterField.GrossAmount in errors },
            modifier = Modifier.weight(1f),
        )
    }
    SplitStrip(form.amounts, form.currency.ifBlank { state.projectCurrency })
    LabelledField(required(str(S.attachment)), isError = EnterField.Attachment in errors) {
        AttachmentRow(form, onEvent)
    }
    if (errors.isNotEmpty()) {
        ZillitText(
            text = EnterField.entries.mapNotNull { errors[it] }.joinToString(" · "),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.danger,
        )
    }
}

/** "Already paid" / "Not paid" beside a switch — a field of the form, not a stray control. */
@Composable
private fun PaidSwitch(form: EnterInvoiceForm, change: (EnterInvoiceForm) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = if (form.paid) str(S.desktop_inv_already_paid_lower) else str(S.desktop_inv_not_paid),
            style = ZillitTheme.typography.bodyMedium,
            color = if (form.paid) colors.textPrimary else colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        ZillitSwitch(checked = form.paid, onCheckedChange = { change(form.copy(paid = it)) })
    }
}

/**
 * The attachment: its name and size with a ✕ once picked ("Uploading..."
 * while it goes up), or the dashed "Attach document (PDF, JPG, PNG)" —
 * red while the form is missing it.
 */
@Composable
private fun AttachmentRow(form: EnterInvoiceForm, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val file = form.file
    if (file == null) {
        val missing = EnterField.Attachment in form.errors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (missing) colors.danger else colors.border, ZillitTheme.shapes.medium)
                .clickable(enabled = !form.busy) { onEvent(InvoicesEvent.EnterPickFile) }
                .padding(vertical = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitIcon(icon = ZillitIcons.File, tint = if (missing) colors.danger else colors.textMuted, size = FILE_ICON)
            ZillitText(
                text = str(S.desktop_inv_attach_document),
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = if (missing) colors.danger else colors.textMuted,
            )
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceSunken, ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (form.uploading) {
            ZillitSpinner(size = FILE_ICON)
            ZillitText(
                text = str(S.desktop_inv_uploading_dots),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
        } else {
            ZillitIcon(icon = ZillitIcons.File, tint = colors.accent, size = FILE_ICON)
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = file.name,
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                ZillitText(
                    text = str(S.desktop_size_kb, kilobytes(file.bytes.size.toLong())),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = str(S.remove),
            onClick = { onEvent(EnterEvent.ClearFile) },
            enabled = !form.saving,
        )
    }
}

/** `(size / 1024).toFixed(1)`. */
private fun kilobytes(bytes: Long): String {
    val tenths = kotlin.math.round(bytes * TENTHS / KB).toLong()
    return "${tenths / TENTHS.toLong()}.${tenths % TENTHS.toLong()}"
}

/** "Amounts don't match" — Create anyway, or Go back; never created silently. */
@Composable
private fun SplitConfirm(state: InvoicesUiState, form: EnterInvoiceForm, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_inv_amounts_dont_match),
        visible = form.confirmSplit,
        onDismiss = { if (!form.saving) onEvent(EnterEvent.CancelSplit) },
        icon = ZillitIcons.Warning,
        scrollable = false,
        actions = {
            ZillitButton(
                text = str(S.dm_nda_go_back),
                onClick = { onEvent(EnterEvent.CancelSplit) },
                variant = ButtonVariant.Tertiary,
                enabled = !form.saving,
            )
            ZillitButton(
                text = if (form.saving) str(S.desktop_inv_creating) else str(S.desktop_create_anyway),
                onClick = { onEvent(EnterEvent.ConfirmSplit) },
                loading = form.saving,
            )
        },
    ) {
        val currency = form.currency.ifBlank { state.projectCurrency }
        val figures = SplitFigures.of(form.amounts)
        val money = { value: Double -> InvoiceFormat.money(value, currency) }
        ZillitText(
            text = str(
                S.desktop_inv_split_create_confirm,
                money(figures.net),
                money(figures.tax),
                money(figures.sum),
                money(figures.gross),
                money(kotlin.math.abs(figures.diff)),
                figures.overUnder(),
            ),
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}

/** The web's four pay methods on this form, in its order. */
private val ENTER_PAY_METHODS = listOf(PayMethod.Bacs, PayMethod.Wire, PayMethod.Cheque, PayMethod.Faster)

private val ENTER_WIDTH = 1040.dp
private val FILE_ICON = 16.dp
private const val KB = 1024.0
private const val TENTHS = 10.0
