package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.SelectionType
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashFormFields
import com.zillit.desktop.feature.cashexpenses.domain.CrewInput
import com.zillit.desktop.feature.cashexpenses.domain.CrewRequestCap
import com.zillit.desktop.feature.cashexpenses.ui.CashBadges
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.CrewEvent
import com.zillit.desktop.feature.cashexpenses.ui.FloatRequestDraft
import com.zillit.desktop.feature.cashexpenses.ui.FloatStatusPill
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import com.zillit.desktop.feature.cashexpenses.ui.date
import com.zillit.desktop.feature.cashexpenses.ui.floatCap
import com.zillit.desktop.feature.cashexpenses.ui.floatCapApplies
import com.zillit.desktop.feature.cashexpenses.ui.floatRequestFields
import com.zillit.desktop.feature.cashexpenses.ui.floatValue

/**
 * Float Request (`PCFloatRequestPage.jsx`).
 *
 * Crew land on their floats — several may be open at once — and raise a new
 * one from there; an accountant raising one for crew lands on the form. The
 * form is the production's template: every float-request field it shows,
 * each one required but the department.
 */
@Composable
fun FloatRequestPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val accountant = state.viewer.isAccountant
    val name = LocalCashPeople.current.nameOrNull(state.viewer.userId) ?: str(S.user_label)
    val department = state.departmentName(state.viewer.departmentId) ?: str(S.desktop_po_dept_column)
    ScrollingPage {
        CrewNotice(
            title = str(S.desktop_pc_crew_portal, "$name ($department)"),
            body = str(S.desktop_pc_request_new_float),
        )
        if (!state.crew.floatFormOpen) {
            FloatLanding(state, onEvent)
            return@ScrollingPage
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl), verticalAlignment = Alignment.Top) {
            // Raising one for crew needs none of the crew's process sidebar.
            if (!accountant) {
                Column(
                    modifier = Modifier.width(SIDE_WIDTH),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    PreviousFloats(state, onEvent)
                    CrewInfoBox(title = str(S.desktop_pc_heads_up), text = str(S.desktop_pc_run_of_show_note))
                    WhatHappensNext()
                }
            }
            FloatForm(state, onEvent, Modifier.weight(1f))
        }
    }
}

// -- the landing ---------------------------------------------------------------

@Composable
private fun FloatLanding(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val newFloat = @Composable {
        ZillitButton(
            text = str(S.ah_new_float),
            onClick = { onEvent(CrewEvent.ShowFloatForm(true)) },
            leadingIcon = ZillitIcons.Add,
        )
    }
    when {
        state.loading && state.myFloats.isEmpty() -> ZillitText(
            text = str(S.ah_loading),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        state.myFloats.isEmpty() -> ZillitEmptyState(
            title = str(S.desktop_pc_no_float_yet),
            icon = ZillitIcons.Wallet,
            action = newFloat,
        )
        else -> Column(
            modifier = Modifier.widthIn(max = LANDING_WIDTH),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.large)
                    .background(ZillitTheme.colors.warningSoft)
                    .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = str(S.desktop_pc_need_cash_note),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.warning,
                    modifier = Modifier.weight(1f),
                )
                newFloat()
            }
            FloatListCard(title = str(S.desktop_pc_my_floats)) {
                // Oldest first, as the module keeps them — the web's order too.
                state.myFloats.forEach { float ->
                    FloatListRow(state, float, onEvent)
                }
            }
        }
    }
}

@Composable
private fun FloatListCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(CREW_HAIRLINE, colors.border, ZillitTheme.shapes.large),
    ) {
        Column(modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md)) {
            ZillitText(text = title, style = ZillitTheme.typography.titleSmall)
            subtitle?.let { ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted) }
        }
        CrewRule()
        content()
    }
}

/** "#12 · £400.00", "03 Sep, 2026 · £120.00 receipts · £20.00 returned", its unread and its status. */
@Composable
private fun FloatListRow(state: CashUiState, float: CashFloat, onEvent: (CashEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val money = { amount: Double -> state.formatMoney(amount, float.currency) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEvent(CashEvent.OpenFloatDetail(float.id)) }
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitIcon(icon = ZillitIcons.Wallet, tint = colors.accentText)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = "#${float.requestNumber.ifBlank { "—" }} · ${money(float.requestedAmount)}",
                style = ZillitTheme.typography.label,
                maxLines = 1,
            )
            ZillitText(
                text = listOfNotNull(
                    date(float.createdAt),
                    float.receiptsAmount.takeIf { it > 0 }?.let { str(S.desktop_pc_amount_receipts, money(it)) },
                    float.returnAmount.takeIf { it > 0 }?.let { str(S.desktop_pc_amount_returned, money(it)) },
                ).joinToString(" · "),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
        ZillitBadge(count = state.unreadFor(CashBadges.PC_FLOAT, float.id))
        FloatStatusPill(float.status)
    }
}

// -- the form's sidebar ----------------------------------------------------------

@Composable
private fun PreviousFloats(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val colors = ZillitTheme.colors
    FloatListCard(title = str(S.desktop_pc_previous_floats), subtitle = str(S.desktop_pc_your_float_history)) {
        if (state.myFloats.isEmpty()) {
            ZillitText(
                text = str(S.desktop_pc_no_previous_floats),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(ZillitTheme.spacing.lg),
            )
        }
        state.myFloats.forEach { float ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEvent(CashEvent.OpenFloatDetail(float.id)) }
                    .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(SYMBOL).clip(CircleShape).background(colors.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitText(
                        text = state.currencies.symbolFor(state.currencyOf(float.currency)).ifBlank { "£" },
                        style = ZillitTheme.typography.label,
                        color = colors.accentText,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(text = "#${float.requestNumber.ifBlank { "—" }}", style = ZillitTheme.typography.numeric)
                    ZillitText(
                        text = date(float.createdAt),
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                ) {
                    ZillitText(
                        text = state.formatMoney(float.requestedAmount, float.currency),
                        style = ZillitTheme.typography.numeric,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                        ZillitBadge(count = state.unreadFor(CashBadges.PC_FLOAT, float.id))
                        FloatStatusPill(float.status)
                    }
                }
            }
        }
    }
}

@Composable
private fun WhatHappensNext() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surfaceSunken)
            .border(CREW_HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        CrewHeading(str(S.desktop_pc_what_happens_next))
        StepLine(str(S.submit), str(S.desktop_pc_next_submit_sub), null, active = true, number = 1)
        StepLine(
            str(S.desktop_pc_next_coordinator),
            str(S.desktop_pc_next_coordinator_sub),
            null,
            active = false,
            number = 2,
        )
        StepLine(str(S.desktop_pc_next_accounts), str(S.desktop_pc_next_accounts_sub), null, active = false, number = 3)
        StepLine(str(S.desktop_pc_next_collect), str(S.desktop_pc_next_collect_sub), null, active = false, number = 4)
    }
}

// -- the form ----------------------------------------------------------------------

@Suppress("LongMethod") // The header, the currency, the template's rows and the footer.
@Composable
private fun FloatForm(state: CashUiState, onEvent: (CashEvent) -> Unit, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val fields = state.floatRequestFields
    if (fields.any { it.selection == SelectionType.AccountCode }) {
        LaunchedEffect(Unit) { onEvent(CashEvent.LoadChartAccounts) }
    }
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(CREW_HAIRLINE, colors.border, ZillitTheme.shapes.large)
            .padding(FORM_PADDING),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(text = str(S.desktop_pc_float_details), style = ZillitTheme.typography.titleMedium)
                ZillitText(
                    text = str(S.desktop_pc_be_specific),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            ZillitStatusPill(label = str(S.desktop_ce_new_fund_request), tone = StatusTone.Pending, dot = true)
        }
        CrewField(str(S.asset_currency), required = true, error = state.crew.floatErrors[CURRENCY_KEY]) {
            CurrencyPicker(
                currencies = state.currencies,
                value = state.floatDraft.currency,
                onPick = { onEvent(CashEvent.EditFloatRequest(state.floatDraft.copy(currency = it))) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        fieldRows(fields).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
                row.forEach { field -> FloatFieldCell(state, field, onEvent, Modifier.weight(1f)) }
                if (row.size == 1 && row.single().type != CashFormFields.TEXTAREA) Box(Modifier.weight(1f))
            }
        }
        CrewInfoBox(text = str(S.desktop_pc_retain_receipts_note))
        CrewErrorBox(state.crew.floatSubmitError)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitButton(
                text = str(S.cancel),
                onClick = {
                    if (state.viewer.isAccountant) {
                        onEvent(CashEvent.Open(CashDestination.ActiveFloats))
                    } else {
                        onEvent(CrewEvent.ShowFloatForm(false))
                    }
                },
                variant = ButtonVariant.Tertiary,
            )
            Box(Modifier.weight(1f))
            ZillitButton(
                text = str(S.desktop_pc_submit_float_request),
                onClick = { onEvent(CashEvent.SubmitFloatRequest) },
                trailingIcon = ZillitIcons.ArrowRight,
                loading = state.busy,
            )
        }
    }
}

/**
 * The web's `buildFieldRows`: fields in pairs, a textarea on its own row, and
 * the collection method alone so the date after it keeps its pair.
 */
private fun fieldRows(fields: List<FormField>): List<List<FormField>> {
    val rows = mutableListOf<List<FormField>>()
    var pending: FormField? = null
    fun flush() {
        pending?.let { rows += listOf(it) }
        pending = null
    }
    fields.forEach { field ->
        when {
            field.type == CashFormFields.TEXTAREA -> {
                flush()
                rows += listOf(field)
            }
            field.label == CashFormFields.COLLECTION_METHOD -> {
                flush()
                rows += listOf(field)
            }
            pending != null -> {
                rows += listOf(pending!!, field)
                pending = null
            }
            else -> pending = field
        }
    }
    flush()
    return rows
}

@Composable
private fun FloatFieldCell(state: CashUiState, field: FormField, onEvent: (CashEvent) -> Unit, modifier: Modifier) {
    val draft = state.floatDraft
    // Days keeps its slot beside How long even while hidden.
    if (field.label == CashFormFields.DURATION && draft.durationType != CashFormFields.DAYS) {
        Box(modifier)
        return
    }
    val custom = !field.systemDefault && field.label !in CashFormFields.RENDERED
    CrewField(
        label = field.name.ifBlank { field.label },
        modifier = modifier,
        required = field.required,
        optional = custom && !field.required,
        error = state.crew.floatErrors[field.label],
        helper = helperFor(state, field),
    ) {
        if (custom) {
            CustomFieldInput(state, field, onEvent)
        } else {
            SystemFieldInput(state, field, onEvent)
        }
    }
}

@Composable
private fun helperFor(state: CashUiState, field: FormField): String? = when (field.label) {
    CashFormFields.USER -> str(S.desktop_pc_prefilled_profile).takeUnless { state.viewer.isAccountant }
    CashFormFields.PURPOSE -> str(S.desktop_pc_purpose_helper)
    CashFormFields.AMOUNT -> capHint(state)
    else -> null
}

/** The live cap under the amount — `useRequestCapGuard.hint`, for what this client can judge. */
@Composable
private fun capHint(state: CashUiState): String? {
    val cap = state.floatCap?.takeIf { it.enabled && !it.isWeekly && it.maxAmount > 0 } ?: return null
    if (!state.floatCapApplies) return str(S.desktop_pc_requester_cap_applies)
    val currency = state.floatDraft.currency.ifBlank { state.currencies.default }
    if (!CrewRequestCap.enforceable(cap, currency, state.currencies.default)) {
        return str(S.desktop_pc_cap_no_rate, currency)
    }
    return str(S.desktop_pc_cap_hint, state.currencies.formatAggregate(cap.maxAmount))
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // One control per system field.
@Composable
private fun SystemFieldInput(state: CashUiState, field: FormField, onEvent: (CashEvent) -> Unit) {
    val draft = state.floatDraft
    val edit = { changed: FloatRequestDraft -> onEvent(CashEvent.EditFloatRequest(changed)) }
    val full = Modifier.fillMaxWidth()
    when (field.label) {
        CashFormFields.USER -> if (state.viewer.isAccountant) {
            val crew = state.assignees.filter { it.userId.isNotBlank() }.sortedBy { it.fullName.lowercase() }
            val self = state.viewer.userId
            ZillitSelect(
                value = draft.targetUserId.ifBlank { self },
                options = (listOf(self) + crew.map { it.userId }).distinct(),
                onSelect = { id ->
                    // The department follows the person picked.
                    val person = crew.firstOrNull { it.userId == id }
                    edit(draft.copy(targetUserId = id, departmentId = person?.departmentId.orEmpty()))
                },
                label = { id -> personLabel(state, id) },
                modifier = full,
            )
        } else {
            ReadOnly(personLabel(state, state.viewer.userId))
        }
        CashFormFields.DEPARTMENT -> ReadOnly(
            state.departmentName(state.floatValue(field).takeIf { it.isNotBlank() })
                ?: state.viewer.departmentIdentifier?.takeIf { draft.targetUserId.isBlank() }.orEmpty(),
        )
        CashFormFields.AMOUNT -> ZillitTextField(
            value = draft.amount,
            onValueChange = { edit(draft.copy(amount = CrewInput.amount(it))) },
            placeholder = state.currencies.symbolFor(draft.currency.ifBlank { state.currencies.default }) + "0.00",
            keyboardType = KeyboardType.Decimal,
            modifier = full,
        )
        CashFormFields.DURATION_TYPE -> OptionSelect(field.label, draft.durationType, allowEmpty = true, full) {
            edit(draft.copy(durationType = it))
        }
        CashFormFields.DURATION -> ZillitTextField(
            value = draft.duration,
            onValueChange = { edit(draft.copy(duration = CrewInput.digits(it, DAYS_DIGITS))) },
            placeholder = str(S.desktop_pc_days_placeholder),
            keyboardType = KeyboardType.Number,
            modifier = full,
        )
        CashFormFields.COLLECT_DATE -> ZillitDateField(
            value = draft.collectDate,
            onValueChange = { edit(draft.copy(collectDate = it)) },
            modifier = full,
        )
        CashFormFields.EPISODE -> OptionSelect(field.label, state.floatValue(field), !field.required, full) {
            edit(draft.copy(episode = it))
        }
        CashFormFields.COLLECTION_METHOD -> OptionSelect(field.label, state.floatValue(field), !field.required, full) {
            edit(draft.copy(collectionMethod = it))
        }
        CashFormFields.PURPOSE -> ZillitTextField(
            value = draft.purpose,
            onValueChange = { edit(draft.copy(purpose = it)) },
            singleLine = false,
            modifier = full.widthIn(min = TEXTAREA_MIN),
        )
        else -> ZillitTextField(
            value = draft.customFields[field.label].orEmpty(),
            onValueChange = { edit(draft.copy(customFields = draft.customFields + (field.label to it))) },
            modifier = full,
        )
    }
}

@Composable
private fun OptionSelect(
    field: String,
    value: String,
    allowEmpty: Boolean,
    modifier: Modifier,
    onPick: (String) -> Unit,
) {
    val options = CashFormFields.OPTIONS[field].orEmpty().map { it.first }
    ZillitSelect(
        value = value,
        options = if (allowEmpty) listOf("") + options else options,
        onSelect = onPick,
        label = { if (it.isBlank()) str(S.dm_step2_select_rtw) else CashFormFields.optionLabel(field, it) },
        modifier = modifier,
    )
}

/** A production's own field, by its type and source (`renderCustomField`). */
@Suppress("LongMethod", "CyclomaticComplexMethod") // One control per template type.
@Composable
private fun CustomFieldInput(state: CashUiState, field: FormField, onEvent: (CashEvent) -> Unit) {
    val draft = state.floatDraft
    val value = draft.customFields[field.label].orEmpty()
    val set = { text: String ->
        onEvent(CashEvent.EditFloatRequest(draft.copy(customFields = draft.customFields + (field.label to text))))
    }
    val full = Modifier.fillMaxWidth()
    when {
        field.type == "select" && field.selection == SelectionType.User ->
            PickSelect(
                value,
                state.assignees.filter { it.userId.isNotBlank() }.map { it.userId to personLabel(state, it.userId) },
                set,
            )
        field.type == "select" && field.selection == SelectionType.Department ->
            PickSelect(value, state.departments.map { it.id to it.name }, set)
        field.type == "select" && field.selection == SelectionType.Currency ->
            PickSelect(value, state.currencies.currencies.map { it.code to it.code }, set)
        field.type == "select" && field.selection == SelectionType.ExpenditureType ->
            PickSelect(value, EXPENDITURE_TYPES.map { it to it }, set)
        field.type == "select" && field.selection == SelectionType.AccountCode -> SuggestField(
            value = value,
            onValueChange = set,
            suggestions = state.chartAccounts.orEmpty(),
            text = { it.label },
            pick = { it.code },
            placeholder = str(S.desktop_pc_enter_code),
            modifier = full,
        )
        field.type == CashFormFields.TEXTAREA -> ZillitTextField(
            value = value,
            onValueChange = set,
            placeholder = "${field.name}…",
            singleLine = false,
            modifier = full,
        )
        field.type == "number" -> ZillitTextField(
            value = value,
            onValueChange = set,
            placeholder = "0",
            keyboardType = KeyboardType.Decimal,
            modifier = full,
        )
        field.type == "date" -> ZillitDateField(value = value, onValueChange = set, modifier = full)
        else -> ZillitTextField(
            value = value,
            onValueChange = set,
            placeholder = if (field.selection == SelectionType.Tags) {
                str(S.desktop_pc_tags_placeholder)
            } else {
                "${field.name}…"
            },
            modifier = full,
        )
    }
}

/** A select over `(value, label)` pairs, with an empty first option. */
@Composable
private fun PickSelect(value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    ZillitSelect(
        value = value,
        options = listOf("") + options.map { it.first },
        onSelect = onPick,
        label = { id ->
            if (id.isBlank()) str(S.dm_step2_select_rtw) else options.firstOrNull { it.first == id }?.second ?: id
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ReadOnly(text: String) {
    ZillitTextField(value = text, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth())
}

/** "Ada Lovelace (Gaffer)". */
private fun personLabel(state: CashUiState, userId: String): String {
    val person = state.assignees.firstOrNull { it.userId == userId } ?: return userId
    return if (person.designation.isNotBlank()) "${person.fullName} (${person.designation})" else person.fullName
}

/** The currency picker's error key — not a template field. */
internal const val CURRENCY_KEY = "currency"

private val EXPENDITURE_TYPES = listOf("Purchase", "Consumption", "Rent")
private val SIDE_WIDTH = 300.dp
private val LANDING_WIDTH = 700.dp
private val FORM_PADDING = 28.dp
private val SYMBOL = 32.dp
private val TEXTAREA_MIN = 200.dp
private const val DAYS_DIGITS = 4
