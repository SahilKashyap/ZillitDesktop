// The department's Upload Invoice sheet and the delete confirmation.
@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.UploadType
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.UploadFlow
import com.zillit.desktop.feature.invoices.ui.UploadStage

@Composable
internal fun UploadDialog(state: InvoicesUiState, flow: UploadFlow, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val ready = flow.stage == UploadStage.Ready && flow.attachment != null
    ZillitDialogShell(
        title = str(S.ah_upload_invoice),
        subtitle = flow.file.name,
        onDismiss = { if (!flow.sending) onEvent(InvoicesEvent.CancelUpload) },
        visible = true,
        width = UPLOAD_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InvoicesEvent.CancelUpload) },
                variant = ButtonVariant.Tertiary,
                enabled = !flow.sending,
            )
            ZillitButton(
                text = str(S.desktop_send_to_accounts),
                onClick = { onEvent(InvoicesEvent.SendUpload) },
                enabled = ready && flow.type != null,
                loading = flow.sending,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            when (flow.stage) {
                UploadStage.Uploading -> StageLine("Uploading ${flow.file.name}…")
                UploadStage.Extracting -> StageLine(str(S.desktop_extracting_invoice_data))
                UploadStage.Ready -> {
                    val x = flow.extraction
                    if (flow.extractionFailed || x == null) {
                        ZillitNotice(
                            text = str(S.desktop_inv_uploaded_extraction_failed),
                            tone = StatusTone.Pending,
                        )
                    } else {
                        val currency = x.currency.ifBlank { state.projectCurrency }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.surfaceSunken, ZillitTheme.shapes.medium)
                                .padding(ZillitTheme.spacing.md),
                            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                        ) {
                            ZillitText(
                                text = str(S.desktop_read_from_the_document),
                                style = ZillitTheme.typography.label,
                                color = colors.textSecondary,
                            )
                            listOf(
                                str(S.supplier) to x.supplierName,
                                str(S.desktop_invoice_number) to x.invoiceNumber,
                                str(S.desktop_invoice_date) to x.invoiceDate,
                                str(S.desktop_due_date_title) to x.dueDate,
                                str(S.desktop_gross) to (x.gross?.let { InvoiceFormat.money(it, currency) } ?: ""),
                                "PO number" to x.poNumber,
                                str(S.desktop_confidence) to (x.confidence?.let { "${it.toInt()}%" } ?: ""),
                            ).filter { it.second.isNotBlank() }.forEach { (label, value) ->
                                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                                    ZillitText(
                                        text = label,
                                        style = ZillitTheme.typography.bodySmall,
                                        color = colors.textMuted,
                                        modifier = Modifier.weight(1f),
                                    )
                                    ZillitText(
                                        text = value,
                                        style = ZillitTheme.typography.bodySmall,
                                        color = colors.textPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            ZillitText(
                text = str(S.desktop_what_is_this_invoice),
                style = ZillitTheme.typography.label,
                color = colors.textSecondary,
            )
            UploadType.entries.forEach { type ->
                val selected = flow.type == type
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected) colors.accentSoft else colors.surface, ZillitTheme.shapes.medium)
                        .border(1.dp, if (selected) colors.accent else colors.border, ZillitTheme.shapes.medium)
                        .clickable(enabled = ready && !flow.sending) { onEvent(InvoicesEvent.ChooseUploadType(type)) }
                        .padding(ZillitTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                ) {
                    ZillitText(text = type.label, style = ZillitTheme.typography.bodyMedium, color = colors.textPrimary)
                    ZillitText(text = type.hint, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                }
            }
        }
    }
}

@Composable
private fun StageLine(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSpinner()
        ZillitText(text = text, style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textSecondary)
    }
}

@Composable
internal fun DeleteDialog(state: InvoicesUiState, invoice: Invoice, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.ah_delete_invoice),
        onDismiss = { onEvent(InvoicesEvent.CancelDelete) },
        visible = true,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InvoicesEvent.CancelDelete) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.delete),
                onClick = { onEvent(InvoicesEvent.ConfirmDelete) },
                variant = ButtonVariant.Danger,
                loading = state.busy,
            )
        },
    ) {
        ZillitText(
            text = "Delete invoice ${invoice.displayNumber} (${state.vendorName(invoice)})? This cannot be undone.",
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}

private val UPLOAD_WIDTH = 560.dp
