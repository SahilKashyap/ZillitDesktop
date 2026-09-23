// The accountant's Enter Invoice dialog: Upload (the bulk upload) and Manual (the form) tabs.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.feature.invoices.ui.EnterInvoiceForm
import com.zillit.desktop.feature.invoices.ui.EnterTab
import com.zillit.desktop.feature.invoices.ui.InboxEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

@Composable
internal fun EnterInvoiceDialog(state: InvoicesUiState, form: EnterInvoiceForm, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val change = { updated: EnterInvoiceForm -> onEvent(InvoicesEvent.EnterChanged(updated)) }
    ZillitDialogShell(
        title = str(S.desktop_enter_invoice),
        subtitle = str(S.desktop_inv_enter_subtitle),
        onDismiss = { if (!form.busy) onEvent(InvoicesEvent.CloseEnter) },
        visible = true,
        width = ENTER_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InvoicesEvent.CloseEnter) },
                variant = ButtonVariant.Tertiary,
                enabled = !form.busy,
            )
            val pick = state.bulkPick
            if (form.tab == EnterTab.Upload) {
                // The web's Upload tab is the bulk upload; its footer is lifted up here.
                ZillitButton(
                    text = str(S.desktop_inv_upload_and_submit),
                    onClick = { onEvent(InboxEvent.SubmitBulk) },
                    leadingIcon = ZillitIcons.Upload,
                    enabled = pick != null && pick.sendable > 0 && !pick.checking,
                )
            } else {
                ZillitButton(
                    text = if (form.mismatchAcknowledged && form.amountsMismatch) {
                        str(S.desktop_create_anyway)
                    } else {
                        str(S.desktop_submit_invoice)
                    },
                    onClick = { onEvent(InvoicesEvent.SubmitEnter) },
                    enabled = !form.busy,
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
            form.error?.let { ZillitNotice(text = it, tone = StatusTone.Rejected) }
            FileRow(form, onEvent)
            VendorRow(state, form, change)
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(
                    value = form.invoiceNumber,
                    onValueChange = { change(form.copy(invoiceNumber = it)) },
                    label = str(S.desktop_invoice_number_required_label),
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = form.description,
                    onValueChange = { change(form.copy(description = it)) },
                    label = str(S.description),
                    modifier = Modifier.weight(2f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitDateField(
                    value = form.invoiceDate,
                    onValueChange = { change(form.copy(invoiceDate = it)) },
                    label = str(S.desktop_invoice_date_required_label),
                    modifier = Modifier.weight(1f),
                )
                ZillitDateField(
                    value = form.dueDate,
                    onValueChange = { change(form.copy(dueDate = it)) },
                    label = str(S.desktop_due_date_title),
                    placeholder = "30 days if blank",
                    modifier = Modifier.weight(1f),
                )
                ZillitDateField(
                    value = form.effectiveDate,
                    onValueChange = { change(form.copy(effectiveDate = it)) },
                    label = str(S.desktop_effective_date_required_label),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.Bottom,
            ) {
                LabelledSelect(
                    label = str(S.dm_step2_department),
                    modifier = Modifier.weight(1f),
                ) {
                    val options = listOf("") + state.departmentNames.keys.sortedBy { state.departmentName(it) }
                    ZillitSelect(
                        value = form.departmentId,
                        options = options,
                        onSelect = { change(form.copy(departmentId = it)) },
                        label = { id -> if (id.isBlank()) {
                            str(S.desktop_pick_a_department)
                        } else {
                            state.departmentName(id)
                        }},
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LabelledSelect(label = str(S.desktop_payment_method), modifier = Modifier.weight(1f)) {
                    ZillitSelect(
                        value = form.payMethod,
                        options = listOf(PayMethod.Bacs, PayMethod.Wire, PayMethod.Cheque, PayMethod.Faster),
                        onSelect = { change(form.copy(payMethod = it)) },
                        label = { it.label },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LabelledSelect(label = str(S.desktop_bank), modifier = Modifier.weight(1f)) {
                    ZillitSelect(
                        value = form.bankId,
                        options = listOf("") + state.banks.map { it.id },
                        onSelect = { id ->
                            // A picked bank brings its account holder, unless a company is already set.
                            val holder = state.banks.firstOrNull { it.id == id }?.entityId.orEmpty()
                            change(form.copy(bankId = id, companyId = form.companyId.ifBlank { holder }))
                        },
                        label = { id -> state.banks.firstOrNull { it.id == id }?.displayName ?: str(S.none) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                LabelledSelect(label = str(S.company), modifier = Modifier.weight(1f)) {
                    ZillitSelect(
                        value = form.companyId,
                        options = listOf("") + state.companies.map { it.id },
                        onSelect = { change(form.copy(companyId = it)) },
                        label = { id -> state.companies.firstOrNull { it.id == id }?.name ?: str(S.ah_select_company) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LabelledSelect(label = str(S.asset_currency), modifier = Modifier.width(CURRENCY_WIDTH)) {
                    val currency = form.currency.ifBlank { state.projectCurrency }
                    ZillitSelect(
                        value = currency,
                        options = (state.currencyOptions + currency).distinct(),
                        onSelect = { change(form.copy(currency = it)) },
                        label = { it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LabelledSelect(label = str(S.desktop_paid), modifier = Modifier.width(PAID_WIDTH)) {
                    ZillitCheckbox(
                        checked = form.paid,
                        onCheckedChange = { change(form.copy(paid = it)) },
                        label = str(if (form.paid) S.desktop_already_paid else S.desktop_inv_not_paid),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(
                    value = form.poNumber,
                    onValueChange = { change(form.copy(poNumber = it)) },
                    label = str(S.desktop_po_reference),
                    modifier = Modifier.weight(1f),
                )
                if (state.viewer.isTelevision) {
                    ZillitTextField(
                        value = form.episode,
                        onValueChange = { change(form.copy(episode = it)) },
                        label = str(S.episode),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(
                    value = form.net,
                    onValueChange = { onEvent(InvoicesEvent.EnterNetChanged(it)) },
                    label = str(S.desktop_net),
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = form.tax,
                    onValueChange = { onEvent(InvoicesEvent.EnterTaxChanged(it)) },
                    label = str(S.ah_lbl_vat),
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = form.gross,
                    onValueChange = { onEvent(InvoicesEvent.EnterGrossChanged(it)) },
                    label = str(S.desktop_gross_required_label),
                    modifier = Modifier.weight(1f),
                )
            }
            if (form.amountsMismatch) {
                ZillitNotice(text = str(S.desktop_inv_net_tax_not_equal_gross), tone = StatusTone.Pending)
            }
            ZillitText(
                text = "* required",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun FileRow(form: EnterInvoiceForm, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(
            text = if (form.file == null) str(S.desktop_choose_file) else str(S.desktop_replace_file),
            onClick = { onEvent(InvoicesEvent.EnterPickFile) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Upload,
            enabled = !form.busy,
        )
        ZillitText(
            text = form.file?.name ?: "PDF, JPG, PNG, DOC or DOCX, up to 10 MB — required",
            style = ZillitTheme.typography.bodySmall,
            color = if (form.file == null) colors.textMuted else colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        when {
            form.uploading -> StatusWithSpinner(str(S.ah_uploading))
            form.attachment != null -> ZillitStatusPill(label = str(S.sides_uploaded), tone = StatusTone.Done)
        }
    }
}

@Composable
private fun StatusWithSpinner(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSpinner()
        ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
    }
}

@Composable
private fun VendorRow(state: InvoicesUiState, form: EnterInvoiceForm, change: (EnterInvoiceForm) -> Unit) {
    val needle = form.vendorQuery.trim().lowercase()
    val matches: List<Vendor?> = listOf<Vendor?>(null) + state.vendors.values
        .filter { needle.isEmpty() || it.name.lowercase().contains(needle) }
        .sortedBy { it.name.lowercase() }
        .take(VENDOR_LIMIT)
    val selected = state.vendors[form.vendorId]
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.Bottom) {
        ZillitTextField(
            value = form.vendorQuery,
            onValueChange = { change(form.copy(vendorQuery = it)) },
            label = str(S.desktop_vendor_required_label),
            placeholder = str(S.desktop_type_to_filter_suppliers),
            modifier = Modifier.weight(1f),
        )
        LabelledSelect(
            label = if (selected == null) {
                str(S.desktop_pick_from_n_suppliers, state.vendors.size)
            } else {
                str(S.selected)
            },
            modifier = Modifier.weight(1f),
        ) {
            ZillitSelect(
                value = selected,
                options = if (selected != null && selected !in matches) listOf(selected) + matches else matches,
                onSelect = { picked ->
                    change(form.copy(vendorId = picked?.id.orEmpty(), vendorQuery = picked?.name ?: form.vendorQuery))
                },
                label = { it?.name ?: "— none —" },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LabelledSelect(label: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(text = label, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
        content()
    }
}

private const val VENDOR_LIMIT = 40
private val ENTER_WIDTH = 960.dp
private val CURRENCY_WIDTH = 120.dp
private val PAID_WIDTH = 150.dp
