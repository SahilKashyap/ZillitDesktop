package com.zillit.desktop.feature.purchaseorder.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.domain.PoAddress
import com.zillit.desktop.feature.purchaseorder.domain.PoFormFields
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.RENTAL_EXPENDITURE_TYPE
import com.zillit.desktop.feature.purchaseorder.domain.isoDayToUtcMidnight
import com.zillit.desktop.feature.purchaseorder.domain.splitCadence
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoFormMode
import com.zillit.desktop.feature.purchaseorder.ui.PoFormState
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState

/**
 * Raising or editing an order — the web's `POForm`, which takes over the page.
 *
 * Three sections in the web's order (PO Details, Delivery Address, Line Items)
 * plus its sticky bar of actions, and the same five labels on them. Fields the
 * production has switched off in Forms Configuration are not drawn; fields it
 * has marked required carry an asterisk.
 */
@Composable
internal fun PoFormPage(state: PoUiState, form: PoFormState, onEvent: (PoEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        FormTopBar(state, form, onEvent)
        ZillitDivider()
        ZillitScrollColumn(
            // The remainder under the sticky bar, not the whole window.
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(ZillitTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            if (form.problems.isNotEmpty()) {
                ZillitSectionCard(title = str(S.desktop_po_please_fix_errors), icon = ZillitIcons.Warning) {
                    form.problems.forEach { problem ->
                        ZillitText(
                            text = "• $problem",
                            style = ZillitTheme.typography.bodyMedium,
                            color = ZillitTheme.colors.danger,
                        )
                    }
                }
            }
            if (form.isTemplate) TemplateNameCard(form, onEvent)
            PoDetailsSection(state, form, onEvent)
            if (!form.isTemplate) DeliverySection(state, form, onEvent)
            LineItemsSection(state, form, onEvent)
            if (!form.isTemplate) AttachmentsSection(state.busy, form, onEvent)
            TotalsCard(form)
        }
    }
}

/** The sticky bar: where you came from, what this is, and every save. */
@Composable
private fun FormTopBar(state: PoUiState, form: PoFormState, onEvent: (PoEvent) -> Unit) {
    var templateName by remember(form.templateId) { mutableStateOf(form.templateName) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitButton(
            text = form.backLabel,
            onClick = { onEvent(PoEvent.CloseForm) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.ArrowLeft,
        )
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = form.title, style = ZillitTheme.typography.titleMedium)
            ZillitText(
                // The web's two subtitles, kept: the form is the same, but who
                // is filling it in changes what it means.
                text = if (state.viewer.isAccountant) {
                    str(S.desktop_po_accountant_entry_banner)
                } else {
                    str(S.desktop_po_department_request_banner)
                },
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        if (!form.isTemplate) {
            ZillitButton(
                text = form.saveDraftLabel,
                onClick = { onEvent(PoEvent.SaveDraft) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !form.saving,
            )
            ZillitButton(
                text = form.saveTemplateLabel,
                onClick = { onEvent(PoEvent.NameTemplate) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = !form.saving,
            )
        }
        ZillitButton(
            text = if (form.isTemplate) form.saveTemplateLabel else form.submitLabel,
            onClick = {
                if (form.isTemplate) onEvent(PoEvent.SaveAsTemplate(templateName)) else onEvent(PoEvent.SubmitForm)
            },
            size = ButtonSize.Small,
            loading = form.saving,
            enabled = !form.saving,
        )
    }
}

@Composable
private fun TemplateNameCard(form: PoFormState, onEvent: (PoEvent) -> Unit) {
    ZillitSectionCard(title = str(S.txt_template), icon = ZillitIcons.Grid) {
        ZillitTextField(
            value = form.templateName,
            onValueChange = { onEvent(PoEvent.EditForm(form.copy(templateName = it))) },
            label = str(S.nda_template_name),
            placeholder = str(S.desktop_po_template_name_example),
        )
    }
}

/**
 * PO Details — the web's `po_details` section.
 *
 * Every field is drawn through the production's own form template, so a
 * production that switched Episode off does not see it, and one that made the
 * nominal code mandatory sees the asterisk. The labels are the template's where
 * it renames them and the web's defaults otherwise.
 */
@Composable
private fun PoDetailsSection(state: PoUiState, form: PoFormState, onEvent: (PoEvent) -> Unit) {
    ZillitSectionCard(title = str(S.desktop_po_details), icon = ZillitIcons.File) {
        VendorAndDescription(state, form, onEvent)
        DepartmentAndCompany(state, form, onEvent)
        CodingRow(state, form, onEvent)
        DateRow(state, form, onEvent)
        NotesRow(state, form, onEvent)
        CustomFields(state, form, onEvent)
    }
}

/** Whether a field is on this production's form, and what it calls it. */
private fun PoUiState.showsField(field: String) = formLayout.shows(PoFormFields.DETAILS, field)

private fun PoUiState.fieldLabel(field: String, fallback: String): String =
    formLayout.label(PoFormFields.DETAILS, field, fallback) +
        if (formLayout.isRequired(PoFormFields.DETAILS, field)) " *" else ""

@Composable
private fun ColumnScope.VendorAndDescription(state: PoUiState, form: PoFormState, onEvent: (PoEvent) -> Unit) {
    val shows = { field: String -> state.showsField(field) }
    val label = { field: String, fallback: String -> state.fieldLabel(field, fallback) }
    run {
        if (shows(PoFormFields.VENDOR)) {
            LabelledField(label(PoFormFields.VENDOR, str(S.desktop_po_vendor_supplier))) {
                ZillitSelect(
                    value = form.vendorId,
                    options = listOf(null) + state.vendors.map { it.id },
                    onSelect = { id ->
                        val vendor = state.vendors.firstOrNull { it.id == id }
                        onEvent(
                            PoEvent.EditForm(
                                form.copy(
                                    vendorId = id,
                                    vendorName = vendor?.name.orEmpty(),
                                    // The vendor's own currency and coding
                                    // lead, as they do on both phones: most
                                    // orders with a vendor are in that
                                    // vendor's currency, and a wrong default
                                    // here is a mispriced order.
                                    currency = vendor?.currency ?: form.currency,
                                    nominalCode = vendor?.defaultNominalCode ?: form.nominalCode,
                                ),
                            ),
                        )
                    },
                    label = { id ->
                        id?.let { key -> state.vendors.firstOrNull { it.id == key }?.name ?: key }
                            ?: str(S.desktop_po_vendor_search_placeholder)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (shows(PoFormFields.DESCRIPTION)) {
            ZillitTextField(
                value = form.description,
                onValueChange = { onEvent(PoEvent.EditForm(form.copy(description = it))) },
                label = label(PoFormFields.DESCRIPTION, str(S.description)),
                placeholder = str(S.desktop_po_description_example),
            )
        }
    }
}

@Composable
private fun ColumnScope.DepartmentAndCompany(state: PoUiState, form: PoFormState, onEvent: (PoEvent) -> Unit) {
    val shows = { field: String -> state.showsField(field) }
    val label = { field: String, fallback: String -> state.fieldLabel(field, fallback) }
    run {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            if (shows(PoFormFields.DEPARTMENT)) {
                LabelledField(label(PoFormFields.DEPARTMENT, str(S.department)), Modifier.weight(1f)) {
                    ZillitSelect(
                        value = form.departmentId,
                        options = listOf(null) + state.departments.map { it.id },
                        onSelect = { onEvent(PoEvent.EditForm(form.copy(departmentId = it))) },
                        label = { id -> id?.let { state.departmentName(it) } ?: str(S.select_department) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (shows(PoFormFields.COMPANY) && state.companies.isNotEmpty()) {
                LabelledField(
                    label(PoFormFields.COMPANY, str(S.company)) + " " + str(S.desktop_optional_tail),
                    Modifier.weight(1f),
                ) {
                    ZillitSelect(
                        value = form.companyId,
                        options = listOf(null) + state.companies.map { it.id },
                        onSelect = { onEvent(PoEvent.EditForm(form.copy(companyId = it))) },
                        label = { id ->
                            id?.let { key -> state.companies.firstOrNull { it.id == key }?.name ?: key }
                                ?: str(S.dm_nda_search_companies)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.CodingRow(state: PoUiState, form: PoFormState, onEvent: (PoEvent) -> Unit) {
    val shows = { field: String -> state.showsField(field) }
    val label = { field: String, fallback: String -> state.fieldLabel(field, fallback) }
    run {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            if (shows(PoFormFields.ACCOUNT_CODE)) {
                ZillitTextField(
                    value = form.nominalCode,
                    onValueChange = { onEvent(PoEvent.EditForm(form.copy(nominalCode = it))) },
                    label = label(PoFormFields.ACCOUNT_CODE, str(S.ah_lbl_nominal_code)),
                    placeholder = str(S.desktop_po_search_or_enter_code),
                    modifier = Modifier.weight(1f),
                )
            }
            if (shows(PoFormFields.CURRENCY)) {
                LabelledField(label(PoFormFields.CURRENCY, str(S.ah_lbl_currency)), Modifier.weight(1f)) {
                    ZillitSelect(
                        value = form.currency ?: state.currencies.firstOrNull() ?: "GBP",
                        options = state.currencies.ifEmpty { listOf("GBP") },
                        onSelect = { onEvent(PoEvent.EditForm(form.copy(currency = it))) },
                        label = { it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (shows(PoFormFields.EPISODE)) {
                ZillitTextField(
                    value = form.episode,
                    onValueChange = { onEvent(PoEvent.EditForm(form.copy(episode = it))) },
                    label = label(PoFormFields.EPISODE, str(S.episode)),
                    placeholder = str(S.desktop_po_episode_example),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.DateRow(state: PoUiState, form: PoFormState, onEvent: (PoEvent) -> Unit) {
    val shows = { field: String -> state.showsField(field) }
    val label = { field: String, fallback: String -> state.fieldLabel(field, fallback) }
    run {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            if (shows(PoFormFields.EFFECTIVE_DATE)) {
                ZillitDateField(
                    value = EpochDate.isoDate(form.effectiveDate),
                    onValueChange = { iso ->
                        onEvent(PoEvent.EditForm(form.copy(effectiveDate = iso.isoDayToUtcMidnight())))
                    },
                    label = label(PoFormFields.EFFECTIVE_DATE, str(S.ah_lbl_eff_date)),
                    modifier = Modifier.weight(1f),
                )
            }
            if (shows(PoFormFields.DELIVERY_DATE)) {
                ZillitDateField(
                    value = EpochDate.isoDate(form.deliveryDate),
                    onValueChange = { iso ->
                        onEvent(PoEvent.EditForm(form.copy(deliveryDate = iso.isoDayToUtcMidnight())))
                    },
                    label = label(PoFormFields.DELIVERY_DATE, str(S.delivery_date)),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.NotesRow(state: PoUiState, form: PoFormState, onEvent: (PoEvent) -> Unit) {
    if (!state.showsField(PoFormFields.NOTES)) return
    ZillitTextField(
        value = form.notes,
        onValueChange = { onEvent(PoEvent.EditForm(form.copy(notes = it))) },
        label = state.fieldLabel(PoFormFields.NOTES, str(S.notes)) + " " + str(S.desktop_optional_tail),
        placeholder = str(S.desktop_po_internal_notes),
        singleLine = false,
    )
}

/** The extra fields this production added to the PO details section. */
@Composable
private fun CustomFields(state: PoUiState, form: PoFormState, onEvent: (PoEvent) -> Unit) {
    val custom = state.formLayout.custom(PoFormFields.DETAILS)
    if (custom.isEmpty()) return
    custom.forEach { field ->
        ZillitTextField(
            value = form.customFields[field.label].orEmpty(),
            onValueChange = { value ->
                onEvent(PoEvent.EditForm(form.copy(customFields = form.customFields + (field.label to value))))
            },
            label = field.name + if (field.required) " *" else "",
        )
    }
}

/**
 * The Delivery Address section, with the address book in front of it.
 *
 * Picking a saved address fills the block and links the order to it; typing
 * over the block unlinks it, because an order that says it used saved address
 * #3 and then carries a different address is a record nobody can reconcile.
 */
@Suppress("LongMethod") // Nine address fields; a form is a list of fields.
@Composable
private fun DeliverySection(state: PoUiState, form: PoFormState, onEvent: (PoEvent) -> Unit) {
    val layout = state.formLayout
    if (!layout.shows(PoFormFields.DELIVERY, PoFormFields.DELIVERY_LINE1) && state.addresses.isEmpty()) return
    val address = form.deliveryAddress
    val set = { next: PoAddress ->
        // Editing the block by hand breaks the link to the saved row.
        onEvent(PoEvent.EditForm(form.copy(deliveryAddress = next, deliveryAddressId = null)))
    }
    ZillitSectionCard(title = str(S.delivery_address), icon = ZillitIcons.Home) {
        if (state.addresses.isNotEmpty()) {
            Row(verticalAlignment = Alignment.Bottom) {
                LabelledField(str(S.desktop_po_saved_address), Modifier.weight(1f)) {
                    ZillitSelect(
                        value = form.deliveryAddressId,
                        options = listOf(null) + state.addresses.map { it.id },
                        onSelect = { onEvent(PoEvent.PickSavedAddress(it)) },
                        label = { id ->
                            id?.let { key -> state.addresses.firstOrNull { it.id == key }?.label ?: key }
                                ?: str(S.desktop_po_pick_a_saved_address)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (form.deliveryAddressId != null) {
                    Spacer(modifier = Modifier.width(ZillitTheme.spacing.sm))
                    ZillitButton(
                        text = str(S.desktop_po_use_manual_address),
                        onClick = { onEvent(PoEvent.PickSavedAddress(null)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                }
            }
            ZillitDivider()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitTextField(
                value = address.name,
                onValueChange = { set(address.copy(name = it)) },
                label = str(S.dd_recipient_name),
                placeholder = str(S.dd_recipient_name),
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = address.email,
                onValueChange = { set(address.copy(email = it)) },
                label = str(S.email),
                placeholder = "email@example.com",
                keyboardType = KeyboardType.Email,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitTextField(
                value = address.phoneCode,
                onValueChange = { set(address.copy(phoneCode = it)) },
                label = str(S.code),
                placeholder = "+44",
                modifier = Modifier.width(CODE_WIDTH),
            )
            ZillitTextField(
                value = address.phone,
                onValueChange = { set(address.copy(phone = it)) },
                label = str(S.phone),
                placeholder = "1753 651700",
                keyboardType = KeyboardType.Phone,
                modifier = Modifier.weight(1f),
            )
        }
        ZillitTextField(
            value = address.line1,
            onValueChange = { set(address.copy(line1 = it)) },
            label = str(S.address_line_1),
            placeholder = str(S.ah_street_hint),
        )
        ZillitTextField(
            value = address.line2,
            onValueChange = { set(address.copy(line2 = it)) },
            label = str(S.address_line_2),
            placeholder = str(S.ah_suite_hint),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitTextField(
                value = address.city,
                onValueChange = { set(address.copy(city = it)) },
                label = str(S.city),
                placeholder = str(S.city),
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = address.state,
                onValueChange = { set(address.copy(state = it)) },
                label = str(S.ah_lbl_state_county_row),
                placeholder = str(S.ah_lbl_state_county_row),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitTextField(
                value = address.postalCode,
                onValueChange = { set(address.copy(postalCode = it)) },
                label = str(S.desktop_postal_zip_code),
                placeholder = str(S.ah_lbl_postal_code),
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = address.country,
                onValueChange = { set(address.copy(country = it)) },
                label = str(S.ah_lbl_country),
                placeholder = str(S.ah_select_country),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Line Items — the costed detail, and the two splits.
 *
 * "Split Line" halves a line; "Split by Period" divides a rental across its own
 * window at the production's cadence. They are mutually exclusive by design:
 * halving a dated rental would give two children each carrying the parent's
 * whole date range, which is not a thing that can be invoiced.
 */
@Suppress("LongMethod") // One row of controls per line; splitting it hides the row.
@Composable
private fun LineItemsSection(state: PoUiState, form: PoFormState, onEvent: (PoEvent) -> Unit) {
    val cadence = state.projectSettings.splitCadence
    ZillitSectionCard(
        title = str(S.ah_line_items),
        icon = ZillitIcons.Ledger,
        action = {
            ZillitButton(
                text = str(S.desktop_po_add_line),
                onClick = { onEvent(PoEvent.AddLine) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        LineHeader()
        form.lines.forEachIndexed { index, line ->
            LineRow(
                state = state,
                form = form,
                line = line,
                index = index,
                cadenceLabel = cadence.label,
                onEvent = onEvent,
            )
            if (index != form.lines.lastIndex) ZillitDivider()
        }
        ZillitText(
            text = str(S.desktop_po_split_help, cadence.label.lowercase()),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        if (form.lines.any { it.isSplitChild }) {
            ZillitNotice(
                text = str(S.desktop_po_rental_lines_split_by_period),
                tone = StatusTone.Progress,
                icon = ZillitIcons.Calendar,
            )
        }
    }
}

/**
 * The line table's column headers — the web draws them from the form template's
 * own field order, and they are what makes a row of bare inputs legible.
 */
@Composable
private fun LineHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        HeaderCell(str(S.description), Modifier.weight(1f))
        HeaderCell(str(S.desktop_exp_type), Modifier.width(EXP_WIDTH))
        HeaderCell(str(S.ah_lbl_qty), Modifier.width(QTY_WIDTH))
        HeaderCell(str(S.ah_lbl_unit_price), Modifier.width(PRICE_WIDTH))
        HeaderCell(str(S.code), Modifier.width(CODE_COLUMN))
        HeaderCell(str(S.ah_lbl_vat_tax), Modifier.width(TAX_WIDTH))
        HeaderCell(str(S.amount), Modifier.width(AMOUNT_WIDTH))
    }
    ZillitDivider()
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        modifier = modifier,
        maxLines = 1,
    )
}

@Suppress("LongMethod") // One line's controls; a table row reads as a row.
@Composable
private fun LineRow(
    state: PoUiState,
    form: PoFormState,
    line: PoLine,
    index: Int,
    cadenceLabel: String,
    onEvent: (PoEvent) -> Unit,
) {
    val set = { next: PoLine ->
        val lines = form.lines.mapIndexed { at, row -> if (at == index) next else row }
        onEvent(PoEvent.EditForm(form.copy(lines = lines)))
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        if (line.isSplitChild) {
            ZillitText(
                text = str(S.desktop_po_split_child),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = line.description,
                onValueChange = { set(line.copy(description = it)) },
                placeholder = str(S.ah_description_hint),
                modifier = Modifier.weight(1f),
            )
            ZillitSelect(
                value = line.expenditureType,
                options = listOf(null) + EXPENDITURE_TYPES,
                onSelect = { set(line.copy(expenditureType = it)) },
                label = { it ?: str(S.desktop_exp_type) },
                modifier = Modifier.width(EXP_WIDTH),
            )
            ZillitTextField(
                value = line.quantity.trimmed(),
                onValueChange = { set(line.copy(quantity = it.toDoubleOrNull() ?: 0.0, amount = null)) },
                placeholder = str(S.ah_lbl_qty),
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.width(QTY_WIDTH),
            )
            ZillitTextField(
                value = line.unitPrice.trimmed(),
                onValueChange = { set(line.copy(unitPrice = it.toDoubleOrNull() ?: 0.0, amount = null)) },
                placeholder = str(S.ah_lbl_unit_price),
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.width(PRICE_WIDTH),
            )
            ZillitTextField(
                value = line.nominalCode.orEmpty(),
                onValueChange = { set(line.copy(nominalCode = it.takeIf { code -> code.isNotBlank() })) },
                placeholder = str(S.code),
                modifier = Modifier.width(CODE_COLUMN),
            )
            ZillitSelect(
                value = line.taxType,
                options = listOf(null) + state.taxTypes.map { it.id },
                onSelect = { id ->
                    val tax = state.taxTypes.firstOrNull { it.id == id }
                    set(line.copy(taxType = id, vatRate = tax?.rate))
                },
                label = { id ->
                    id?.let { key -> state.taxTypes.firstOrNull { it.id == key }?.name ?: key }
                        ?: str(S.ah_lbl_vat_tax)
                },
                modifier = Modifier.width(TAX_WIDTH),
            )
            ZillitText(
                text = Money.format(line.total, form.currency),
                style = ZillitTheme.typography.numeric,
                modifier = Modifier.width(AMOUNT_WIDTH),
            )
        }
        if (line.expenditureType == RENTAL_EXPENDITURE_TYPE) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitDateField(
                    value = line.rentalStart.orEmpty(),
                    onValueChange = { set(line.copy(rentalStart = it.takeIf { day -> day.isNotBlank() })) },
                    label = str(S.start),
                    modifier = Modifier.width(RENTAL_WIDTH),
                )
                ZillitDateField(
                    value = line.rentalEnd.orEmpty(),
                    onValueChange = { set(line.copy(rentalEnd = it.takeIf { day -> day.isNotBlank() })) },
                    label = str(S.end),
                    modifier = Modifier.width(RENTAL_WIDTH),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            // Exactly one of the two is offered, never both: see the section's
            // note. A divisible rental splits by period; anything else halves.
            if (line.isDivisibleRental) {
                ZillitButton(
                    text = str(S.desktop_po_split_by_period),
                    onClick = { onEvent(PoEvent.SplitLineByPeriod(index)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Calendar,
                    enabled = !line.isSplitChild,
                )
                ZillitText(
                    text = cadenceLabel,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            } else {
                ZillitButton(
                    text = str(S.desktop_po_split_line),
                    onClick = { onEvent(PoEvent.SplitLine(index)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = !line.isSplitChild && line.total > 0,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            ZillitButton(
                text = if (line.isSplitChild) str(S.desktop_po_remove_split) else str(S.desktop_po_remove_line),
                onClick = { onEvent(PoEvent.RemoveLine(index)) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
            )
        }
    }
}

@Composable
private fun AttachmentsSection(busy: Boolean, form: PoFormState, onEvent: (PoEvent) -> Unit) {
    ZillitSectionCard(
        title = str(S.attachments),
        icon = ZillitIcons.Paperclip,
        action = {
            ZillitButton(
                text = if (form.uploading) str(S.ah_uploading) else str(S.attachment),
                onClick = { onEvent(PoEvent.AttachFile) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Upload,
                loading = form.uploading,
                enabled = !form.uploading,
            )
        },
    ) {
        if (form.attachments.isEmpty()) {
            ZillitText(
                text = str(S.desktop_po_attachment_hint),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            return@ZillitSectionCard
        }
        form.attachments.forEach { file ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitText(
                    text = file.displayName,
                    style = ZillitTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                ZillitButton(
                    text = str(S.view),
                    onClick = { onEvent(PoEvent.OpenAttachment(file)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = !busy,
                )
                ZillitButton(
                    text = str(S.remove),
                    onClick = { onEvent(PoEvent.RemoveAttachment(file)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    enabled = !busy,
                )
            }
        }
    }
}

/** Net, tax, gross — the form's footer, and the figure the order is judged on. */
@Composable
private fun TotalsCard(form: PoFormState) {
    val totals = form.totals
    ZillitSectionCard(title = str(S.desktop_po_total), icon = ZillitIcons.Bank) {
        TotalRow(str(S.ah_lbl_net_total), Money.format(totals.net, form.currency))
        TotalRow(str(S.ah_lbl_vat_tax), Money.format(totals.tax, form.currency))
        ZillitDivider()
        TotalRow(str(S.ah_lbl_gross_total), Money.format(totals.gross, form.currency), strong = true)
    }
}

@Composable
private fun TotalRow(label: String, value: String, strong: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(
            text = label,
            style = if (strong) ZillitTheme.typography.titleSmall else ZillitTheme.typography.bodyMedium,
            color = if (strong) ZillitTheme.colors.textPrimary else ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = value,
            style = if (strong) ZillitTheme.typography.titleSmall else ZillitTheme.typography.numeric,
        )
    }
}

/**
 * A label above a control that does not draw its own.
 *
 * `ZillitSelect` has no label slot — its `label` lambda renders the *option* —
 * so every select on this form is wrapped. The style is `ZillitTextField`'s own
 * label style, because a form where half the labels sit above their field and
 * half below reads as broken.
 */
@Composable
private fun LabelledField(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textSecondary,
        )
        content()
    }
}

/** A quantity or price as a field shows it: no trailing `.0` on a whole number. */
internal fun Double.trimmed(): String {
    if (this == 0.0) return ""
    val rounded = kotlin.math.round(this * PENNIES) / PENNIES
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
}

private const val PENNIES = 100.0

private val EXPENDITURE_TYPES = listOf("Purchase", "Consumption", RENTAL_EXPENDITURE_TYPE)
private val EXP_WIDTH = 130.dp
private val QTY_WIDTH = 70.dp
private val PRICE_WIDTH = 110.dp
private val CODE_COLUMN = 110.dp
private val TAX_WIDTH = 120.dp
private val CODE_WIDTH = 90.dp
private val RENTAL_WIDTH = 160.dp
