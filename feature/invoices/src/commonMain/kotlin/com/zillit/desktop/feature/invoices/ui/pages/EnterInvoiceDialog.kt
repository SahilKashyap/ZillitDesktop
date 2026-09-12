// The accountant's Enter Invoice dialog: Upload (extraction-prefilled) and Manual tabs over one form.
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
import com.zillit.desktop.feature.invoices.domain.PayMethod
import com.zillit.desktop.feature.invoices.domain.Vendor
import com.zillit.desktop.feature.invoices.ui.EnterInvoiceForm
import com.zillit.desktop.feature.invoices.ui.EnterTab
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

@Composable
internal fun EnterInvoiceDialog(state: InvoicesUiState, form: EnterInvoiceForm, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val change = { updated: EnterInvoiceForm -> onEvent(InvoicesEvent.EnterChanged(updated)) }
    ZillitDialogShell(
        title = "Enter invoice",
        subtitle = "Lands in the inbox for processing",
        onDismiss = { if (!form.busy) onEvent(InvoicesEvent.CloseEnter) },
        visible = true,
        width = ENTER_WIDTH,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(InvoicesEvent.CloseEnter) },
                variant = ButtonVariant.Tertiary,
                enabled = !form.busy,
            )
            ZillitButton(
                text = if (form.mismatchAcknowledged && form.amountsMismatch) "Create anyway" else "Submit Invoice",
                onClick = { onEvent(InvoicesEvent.SubmitEnter) },
                enabled = !form.busy,
                loading = form.saving,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitTabStrip(
                tabs = EnterTab.entries.map { ZillitTab(it.id, it.label) },
                activeId = form.tab.id,
                onSelect = { id -> onEvent(InvoicesEvent.SelectEnterTab(EnterTab.entries.first { it.id == id })) },
            )
            form.error?.let { ZillitNotice(text = it, tone = StatusTone.Rejected) }
            FileRow(form, onEvent)
            if (form.tab == EnterTab.Upload && form.extraction != null) {
                ZillitNotice(
                    text = "Fields prefilled from the document" +
                        (form.extraction.confidence?.let { " · ${it.toInt()}% confidence" } ?: "") +
                        ". Check them before submitting.",
                    tone = StatusTone.Ready,
                )
            }
            if (form.tab == EnterTab.Upload && form.extractionFailed) {
                ZillitNotice(
                    text = "Extraction could not read this document — fill the fields in by hand.",
                    tone = StatusTone.Pending,
                )
            }
            VendorRow(state, form, change)
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(
                    value = form.invoiceNumber,
                    onValueChange = { change(form.copy(invoiceNumber = it)) },
                    label = "Invoice number *",
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = form.description,
                    onValueChange = { change(form.copy(description = it)) },
                    label = "Description",
                    modifier = Modifier.weight(2f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitDateField(
                    value = form.invoiceDate,
                    onValueChange = { change(form.copy(invoiceDate = it)) },
                    label = "Invoice date *",
                    modifier = Modifier.weight(1f),
                )
                ZillitDateField(
                    value = form.dueDate,
                    onValueChange = { change(form.copy(dueDate = it)) },
                    label = "Due date",
                    placeholder = "30 days if blank",
                    modifier = Modifier.weight(1f),
                )
                ZillitDateField(
                    value = form.effectiveDate,
                    onValueChange = { change(form.copy(effectiveDate = it)) },
                    label = "Effective date *",
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.Bottom,
            ) {
                LabelledSelect(
                    label = "Department *",
                    modifier = Modifier.weight(1f),
                ) {
                    val options = listOf("") + state.departmentNames.keys.sortedBy { state.departmentName(it) }
                    ZillitSelect(
                        value = form.departmentId,
                        options = options,
                        onSelect = { change(form.copy(departmentId = it)) },
                        label = { id -> if (id.isBlank()) "Pick a department" else state.departmentName(id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LabelledSelect(label = "Payment method", modifier = Modifier.weight(1f)) {
                    ZillitSelect(
                        value = form.payMethod,
                        options = listOf(PayMethod.Bacs, PayMethod.Wire, PayMethod.Cheque, PayMethod.Faster),
                        onSelect = { change(form.copy(payMethod = it)) },
                        label = { it.label },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LabelledSelect(label = "Bank", modifier = Modifier.weight(1f)) {
                    ZillitSelect(
                        value = form.bankId,
                        options = listOf("") + state.banks.map { it.id },
                        onSelect = { change(form.copy(bankId = it)) },
                        label = { id -> state.banks.firstOrNull { it.id == id }?.displayName ?: "None" },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(
                    value = form.currency,
                    onValueChange = { change(form.copy(currency = it.uppercase())) },
                    label = "Currency",
                    placeholder = state.projectCurrency,
                    modifier = Modifier.width(CURRENCY_WIDTH),
                )
                ZillitTextField(
                    value = form.poNumber,
                    onValueChange = { change(form.copy(poNumber = it)) },
                    label = "PO reference",
                    modifier = Modifier.weight(1f),
                )
                if (state.viewer.isTelevision) {
                    ZillitTextField(
                        value = form.episode,
                        onValueChange = { change(form.copy(episode = it)) },
                        label = "Episode",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(
                    value = form.net,
                    onValueChange = { onEvent(InvoicesEvent.EnterNetChanged(it)) },
                    label = "Net",
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = form.tax,
                    onValueChange = { onEvent(InvoicesEvent.EnterTaxChanged(it)) },
                    label = "Tax",
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = form.gross,
                    onValueChange = { onEvent(InvoicesEvent.EnterGrossChanged(it)) },
                    label = "Gross *",
                    modifier = Modifier.weight(1f),
                )
            }
            if (form.amountsMismatch) {
                ZillitNotice(text = "Net + Tax does not equal Gross.", tone = StatusTone.Pending)
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
            text = if (form.file == null) "Choose file" else "Replace file",
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
            form.uploading -> StatusWithSpinner("Uploading…")
            form.extracting -> StatusWithSpinner("Extracting…")
            form.attachment != null -> ZillitStatusPill(label = "Uploaded", tone = StatusTone.Done)
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
            label = "Vendor *",
            placeholder = "Type to filter suppliers",
            modifier = Modifier.weight(1f),
        )
        LabelledSelect(
            label = if (selected == null) "Pick from ${state.vendors.size} suppliers" else "Selected",
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
