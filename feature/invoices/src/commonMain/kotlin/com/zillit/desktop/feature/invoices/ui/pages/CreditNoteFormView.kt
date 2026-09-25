// The credit note / dispute form: the web's full-page form view.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.component.ZillitCalcField
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.feature.invoices.domain.Vendor
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.CreditAttachment
import com.zillit.desktop.feature.invoices.domain.CreditNotes
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.ui.CreditEvent
import com.zillit.desktop.feature.invoices.ui.CreditField
import com.zillit.desktop.feature.invoices.ui.CreditNoteForm
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

/**
 * New or Edit Credit Note / Dispute — the web's form view, in the list's
 * place: the top bar (Cancel and Create / Update, or the lock), the details
 * card, the lines for a credit note, then the attachments. A note dated in a
 * closed period opens here read-only.
 */
@Suppress("LongMethod") // The top bar and three cards of one form view.
@Composable
internal fun ColumnScope.CreditNoteFormView(
    state: InvoicesUiState,
    form: CreditNoteForm,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val title = str(
        when {
            form.editingId == null && form.isDispute -> S.desktop_inv_new_dispute
            form.editingId == null -> S.desktop_inv_new_credit_note
            form.isDispute -> S.desktop_inv_edit_dispute
            else -> S.desktop_inv_edit_credit_note
        },
    )
    FormTopBar(
        section = str(S.desktop_credit_notes),
        title = title,
        onBack = { onEvent(CreditEvent.CloseForm) },
    ) {
        ZillitButton(
            text = str(S.cancel),
            onClick = { onEvent(CreditEvent.CloseForm) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = !form.saving,
        )
        if (form.locked) {
            LockBanner(str(S.desktop_inv_locked_period_credit, state.periodLock.lockedThrough))
        } else {
            ZillitButton(
                text = str(saveLabel(form)),
                onClick = { onEvent(CreditEvent.Save) },
                size = ButtonSize.Small,
                loading = form.saving,
            )
        }
    }
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        DetailsCard(state, form, onEvent)
        if (!form.isDispute) {
            LineDraftCard(
                state = state,
                draft = form.lines,
                currency = form.currency.ifBlank { state.projectCurrency },
                frozen = form.frozen,
                error = form.errors[CreditField.Lines],
            ) { onEvent(CreditEvent.Lines(it)) }
        }
        ZillitSectionCard(title = str(S.ah_attachments_title), icon = ZillitIcons.Paperclip) {
            AttachmentList(form.attachments, removable = !form.frozen, onEvent = onEvent)
            if (!form.frozen) {
                ZillitButton(
                    text = str(S.desktop_add_attachment),
                    onClick = { onEvent(CreditEvent.AddAttachment) },
                    leadingIcon = ZillitIcons.Add,
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
    }
}

private fun saveLabel(form: CreditNoteForm): String = when {
    form.editingId == null && form.isDispute -> S.desktop_inv_create_dispute
    form.editingId == null -> S.desktop_inv_create_credit_note
    form.isDispute -> S.desktop_inv_update_dispute
    else -> S.desktop_inv_update_credit_note
}

/** Against Invoice, Vendor and Reason; Effective Date, Currency and (for a dispute) Amount; Notes. */
@Suppress("LongMethod") // The web's two field rows and Notes, one call per field.
@Composable
private fun DetailsCard(state: InvoicesUiState, form: CreditNoteForm, onEvent: (InvoicesEvent) -> Unit) {
    val enabled = !form.frozen
    val edit = { next: CreditNoteForm -> onEvent(CreditEvent.Change(next)) }
    ZillitSectionCard {
        // The card's own rhythm is tight; the web's form rows breathe.
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            FieldRow {
                InvoicePickerField(state, form, enabled, onEvent)
                VendorPickerField(state, form, enabled) { edit(form.copy(vendorId = it)) }
                ZillitTextField(
                    value = form.reason,
                    onValueChange = { edit(form.copy(reason = it)) },
                    label = if (form.isDispute) "${str(S.reason)} *" else str(S.reason),
                    placeholder = str(
                        if (form.isDispute) S.desktop_inv_dispute_reason_hint else S.desktop_inv_credit_reason_hint,
                    ),
                    errorText = form.errors[CreditField.Reason],
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
            }
            FieldRow {
                ZillitDateField(
                    value = form.effectiveDate,
                    onValueChange = { edit(form.copy(effectiveDate = it)) },
                    label = "${str(S.ah_lbl_eff_date)} *",
                    errorText = form.errors[CreditField.EffectiveDate],
                    enabled = enabled,
                    // The web's date input `min` — the day after the cost report's lock.
                    minDate = dayAfterLock(state),
                    modifier = Modifier.weight(1f),
                )
                CaptionedField(str(S.asset_currency), Modifier.weight(1f)) {
                    CurrencyPicker(
                        state = state,
                        value = form.currency.ifBlank { state.projectCurrency },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { edit(form.copy(currency = it)) }
                }
                if (form.isDispute) {
                    // The web's `CalcInput`: "2*50" resolves as the field is left.
                    ZillitCalcField(
                        value = form.disputeAmount,
                        onCommit = { edit(form.copy(disputeAmount = it)) },
                        label = str(S.amount),
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            ZillitTextField(
                value = form.notes,
                onValueChange = { edit(form.copy(notes = it)) },
                label = str(S.notes),
                placeholder = str(S.hint_notes),
                singleLine = false,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Against Invoice Ref — typed to search, picked from the list under it. The
 * list opens as the box takes focus, even before anything is typed, shows up
 * to thirty, and shuts on a pick or a click elsewhere (`CreditsPage.jsx:520-550`).
 * Only a numbered invoice that is not ready to pay is offered: the number is
 * the link. Picking one brings its vendor and currency.
 */
@Composable
private fun RowScope.InvoicePickerField(
    state: InvoicesUiState,
    form: CreditNoteForm,
    enabled: Boolean,
    onEvent: (InvoicesEvent) -> Unit,
) {
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    Box(Modifier.weight(1f).onSizeChanged { fieldSize = it }) {
        ZillitTextField(
            value = form.invoiceQuery,
            onValueChange = {
                onEvent(CreditEvent.Change(form.copy(invoiceQuery = it, invoiceRef = "", invoiceId = "")))
            },
            label = str(S.desktop_inv_against_invoice_ref),
            placeholder = str(S.desktop_inv_search_invoice_number),
            errorText = form.errors[CreditField.InvoiceRef],
            enabled = enabled,
            trailingContent = if (form.invoiceRef.isNotBlank() && enabled) {
                {
                    ZillitIconButton(
                        icon = ZillitIcons.Close,
                        contentDescription = str(S.clear),
                        onClick = { onEvent(CreditEvent.ClearInvoice) },
                    )
                }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                if (focus.isFocused && enabled && !form.pickerOpen) onEvent(CreditEvent.OpenInvoicePicker)
            },
        )
        val matches = CreditNotes.creditable(state.credit.invoices, form.invoiceQuery.takeIf { form.invoiceRef.isBlank() }.orEmpty()) {
            state.vendorName(it)
        }.take(SUGGESTIONS_SHOWN)
        if (enabled && form.pickerOpen && matches.isNotEmpty()) {
            Popup(
                offset = IntOffset(0, fieldSize.height),
                onDismissRequest = { onEvent(CreditEvent.CloseInvoicePicker) },
                properties = PopupProperties(focusable = false),
            ) {
                Column(
                    Modifier
                        .width(with(density) { fieldSize.width.toDp() })
                        .heightIn(max = SUGGESTIONS_MAX)
                        .shadow(SUGGESTIONS_ELEVATION, ZillitTheme.shapes.medium)
                        .clip(ZillitTheme.shapes.medium)
                        .background(ZillitTheme.colors.surfaceRaised)
                        .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.medium)
                        .verticalScroll(rememberScrollState()),
                ) {
                    matches.forEach { invoice ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { onEvent(CreditEvent.PickInvoice(invoice)) }
                                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
                        ) {
                            ZillitText(text = invoice.invoiceNumber, style = ZillitTheme.typography.label)
                            MutedLine(
                                "${state.vendorName(invoice)} · " +
                                    InvoiceFormat.money(
                                        invoice.grossAmount,
                                        invoice.currency.ifBlank { state.projectCurrency },
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Vendor * — searchable by name, email and contact, each option with that
 * second line; "No vendors…" when the list is empty; in the order the
 * directory lists them (`CreditsPage.jsx:556-574`).
 */
@Composable
private fun RowScope.VendorPickerField(
    state: InvoicesUiState,
    form: CreditNoteForm,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val vendors = state.vendors.values.toList()
    val error = form.errors[CreditField.Vendor]
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = "${str(S.ah_lbl_vendor)} *",
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textSecondary,
        )
        SearchPicker(
            value = state.vendors[form.vendorId],
            options = vendors,
            label = { it.name },
            searchText = { "${it.name} ${vendorSubline(it)}" },
            subline = ::vendorSubline,
            onSelect = { onSelect(it.id) },
            placeholder = if (vendors.isEmpty()) str(S.desktop_inv_no_vendors_ellipsis) else str(S.desktop_inv_search_vendor_hint),
            enabled = enabled,
            error = error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.danger)
        }
    }
}

/** The web's `vendorSubline`: email · contact person. */
private fun vendorSubline(vendor: Vendor): String =
    listOf(vendor.email, vendor.contactPerson).filter { it.isNotBlank() }.joinToString(" · ")

/** The web's `PeriodLockBanner`, in the Save button's place. */
@Composable
private fun LockBanner(text: String) {
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.dangerSoft)
            .border(1.dp, ZillitTheme.colors.danger.copy(alpha = LOCK_BORDER_ALPHA), ZillitTheme.shapes.medium)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(icon = ZillitIcons.Lock, tint = ZillitTheme.colors.danger, size = LOCK_ICON)
        ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger)
    }
}

/** A note's files: name and size, View for a stored one, Remove while the form is open. */
@Composable
internal fun AttachmentList(attachments: List<CreditAttachment>, removable: Boolean, onEvent: (InvoicesEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        if (!removable) FactLabel(str(S.ah_attachments_title))
        attachments.forEachIndexed { index, attachment ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = attachment.name,
                    style = ZillitTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                attachment.sizeBytes?.let { MutedLine(sizeLabel(it)) }
                if (attachment.stored != null) {
                    ZillitButton(
                        text = str(S.view),
                        onClick = { onEvent(CreditEvent.OpenAttachment(attachment)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                }
                if (removable) {
                    ZillitButton(
                        text = str(S.remove),
                        onClick = { onEvent(CreditEvent.RemoveAttachment(index)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                }
            }
        }
    }
}

/** The web's `(size / 1024).toFixed(1) KB`. */
private fun sizeLabel(bytes: Long): String {
    val kb = bytes / BYTES_PER_KB
    val tenths = kotlin.math.round(kb * TENTHS) / TENTHS
    return "$tenths KB"
}

private const val SUGGESTIONS_SHOWN = 30
private val SUGGESTIONS_MAX = 208.dp
private val SUGGESTIONS_ELEVATION = 8.dp
private val LOCK_ICON = 12.dp
private const val LOCK_BORDER_ALPHA = 0.3f
private const val BYTES_PER_KB = 1024.0
private const val TENTHS = 10.0

/** The first day a note may be dated — the day after the lock; null when nothing is locked. */
private fun dayAfterLock(state: InvoicesUiState): LocalDate? =
    state.periodLock.lockedThrough.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDate.parse(it.take(ISO_DATE)).plus(1, DateTimeUnit.DAY) }.getOrNull() }

private const val ISO_DATE = 10
