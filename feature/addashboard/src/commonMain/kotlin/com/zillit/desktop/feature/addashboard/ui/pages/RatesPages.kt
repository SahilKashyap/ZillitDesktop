package com.zillit.desktop.feature.addashboard.ui.pages

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
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.AdCrewMember
import com.zillit.desktop.feature.addashboard.domain.Agency
import com.zillit.desktop.feature.addashboard.domain.AgreementAllowanceRow
import com.zillit.desktop.feature.addashboard.domain.DayKind
import com.zillit.desktop.feature.addashboard.domain.DistributionEntry
import com.zillit.desktop.feature.addashboard.domain.PayRule
import com.zillit.desktop.feature.addashboard.domain.PayRuleField
import com.zillit.desktop.feature.addashboard.domain.PayRuleGroup
import com.zillit.desktop.feature.addashboard.domain.PayRuleTemplate
import com.zillit.desktop.feature.addashboard.domain.PayRuleTemplates
import com.zillit.desktop.feature.addashboard.domain.RateBasis
import com.zillit.desktop.feature.addashboard.domain.RateKind
import com.zillit.desktop.feature.addashboard.domain.RateLine
import com.zillit.desktop.feature.addashboard.domain.UnionAgreementRef
import com.zillit.desktop.feature.addashboard.ui.AdEvent
import com.zillit.desktop.feature.addashboard.ui.AdUiState
import com.zillit.desktop.feature.addashboard.ui.DayTypeField
import com.zillit.desktop.feature.addashboard.ui.MoneyField
import com.zillit.desktop.feature.addashboard.ui.RATE_CATEGORY_LABELS
import com.zillit.desktop.feature.addashboard.ui.RatesEvent

/**
 * Rates & Config: what artistes are paid, and who receives the reports.
 *
 * One document with **one Save** — the non-union rates, every union pay
 * breakdown and the distribution list all go in a single write, so a
 * distribution edit needs that Save too. A remote change is only applied while
 * nothing here is unsaved; the last Save wins.
 */
@Composable
internal fun ConfigPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    AdPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.rates.dirty) {
                ZillitStatusPill(label = str(S.cs_exit_title), tone = StatusTone.Pending)
            }
            ZillitButton(
                text = str(S.dm_setup_save),
                onClick = { onEvent(RatesEvent.Save) },
                enabled = state.rates.dirty,
                loading = state.rates.saving,
                leadingIcon = ZillitIcons.Save,
            )
        }

        if (state.currencies.defaultCode.isBlank()) {
            // Without it every amount prints bare: the project default is the
            // only symbol source, deliberately — a wrong-currency sign is worse.
            ZillitNotice(
                text = str(S.desktop_ad_no_default_currency),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        PermissionsAndDistribution(state, onEvent)
        VoucherFields()
        RegisteredAgencies(state, onEvent)
        NonUnionRatesSection(state, onEvent)
        UnionBreakdowns(state, onEvent)
    }

    DistributionPickerDialog(state, onEvent)
    VendorFormDialog(state, onEvent)
    DeleteVendorDialog(state, onEvent)
    DeleteUnionDialog(state, onEvent)
}

/**
 * 01 · Permissions & Distribution.
 *
 * The distribution list only. The viewing-and-posting grid the web once had
 * here is switched off, and rights live in the Permission Grid; the approvers
 * picker is hidden too, though `report_approvers` still round-trips.
 */
@Composable
private fun PermissionsAndDistribution(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    ZillitSectionCard(
        title = str(S.desktop_ad_permissions_and_distribution),
        meta = str(S.desktop_ad_distribution_meta),
        action = {
            ZillitButton(
                text = str(S.desktop_ad_choose_recipients),
                onClick = { onEvent(RatesEvent.OpenDistributionPicker) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Mail,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            if (state.rates.nonUnion.distribution.isEmpty()) {
                ZillitText(
                    text = str(S.desktop_ad_no_recipients),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                state.rates.nonUnion.distribution.forEach { entry ->
                    ZillitChoiceChip(
                        label = entry.name.ifBlank { entry.email },
                        selected = true,
                        onClick = { onEvent(RatesEvent.ToggleDistribution(entry)) },
                    )
                }
            }
        }
    }
}

/**
 * 02 · Voucher Fields.
 *
 * Display only, as on the web: the agreement text shown on a voucher is not
 * saved anywhere, so it is stated rather than offered as an editable field
 * that would silently discard what was typed.
 */
@Composable
private fun VoucherFields() {
    ZillitSectionCard(title = str(S.desktop_ad_voucher_fields)) {
        ZillitText(
            text = str(S.desktop_ad_voucher_fields_note),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** 03 · Registered Agencies — the list a purchase order can be raised against. */
@Composable
private fun RegisteredAgencies(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    ZillitSectionCard(
        title = str(S.desktop_ad_registered_agencies),
        meta = str(S.desktop_ad_n_agencies, state.rates.config.vendors.size),
        padded = false,
        action = {
            ZillitButton(
                text = str(S.add),
                onClick = { onEvent(RatesEvent.StartVendorForm(null)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        ZillitDataTable(
            rows = state.rates.config.vendors,
            columns = listOf(
                textColumn(str(S.name), width = ColumnWidth.Weight(2f)) { it.name },
                textColumn(str(S.email)) { it.email },
                TableColumn(str(S.status), width = ColumnWidth.Fixed(STATUS_WIDTH)) { agency ->
                    ZillitStatusPill(
                        label = if (agency.verified) str(S.ah_verified) else str(S.pending),
                        tone = if (agency.verified) StatusTone.Done else StatusTone.Pending,
                    )
                },
                TableColumn(str(S.dd_actions), width = ColumnWidth.Fixed(ACTION_WIDTH)) { agency ->
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = str(S.remove),
                        onClick = { onEvent(RatesEvent.AskDeleteVendor(agency)) },
                    )
                },
            ),
            key = { it.id },
            emptyTitle = str(S.desktop_ad_no_agencies),
            virtualised = false,
        )
    }
}

/** 04 · Non-union Rates: a day rate per category, day types, rules, and money. */
@Suppress("LongMethod") // One screen, laid out once; splitting it hides the page's shape.
@Composable
private fun NonUnionRatesSection(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val rates = state.rates.nonUnion
    ZillitSectionCard(
        title = str(S.desktop_ad_non_union_rates),
        meta = str(S.desktop_ad_non_union_per_day),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(
                text = str(S.desktop_ad_non_union_note),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
            // The eight categories, keyed by their label: `day_rates` is the one
            // place in this module where the label is the key, not the display.
            RATE_CATEGORY_LABELS.forEach { category ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(
                        text = category,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        style = ZillitTheme.typography.bodySmall,
                    )
                    ZillitTextField(
                        value = rates.dayRates[category].orEmpty(),
                        onValueChange = { onEvent(RatesEvent.DayRate(category, it)) },
                        modifier = Modifier.width(AMOUNT_WIDTH),
                        placeholder = "0.00",
                        leadingIcon = null,
                    )
                }
            }
        }
    }

    ZillitSectionCard(
        title = str(S.desktop_ad_day_types),
        meta = str(S.desktop_ad_day_types_meta),
        action = {
            PRESET_DAY_TYPES.forEach { preset ->
                ZillitButton(
                    text = preset,
                    onClick = { onEvent(RatesEvent.AddDayType(preset)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            ZillitButton(
                text = str(S.custom),
                onClick = { onEvent(RatesEvent.AddDayType("")) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            rates.dayTypes.forEachIndexed { index, option ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitTextField(
                        value = option.dayType,
                        onValueChange = { onEvent(RatesEvent.DayTypeEdit(index, DayTypeField.Name, it)) },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        placeholder = str(S.name),
                    )
                    ZillitTextField(
                        value = option.workMinutes.toString(),
                        onValueChange = {
                            onEvent(RatesEvent.DayTypeEdit(index, DayTypeField.WorkMinutes, it))
                        },
                        modifier = Modifier.width(MINUTES_WIDTH),
                        label = str(S.desktop_ad_work_minutes),
                    )
                    ZillitTextField(
                        value = option.mealBreakMinutes.toString(),
                        onValueChange = {
                            onEvent(RatesEvent.DayTypeEdit(index, DayTypeField.MealMinutes, it))
                        },
                        modifier = Modifier.width(MINUTES_WIDTH),
                        label = str(S.desktop_ad_meal_minutes),
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = str(S.remove),
                        onClick = { onEvent(RatesEvent.RemoveDayType(index)) },
                    )
                }
            }
        }
    }

    PayRuleGroup.entries.forEach { group ->
        ZillitSectionCard(
            title = group.label,
            meta = rates.rules(group).size.toString(),
            action = {
                ZillitButton(
                    text = str(S.add),
                    onClick = { onEvent(RatesEvent.AddRule(group)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                if (rates.rules(group).isEmpty()) {
                    ZillitText(
                        text = str(S.desktop_ad_no_rules_defined),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
                rates.rules(group).forEach { rule ->
                    PayRuleRow(state, group, rule, onEvent)
                }
            }
        }
    }

    MoneyListCard(
        title = str(S.allowances_label),
        rows = rates.allowances,
        state = state,
        onAdd = { onEvent(RatesEvent.AddAllowance) },
        onField = { index, field, text -> onEvent(RatesEvent.AllowanceField(index, field, text)) },
        onRemove = { onEvent(RatesEvent.RemoveAllowance(it)) },
    )
    MoneyListCard(
        title = str(S.desktop_additional_fees),
        rows = rates.additionalFees,
        state = state,
        onAdd = { onEvent(RatesEvent.AddFee) },
        onField = { index, field, text -> onEvent(RatesEvent.FeeField(index, field, text)) },
        onRemove = { onEvent(RatesEvent.RemoveFee(it)) },
    )
}

/**
 * One pay rule, collapsed to a summary and expanded to its editor.
 *
 * The rule's **template** is what turns this short form into the trigger the
 * pay engine reads, and the template's id rides in the saved rule — which is
 * what makes a rule reopen as the same rule.
 */
@Suppress("LongMethod") // One screen, laid out once; splitting it hides the page's shape.
@Composable
private fun PayRuleRow(
    state: AdUiState,
    group: PayRuleGroup,
    rule: PayRule,
    onEvent: (AdEvent) -> Unit,
) {
    val editing = state.rates.editingRule == rule.id
    val template = rule.template
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                ZillitText(
                    text = rule.label.ifBlank { template.label },
                    style = ZillitTheme.typography.bodyMedium,
                )
                ZillitText(
                    text = "${summarise(rule)} · ${rule.basis.short}",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            ZillitText(text = rule.formatRate(), style = ZillitTheme.typography.numeric)
            ZillitText(
                text = rule.nominalCode.ifBlank { EM_DASH },
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
            ZillitIconButton(
                icon = if (editing) ZillitIcons.Tick else ZillitIcons.ChevronDown,
                contentDescription = str(if (editing) S.ah_done else S.edit),
                onClick = { onEvent(RatesEvent.EditRule(if (editing) null else rule.id)) },
            )
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = str(S.remove),
                onClick = { onEvent(RatesEvent.RemoveRule(group, rule.id)) },
            )
        }

        if (!editing) return@Column

        ZillitSelect(
            value = template,
            options = PayRuleTemplates.all,
            onSelect = { picked ->
                onEvent(
                    RatesEvent.PatchRule(
                        group,
                        rule.copy(templateId = picked.id, formValue = picked.defaultValue),
                    ),
                )
            },
            label = { it.label },
            subtitle = { it.helper },
            section = { it.group.label },
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitTextField(
            value = rule.label,
            onValueChange = { onEvent(RatesEvent.PatchRule(group, rule.copy(label = it))) },
            modifier = Modifier.fillMaxWidth(),
            label = str(S.dm_rules_label_hint),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitSelect(
                value = rule.rateKind,
                options = RateKind.entries,
                onSelect = { onEvent(RatesEvent.PatchRule(group, rule.copy(rateKind = it))) },
                label = { it.label },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            ZillitTextField(
                value = rule.amount,
                onValueChange = { onEvent(RatesEvent.PatchRule(group, rule.copy(amount = it))) },
                modifier = Modifier.width(AMOUNT_WIDTH),
                label = str(S.amount),
            )
            ZillitSelect(
                value = rule.basis,
                options = RateBasis.entries,
                onSelect = { onEvent(RatesEvent.PatchRule(group, rule.copy(basis = it))) },
                label = { it.label },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }

        TemplateField(template, rule) { onEvent(RatesEvent.PatchRule(group, it)) }

        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = rule.increments,
                onValueChange = { onEvent(RatesEvent.PatchRule(group, rule.copy(increments = it))) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = str(S.desktop_bill_in_increments),
                helperText = str(S.desktop_ad_increments_hint),
            )
            ZillitTextField(
                value = rule.cap,
                onValueChange = { onEvent(RatesEvent.PatchRule(group, rule.copy(cap = it))) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = str(S.desktop_hub_cap_maximum_payout),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = rule.bdrMin,
                onValueChange = { onEvent(RatesEvent.PatchRule(group, rule.copy(bdrMin = it))) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = str(S.desktop_ad_bdr_min),
            )
            ZillitTextField(
                value = rule.bdrMax,
                onValueChange = { onEvent(RatesEvent.PatchRule(group, rule.copy(bdrMax = it))) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = str(S.desktop_ad_bdr_max),
            )
        }
        // The nominal code comes from the project's chart of accounts; the
        // union editor has none, deliberately.
        ZillitSearchSelect(
            value = state.rates.nominalCodes.firstOrNull { it.code == rule.nominalCode },
            options = state.rates.nominalCodes,
            onSelect = { onEvent(RatesEvent.PatchRule(group, rule.copy(nominalCode = it.code))) },
            label = { it.display },
            modifier = Modifier.fillMaxWidth(),
            placeholder = str(S.desktop_nominal_code_optional),
            onClear = { onEvent(RatesEvent.PatchRule(group, rule.copy(nominalCode = ""))) },
        )
        ZillitTextField(
            value = rule.note,
            onValueChange = { onEvent(RatesEvent.PatchRule(group, rule.copy(note = it))) },
            modifier = Modifier.fillMaxWidth(),
            label = str(S.desktop_notes_optional),
        )
    }
}

/** The one field a rule's template asks for, whichever kind it is. */
@Composable
private fun TemplateField(template: PayRuleTemplate, rule: PayRule, onPatch: (PayRule) -> Unit) {
    when (template.field) {
        PayRuleField.None -> ZillitText(
            text = str(S.desktop_ad_rule_always_on),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )

        PayRuleField.Hours -> ZillitTextField(
            value = rule.formValue,
            onValueChange = { onPatch(rule.copy(formValue = it)) },
            modifier = Modifier.width(AMOUNT_WIDTH),
            label = str(S.desktop_ad_hours_threshold),
        )

        PayRuleField.Clock -> ZillitTextField(
            value = rule.formValue,
            onValueChange = { onPatch(rule.copy(formValue = it)) },
            modifier = Modifier.width(AMOUNT_WIDTH),
            label = str(S.desktop_ad_clock_threshold),
            placeholder = "22:00",
        )

        PayRuleField.DayKinds -> Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitSectionLabel(text = str(S.desktop_ad_fires_on_any_of))
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                DayKind.entries.forEach { kind ->
                    val on = kind in rule.dayKinds
                    ZillitChoiceChip(
                        label = kind.label,
                        selected = on,
                        onClick = {
                            val next = if (on) rule.dayKinds - kind else rule.dayKinds + kind
                            onPatch(rule.copy(dayKinds = next))
                        },
                    )
                }
            }
        }
    }
}

/**
 * Allowances and additional fees.
 *
 * Flat, per day, full production, uncapped — those four are fixed by this
 * editor, so only the name, the amount and the nominal code are offered.
 */
@Composable
private fun MoneyListCard(
    title: String,
    rows: List<RateLine>,
    state: AdUiState,
    onAdd: () -> Unit,
    onField: (Int, MoneyField, String) -> Unit,
    onRemove: (Int) -> Unit,
) {
    ZillitSectionCard(
        title = title,
        meta = str(S.desktop_ad_flat_per_day_uncapped),
        action = {
            ZillitButton(
                text = str(S.add),
                onClick = onAdd,
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            if (rows.isEmpty()) {
                ZillitText(
                    text = str(S.desktop_ad_none_added),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            rows.forEachIndexed { index, line ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitTextField(
                        value = line.label,
                        onValueChange = { onField(index, MoneyField.Name, it) },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        placeholder = str(S.name),
                    )
                    ZillitTextField(
                        value = line.rateAmount.toString(),
                        onValueChange = { onField(index, MoneyField.Amount, it) },
                        modifier = Modifier.width(AMOUNT_WIDTH),
                        placeholder = "0.00",
                    )
                    ZillitSearchSelect(
                        value = state.rates.nominalCodes.firstOrNull { it.code == line.nominalCode },
                        options = state.rates.nominalCodes,
                        onSelect = { onField(index, MoneyField.NominalCode, it.code) },
                        label = { it.display },
                        modifier = Modifier.width(NOMINAL_WIDTH),
                        placeholder = str(S.dm_rule_nominal),
                        onClear = { onField(index, MoneyField.NominalCode, "") },
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = str(S.remove),
                        onClick = { onRemove(index) },
                    )
                }
            }
        }
    }
}

/** 05 · Union Pay Breakdown — one document per agreement. */
@Composable
private fun UnionBreakdowns(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    ZillitSectionCard(
        title = str(S.desktop_ad_union_pay_breakdown),
        meta = str(S.desktop_ad_n_agreements, state.rates.config.unionAgreements.size),
        action = {
            ZillitButton(
                text = str(S.desktop_ad_add_union_breakdown),
                onClick = { onEvent(RatesEvent.OpenUnionEditor(null)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            if (state.rates.config.unionAgreements.isEmpty()) {
                ZillitText(
                    text = str(S.desktop_ad_no_union_agreements),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            state.rates.config.unionAgreements.forEach { agreement ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(
                        text = agreement.label,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        style = ZillitTheme.typography.bodyMedium,
                    )
                    ZillitButton(
                        text = str(S.edit),
                        onClick = { onEvent(RatesEvent.OpenUnionEditor(agreement.id)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = str(S.delete),
                        onClick = { onEvent(RatesEvent.AskDeleteUnion(agreement)) },
                    )
                }
            }
        }
    }
}

/**
 * The Union Pay Breakdown page: one agreement, and the production's rates under it.
 *
 * A saved agreement renders from its **own snapshot**, with no deal-memo call —
 * the AD service cannot read the agreement, so the save copies its rules in and
 * the server builds each artiste's deal from that copy. **Refresh** is the only
 * way an upstream change reaches the pay engine.
 */
@Suppress("LongMethod") // One screen, laid out once; splitting it hides the page's shape.
@Composable
internal fun UnionConfigPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val editor = state.rates.unionEditor ?: return
    val breakdown = editor.agreementId?.let { state.rates.union[it] }
    AdPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = str(S.back),
                onClick = { onEvent(RatesEvent.CloseUnionEditor) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.ArrowLeft,
            )
            ZillitText(
                text = breakdown?.label ?: str(S.desktop_ad_add_union_breakdown),
                style = ZillitTheme.typography.titleMedium,
            )
            editor.agreementId?.let { id ->
                ZillitButton(
                    text = str(S.refresh_text),
                    onClick = { onEvent(RatesEvent.RefreshAgreement(id)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    loading = editor.refreshing,
                )
            }
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(RatesEvent.Save) },
                enabled = state.rates.dirty,
                loading = state.rates.saving,
            )
        }

        if (editor.agreementId == null) {
            ZillitSectionCard(title = str(S.desktop_ad_pick_an_agreement)) {
                Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    ZillitTextField(
                        value = editor.territory,
                        onValueChange = { onEvent(RatesEvent.PickTerritory(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = str(S.dm_section_territory),
                        placeholder = str(S.desktop_ad_territory_placeholder),
                    )
                    ZillitSearchSelect<UnionAgreementRef>(
                        value = null,
                        options = editor.catalogue,
                        onSelect = { onEvent(RatesEvent.PickAgreement(it)) },
                        label = { it.label },
                        subtitle = { it.territory },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = str(S.desktop_ad_pick_an_agreement),
                    )
                }
            }
            return@AdPage
        }

        val document = breakdown?.document
        ZillitSectionCard(
            title = str(S.desktop_ad_day_rate_per_category),
            meta = state.currencies.symbolFor(breakdown?.currency),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                RATE_CATEGORY_LABELS.forEach { category ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitText(
                            text = category,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            style = ZillitTheme.typography.bodySmall,
                        )
                        ZillitTextField(
                            value = breakdown?.dayRates?.get(category).orEmpty(),
                            onValueChange = {
                                onEvent(RatesEvent.UnionDayRate(editor.agreementId, category, it))
                            },
                            modifier = Modifier.width(AMOUNT_WIDTH),
                            placeholder = "0.00",
                        )
                    }
                }
            }
        }

        FlooredRows(
            title = str(S.allowances_label),
            rows = document?.allowances.orEmpty(),
            chosen = breakdown?.allowanceAmounts.orEmpty(),
            symbol = state.currencies.symbolFor(breakdown?.currency),
            onAmount = { rowId, amount ->
                onEvent(RatesEvent.UnionAllowance(editor.agreementId, rowId, amount))
            },
        )
        FlooredRows(
            title = str(S.desktop_additional_fees),
            rows = document?.extraFees.orEmpty(),
            chosen = breakdown?.feeAmounts.orEmpty(),
            symbol = state.currencies.symbolFor(breakdown?.currency),
            onAmount = { rowId, amount -> onEvent(RatesEvent.UnionFee(editor.agreementId, rowId, amount)) },
        )

        if (document != null) {
            ZillitSectionCard(title = str(S.desktop_ad_agreement_rules), padded = false) {
                ZillitDataTable(
                    rows = document.overtimes + document.premiums + document.penalties + document.turnaround,
                    columns = listOf(
                        textColumn(str(S.name), width = ColumnWidth.Weight(2f)) { it.label },
                        textColumn(str(S.av_rate)) { row ->
                            row.rateAmount?.toString().orEmpty()
                        },
                        textColumn(str(S.dm_rule_basis)) { it.basis.label },
                    ),
                    key = { it.id + it.label },
                    emptyTitle = str(S.desktop_ad_no_rules_defined),
                    virtualised = false,
                )
            }
        }
    }
}

/**
 * The agreement's allowance rows, floored at the agreement's own amount.
 *
 * A production may pay **above** a union minimum and never below it, so the
 * floor is shown and the field clamps to it.
 */
@Composable
private fun FlooredRows(
    title: String,
    rows: List<AgreementAllowanceRow>,
    chosen: Map<String, String>,
    symbol: String,
    onAmount: (String, String) -> Unit,
) {
    if (rows.isEmpty()) return
    ZillitSectionCard(title = title, meta = str(S.desktop_ad_floored_at_agreement)) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        ZillitText(text = row.label, style = ZillitTheme.typography.bodySmall)
                        ZillitText(
                            text = str(S.desktop_ad_agreement_minimum, "$symbol${row.floor ?: 0.0}"),
                            style = ZillitTheme.typography.labelSmall,
                            color = ZillitTheme.colors.textMuted,
                        )
                    }
                    ZillitTextField(
                        value = chosen[row.id] ?: row.floor?.toString().orEmpty(),
                        onValueChange = { onAmount(row.id, it) },
                        modifier = Modifier.width(AMOUNT_WIDTH),
                        placeholder = "0.00",
                    )
                }
            }
        }
    }
}

/** A rule's trigger in words, for the collapsed row. */
private fun summarise(rule: PayRule): String {
    val template = rule.template
    return when (template.field) {
        PayRuleField.None -> template.label
        PayRuleField.Hours -> str(S.desktop_ad_after_n_hours, rule.formValue.ifBlank { "0" })
        PayRuleField.Clock -> str(S.desktop_ad_at_clock, rule.formValue)
        PayRuleField.DayKinds -> rule.dayKinds.joinToString(" / ") { it.label }
            .ifBlank { template.label }
    }
}

/** The crew a distribution list may name. */
internal fun AdUiState.distributionCandidates(): List<AdCrewMember> = emptyList()

/** One distribution row from a crew member. */
internal fun AdCrewMember.asDistribution(): DistributionEntry = DistributionEntry(
    kind = "user",
    ref = userId,
    name = fullName,
    email = email,
)

/** The agencies a vendor list offers, by name. */
internal fun List<Agency>.sortedForPicker(): List<Agency> = sortedBy { it.name }

private val AMOUNT_WIDTH = 120.dp
private val MINUTES_WIDTH = 120.dp
private val NOMINAL_WIDTH = 170.dp
private val STATUS_WIDTH = 120.dp
private val ACTION_WIDTH = 80.dp
