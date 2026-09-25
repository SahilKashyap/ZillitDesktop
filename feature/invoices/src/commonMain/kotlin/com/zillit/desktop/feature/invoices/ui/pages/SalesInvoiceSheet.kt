// Sales Invoices: the full-page form, the invoice preview, its PDF, history and delete confirm.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.AhIcons
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.CodedLine
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.SalesInvoice
import com.zillit.desktop.feature.invoices.domain.SalesInvoiceStatus
import com.zillit.desktop.feature.invoices.domain.SalesInvoices
import com.zillit.desktop.feature.invoices.domain.SalesTerms
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState
import com.zillit.desktop.feature.invoices.ui.SalesEvent
import com.zillit.desktop.feature.invoices.ui.SalesField
import com.zillit.desktop.feature.invoices.ui.SalesInvoiceDraft
import com.zillit.desktop.feature.invoices.ui.SalesPdf
import com.zillit.desktop.feature.invoices.ui.decodePreviewPages

// -- the form ------------------------------------------------------------------------

/**
 * New / Edit Invoice — the web's full-page form (`SalesPage.jsx:528-805`), in
 * the list's place: `INVOICES / AP / Sales Invoices / New Invoice`, Cancel and
 * Create / Update Invoice; then Invoice Details (client, terms, the address,
 * the dates and currency) and Line Items.
 */
@Composable
internal fun ColumnScope.SalesInvoiceFormView(
    state: InvoicesUiState,
    draft: SalesInvoiceDraft,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val editing = draft.editingId != null
    FormTopBar(
        section = str(S.desktop_sales_invoices),
        title = str(if (editing) S.desktop_inv_edit_invoice else S.desktop_inv_new_invoice),
        onBack = { onEvent(InvoicesEvent.CancelSalesInvoice) },
    ) {
        ZillitButton(
            text = str(S.cancel),
            onClick = { onEvent(InvoicesEvent.CancelSalesInvoice) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = !draft.busy,
        )
        ZillitButton(
            text = when {
                draft.busy -> str(S.ah_saving)
                editing -> str(S.desktop_inv_update_invoice)
                else -> str(S.desktop_inv_create_invoice)
            },
            onClick = { onEvent(InvoicesEvent.ConfirmSalesInvoice) },
            size = ButtonSize.Small,
            enabled = !draft.busy,
            loading = draft.busy,
        )
    }
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        SalesDetailsCard(state, draft, onEvent)
        LineDraftCard(
            state = state,
            draft = draft.lines,
            currency = draft.currency.ifBlank { state.projectCurrency },
            frozen = draft.busy,
            error = draft.lineError,
        ) { onEvent(InvoicesEvent.EditSalesLines(it)) }
    }
}

/** "Invoice Details" — three rows of fields, the address under its own sub-header. */
@Suppress("LongMethod") // The web's three field rows and the address block, one call per field.
@Composable
private fun SalesDetailsCard(state: InvoicesUiState, draft: SalesInvoiceDraft, onEvent: (InvoicesEvent) -> Unit) {
    val enabled = !draft.busy
    val edit = { next: SalesInvoiceDraft -> onEvent(InvoicesEvent.EditSalesInvoice(next)) }
    val address = draft.address
    ZillitSectionCard(
        title = str(S.desktop_inv_invoice_details),
        icon = ZillitIcons.File,
        action = {
            ZillitText(
                text = str(S.desktop_inv_required_fields),
                style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = ZillitTheme.colors.textMuted,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            FieldRow {
                ZillitTextField(
                    value = draft.clientName,
                    onValueChange = { edit(draft.copy(clientName = it)) },
                    label = str(S.desktop_inv_client_billed_to),
                    placeholder = str(S.desktop_inv_company_or_client_name),
                    errorText = draft.errors[SalesField.ClientName],
                    enabled = enabled,
                    modifier = Modifier.weight(2f),
                )
                CaptionedField(str(S.desktop_inv_payment_terms), Modifier.weight(1f)) {
                    ZillitSelect(
                        value = draft.payTerms,
                        options = SalesTerms.entries,
                        onSelect = { edit(draft.copy(payTerms = it)) },
                        label = { it.label },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitIcon(icon = AhIcons.MapPin, tint = ZillitTheme.colors.accentText, size = ADDRESS_ICON)
                FactLabel(str(S.address))
            }
            FieldRow {
                ZillitTextField(
                    value = address.line1,
                    onValueChange = { edit(draft.copy(address = address.copy(line1 = it))) },
                    label = str(S.address_line_1),
                    placeholder = str(S.ah_street_hint),
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = address.line2,
                    onValueChange = { edit(draft.copy(address = address.copy(line2 = it))) },
                    label = str(S.desktop_inv_address_line_2_optional),
                    placeholder = str(S.ah_suite_hint),
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = address.city,
                    onValueChange = { edit(draft.copy(address = address.copy(city = it))) },
                    label = str(S.city),
                    placeholder = str(S.ah_city_hint),
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
            }
            FieldRow {
                ZillitTextField(
                    value = address.state,
                    onValueChange = { edit(draft.copy(address = address.copy(state = it))) },
                    label = str(S.desktop_inv_state_county_optional),
                    placeholder = str(S.ah_county_hint),
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = address.postalCode,
                    onValueChange = { edit(draft.copy(address = address.copy(postalCode = it))) },
                    label = str(S.desktop_postal_zip_code),
                    placeholder = str(S.ah_postcode_hint),
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                CaptionedField(str(S.country), Modifier.weight(1f)) {
                    val countries = state.sales.countries
                    SearchPicker(
                        value = address.country.takeIf { it.isNotBlank() },
                        options = (countries.map { it.name } + address.country).filter { it.isNotBlank() }.distinct(),
                        label = { it },
                        searchText = { name -> "$name ${countries.firstOrNull { it.name == name }?.code.orEmpty()}" },
                        onSelect = { edit(draft.copy(address = address.copy(country = it))) },
                        placeholder = str(S.desktop_dm_select_country),
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            FieldRow {
                ZillitDateField(
                    value = draft.invoiceDate,
                    onValueChange = { edit(draft.copy(invoiceDate = it)) },
                    label = "${str(S.desktop_invoice_date_title)} *",
                    enabled = enabled,
                    errorText = draft.errors[SalesField.InvoiceDate],
                    modifier = Modifier.weight(1f),
                )
                ZillitDateField(
                    value = draft.dueDate,
                    onValueChange = { edit(draft.copy(dueDate = it)) },
                    label = "${str(S.ah_run_detail_col_due)} *",
                    enabled = enabled,
                    errorText = str(S.desktop_that_is_not_a_date).takeIf { draft.dateIsWrong },
                    modifier = Modifier.weight(1f),
                )
                CaptionedField(str(S.asset_currency), Modifier.weight(1f)) {
                    CurrencyPicker(
                        state = state,
                        value = draft.currency.ifBlank { state.projectCurrency },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { edit(draft.copy(currency = it)) }
                }
            }
        }
    }
}

/** A control under its label, weighted into its row. */
@Composable
internal fun CaptionedField(label: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(text = label, style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
        content()
    }
}

// -- the preview -----------------------------------------------------------------------

/**
 * One sales invoice, laid out as the document — the web's preview modal
 * (`SalesPage.jsx:1006-1520`): the production's header and the INVOICE block,
 * Billed To, the lines with the Gross Total, the payment footer, and who
 * raised and last changed it. History on the left; Edit, Delete and Mark Sent
 * for a draft; View PDF always.
 */
@Composable
internal fun SalesPreviewDialog(state: InvoicesUiState, invoice: SalesInvoice, nowMs: Long, onEvent: (InvoicesEvent) -> Unit) {
    val shown = invoice.shownStatus(nowMs)
    ZillitDialogShell(
        title = invoice.reference.ifBlank { str(S.desktop_inv_sales_invoice) },
        onDismiss = { onEvent(SalesEvent.ClosePreview) },
        visible = true,
        width = PREVIEW_WIDTH,
        icon = ZillitIcons.File,
        actions = { SalesPreviewActions(state, invoice, nowMs, onEvent) },
    ) {
        ZillitStatusPill(label = shown.label, tone = shown.tone())
        DocumentHeader(state, invoice)
        BilledTo(invoice)
        DocumentLines(state, invoice)
        PaymentFooter(state, invoice)
        AuditRow(state, invoice)
    }
}

@Composable
private fun DocumentHeader(state: InvoicesUiState, invoice: SalesInvoice) {
    val project = state.sales.project
    val colors = ZillitTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(
                text = project.projectName,
                style = ZillitTheme.typography.titleSmall,
                color = colors.info,
            )
            ZillitText(text = project.companyName, style = ZillitTheme.typography.label)
            if (project.companyAddress.isNotBlank()) MutedLine(project.companyAddress)
            MutedLine("${project.companyPhone} · ${project.companyEmail}")
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(
                text = str(S.desktop_inv_invoice_caps),
                style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = colors.info,
            )
            MetaLine(str(S.desktop_inv_invoice_no), invoice.reference)
            MetaLine(str(S.date), InvoiceFormat.date(invoice.invoiceDateMs))
            MetaLine(str(S.desktop_due), InvoiceFormat.date(invoice.dueDateMs))
            MetaLine(str(S.desktop_terms), invoice.termsLabel)
        }
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(text = "$label:", style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
        ZillitText(text = value, style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun BilledTo(invoice: SalesInvoice) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = str(S.desktop_inv_billed_to).uppercase(),
            style = ZillitTheme.typography.columnHeader,
            color = colors.info,
        )
        ZillitText(text = invoice.clientName, style = ZillitTheme.typography.label)
        invoice.addressLine.takeIf { it.isNotBlank() }?.let { MutedLine(it) }
    }
}

/**
 * `# / Description / Qty / Unit Price / Tax / Amount` — a split row reads
 * "↳ SPLIT" with no quantity or price, and an invoice with no lines shows one
 * "Sales invoice" line for its gross. Each amount is the line's net plus its
 * stored tax; the Gross Total is the invoice's own.
 */
@Composable
private fun DocumentLines(state: InvoicesUiState, invoice: SalesInvoice) {
    val currency = invoice.currency.ifBlank { state.projectCurrency }
    val money = { value: Double -> InvoiceFormat.money(value, currency) }
    val lines = invoice.lineItems.ifEmpty {
        listOf(
            CodedLine(
                id = "",
                description = str(S.desktop_inv_sales_invoice_line),
                amount = invoice.grossAmount,
                unitPrice = invoice.grossAmount,
            ),
        )
    }
    val colors = ZillitTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium).border(1.dp, colors.border, ZillitTheme.shapes.medium),
    ) {
        DocumentRow(
            listOf("#", str(S.description), str(S.ah_lbl_qty), str(S.ah_lbl_unit_price), str(S.ah_lbl_vat), str(S.amount)),
            header = true,
        )
        var position = 0
        lines.forEach { line ->
            val tax = invoice.lineTaxAmounts[line.id] ?: 0.0
            val number = if (line.isSplit) "" else (++position).toString()
            DocumentRow(
                listOf(
                    number,
                    if (line.isSplit) "↳ ${str(S.desktop_card_split_caps)} ${line.description.ifBlank { "—" }}" else line.description.ifBlank { "—" },
                    if (line.isSplit) "—" else InvoiceFormat.plain(line.quantity).removeSuffix(".00"),
                    if (line.isSplit) "—" else money(line.unitPrice),
                    "${InvoiceFormat.plain(line.taxRate ?: 0.0).removeSuffix(".00")}%",
                    money(line.amount + tax),
                ),
                muted = line.isSplit,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
            horizontalArrangement = Arrangement.End,
        ) {
            ZillitText(
                text = "${str(S.ah_lbl_gross_total)}   ${money(invoice.grossAmount)}",
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                color = colors.accentText,
            )
        }
    }
}

@Composable
private fun DocumentRow(cells: List<String>, header: Boolean = false, muted: Boolean = false) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (header) colors.info else colors.surface)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        cells.forEachIndexed { index, text ->
            ZillitText(
                text = if (header) text.uppercase() else text,
                style = when {
                    header -> ZillitTheme.typography.columnHeader
                    muted -> ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic)
                    else -> ZillitTheme.typography.bodySmall
                },
                color = when {
                    header -> colors.textOnAccent
                    muted -> colors.textMuted
                    else -> colors.textPrimary
                },
                maxLines = 1,
                textAlign = if (index >= 2) TextAlign.End else TextAlign.Start,
                modifier = when (index) {
                    0 -> Modifier.width(NUMBER_WIDTH)
                    1 -> Modifier.weight(DESCRIPTION_SHARE)
                    else -> Modifier.weight(1f)
                },
            )
        }
    }
}

/** "Payment Details:" and the terms sentence — the web's footer, placeholder bank details and all. */
@Composable
private fun PaymentFooter(state: InvoicesUiState, invoice: SalesInvoice) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        MutedLine(str(S.desktop_inv_payment_details_line, state.sales.project.companyName))
        MutedLine(str(S.desktop_inv_payment_terms_line, invoice.termsLabel))
    }
}

/** Created By and, once changed, Updated By — name, designation and `DD Mon YYYY | hh:mm AM`. */
@Composable
private fun AuditRow(state: InvoicesUiState, invoice: SalesInvoice) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        AuditCell(str(S.ah_lbl_created_by), state, invoice.createdBy, invoice.createdAtMs, Modifier.weight(1f))
        if (invoice.updatedBy.isNotBlank()) {
            AuditCell(str(S.ah_lbl_updated_by), state, invoice.updatedBy, invoice.updatedAtMs, Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun AuditCell(label: String, state: InvoicesUiState, userId: String, atMs: Long?, modifier: Modifier) {
    val person = state.sales.people[userId]
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FactLabel(label)
        ZillitText(
            text = person?.name ?: state.userNames[userId] ?: "—",
            style = ZillitTheme.typography.label,
        )
        person?.role?.takeIf { it.isNotBlank() }?.let { MutedLine(it) }
        if (atMs != null) MutedLine(SalesInvoices.stamp(atMs))
    }
}

@Composable
private fun RowScope.SalesPreviewActions(
    state: InvoicesUiState,
    invoice: SalesInvoice,
    nowMs: Long,
    onEvent: (InvoicesEvent) -> Unit,
) {
    ZillitButton(
        text = str(S.history),
        onClick = { onEvent(SalesEvent.ShowHistory) },
        leadingIcon = ZillitIcons.Clock,
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
    )
    Spacer(Modifier.weight(1f))
    if (invoice.status == SalesInvoiceStatus.Draft && state.viewer.mayPost) {
        ZillitButton(
            text = str(S.edit),
            onClick = { onEvent(SalesEvent.Edit) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
        )
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = str(S.desktop_inv_delete_sales_invoice),
            onClick = { onEvent(SalesEvent.RequestDelete) },
        )
        ZillitButton(
            text = if (state.sales.markingSent) str(S.desktop_payroll_marking) else str(S.desktop_inv_mark_sent),
            onClick = { onEvent(SalesEvent.MarkSent) },
            size = ButtonSize.Small,
            enabled = !state.sales.markingSent,
            loading = state.sales.markingSent,
        )
    }
    if (invoice.isOverdue(nowMs)) ZillitStatusPill(label = SalesInvoiceStatus.Overdue.label, tone = StatusTone.Rejected)
    if (invoice.status == SalesInvoiceStatus.Paid) {
        ZillitStatusPill(label = SalesInvoiceStatus.Paid.label, tone = StatusTone.Done)
    }
    val generating = state.sales.pdf?.loading == true
    ZillitButton(
        text = if (generating) str(S.drive_generating) else str(S.ah_view_pdf),
        onClick = { onEvent(SalesEvent.ViewPdf) },
        leadingIcon = ZillitIcons.Download,
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        loading = generating,
    )
}

// -- the PDF, history and delete ------------------------------------------------------------

/** "Sales Invoice — {ref}": the server's PDF, rendered in-app, with Download beside it. */
@Composable
internal fun SalesPdfDialog(pdf: SalesPdf, onEvent: (InvoicesEvent) -> Unit) {
    val pages = remember(pdf.bytes) { pdf.bytes?.bytes?.let(::decodePreviewPages).orEmpty() }
    ZillitDialogShell(
        title = str(S.desktop_inv_sales_invoice_pdf_title, pdf.reference),
        onDismiss = { onEvent(SalesEvent.ClosePdf) },
        visible = true,
        width = PDF_WIDTH,
        scrollable = false,
        icon = ZillitIcons.File,
        actions = {
            if (pdf.bytes != null) {
                ZillitButton(
                    text = str(S.download),
                    onClick = { onEvent(SalesEvent.DownloadPdf) },
                    leadingIcon = ZillitIcons.Download,
                )
            }
            ZillitButton(text = str(S.close), onClick = { onEvent(SalesEvent.ClosePdf) }, variant = ButtonVariant.Tertiary)
        },
    ) {
        Box(Modifier.fillMaxWidth().height(PDF_HEIGHT), contentAlignment = Alignment.Center) {
            when {
                pdf.loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitSpinner()
                    MutedLine(str(S.av_pdf_generating))
                }
                pages.isNotEmpty() -> Box(Modifier.fillMaxSize()) { PreviewPages(pages, pdf.reference) }
                else -> MutedLine(str(S.desktop_inv_could_not_load_preview))
            }
        }
    }
}

@Composable
internal fun SalesHistorySheet(state: InvoicesUiState, onEvent: (InvoicesEvent) -> Unit) {
    val history = state.sales.history ?: return
    val subtitle = history.invoice.reference.ifBlank { history.invoice.id.take(HISTORY_ID_CHARS) }
    HistorySheet(
        invoiceNumber = subtitle,
        rows = history.rows,
        loading = history.loading,
        nameOf = { id -> state.userNames[id] ?: id.ifBlank { str(S.desktop_unknown) } },
        onClose = { onEvent(SalesEvent.HideHistory) },
        subtitle = subtitle,
    )
}

/** "Delete Sales Invoice" — the web's ConfirmModal over the preview. */
@Composable
internal fun SalesDeleteDialog(invoice: SalesInvoice, onEvent: (InvoicesEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_inv_delete_sales_invoice),
        onDismiss = { onEvent(InvoicesEvent.CancelDeleteSales) },
        visible = true,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(InvoicesEvent.CancelDeleteSales) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.delete),
                onClick = { onEvent(InvoicesEvent.ConfirmDeleteSales) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(
            text = str(
                S.desktop_inv_delete_credit_confirm,
                invoice.reference.ifBlank { str(S.desktop_inv_this_invoice) },
            ),
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}

private val PREVIEW_WIDTH = 720.dp
private val PDF_WIDTH = 820.dp
private val PDF_HEIGHT = 620.dp
private val NUMBER_WIDTH = 24.dp
private val ADDRESS_ICON = 12.dp
private const val DESCRIPTION_SHARE = 2.6f
private const val HISTORY_ID_CHARS = 8
