package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.NonUnionPay
import com.zillit.desktop.feature.accounthub.domain.PayApplyMode
import com.zillit.desktop.feature.accounthub.domain.PayDayKind
import com.zillit.desktop.feature.accounthub.domain.PayRateBasis
import com.zillit.desktop.feature.accounthub.domain.PayRateType
import com.zillit.desktop.feature.accounthub.domain.PayRule
import com.zillit.desktop.feature.accounthub.domain.PayRuleField
import com.zillit.desktop.feature.accounthub.domain.PayRuleKind
import com.zillit.desktop.feature.accounthub.domain.PayRuleTemplate
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.SetupSection

private const val LABEL_WIDTH = 190
private const val AMOUNT_WIDTH = 100
private const val FIELD_WIDTH = 130
private const val NOMINAL_WIDTH = 110

/**
 * The production's own overtime, premium and penalty rules.
 *
 * Non-union productions have no agreement to read these from, so this is where
 * they are set — and the engine consumes them through the same shape it reads
 * a union agreement's, which is why the wire values here look the way they do.
 *
 * Conditions are picked from a catalogue rather than written. The web shipped
 * a raw-JSON editor here and withdrew it as too easy to get wrong, and the
 * argument is stronger on this screen than most: a condition that is subtly
 * wrong does not fail, it pays somebody the wrong amount.
 */
@Composable
internal fun NonUnionPaySection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val section = state.setup.nonUnionPay
    val value = section.edited
    val editable = state.viewer.canEdit

    SetupSectionCard(
        title = "Non-Union Pay Breakdown",
        description = "The overtime, premium and penalty rules a non-union deal is paid by.",
        dirty = section.dirty,
        saving = section.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.NonUnionPay)) },
        onRevert = { onEvent(AccountHubEvent.RevertSection(SetupSection.NonUnionPay)) },
        editable = editable,
    ) {
        ApplyScope(state, value, editable, onEvent)
        PayRuleKind.entries.forEach { kind ->
            RuleList(kind, value, editable, onEvent)
        }
    }
}

/**
 * Who every rule in this breakdown pays.
 *
 * Section-level, not per rule, and the first thing on the card because it
 * changes what all of it means. Neither choice reads as picked until somebody
 * picks one: the server's own pristine state is "not chosen", which the engine
 * treats as everybody, and showing "Everyone" as already selected would claim
 * a decision nobody made.
 */
@Composable
private fun ColumnScope.ApplyScope(
    state: AccountHubUiState,
    value: NonUnionPay,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    ZillitSectionLabel("Apply these rules to")
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitButton(
            text = PayApplyMode.All.label,
            onClick = { onEvent(AccountHubEvent.ApplyPayToEveryone(true)) },
            variant = if (value.applyMode == PayApplyMode.All) {
                ButtonVariant.Primary
            } else {
                ButtonVariant.Tertiary
            },
            size = ButtonSize.Small,
            enabled = editable,
        )
        ZillitButton(
            text = PayApplyMode.Departments.label,
            onClick = { onEvent(AccountHubEvent.ApplyPayToEveryone(false)) },
            variant = if (value.applyMode == PayApplyMode.Departments) {
                ButtonVariant.Primary
            } else {
                ButtonVariant.Tertiary
            },
            size = ButtonSize.Small,
            enabled = editable,
        )
    }

    when {
        value.applyMode == PayApplyMode.Unset -> ZillitText(
            text = "Nobody has chosen yet, which pays every department — the same as choosing " +
                "everyone. Pick one to say so on the record.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )

        value.applyMode == PayApplyMode.Departments -> DepartmentPicker(state, value, editable, onEvent)
    }

    if (value.appliesToNobody) {
        ZillitNotice(
            text = "No department is chosen, so these rules pay nobody. Pick at least one, or " +
                "apply them to everyone.",
            tone = StatusTone.Rejected,
            icon = ZillitIcons.Warning,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The departments the rules reach.
 *
 * A stored id the host could not name is still listed and still checked — the
 * scope is the production's, and hiding a department because this window has
 * no name for it would drop it on the next save.
 */
@Composable
private fun ColumnScope.DepartmentPicker(
    state: AccountHubUiState,
    value: NonUnionPay,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val known = state.setup.departments
    val ids = (known.keys + value.departmentIds).distinct().sortedBy { known[it] ?: it }

    if (ids.isEmpty()) {
        ZillitText(
            text = "No departments to choose from on this production.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        return
    }

    ids.forEach { id ->
        ZillitCheckbox(
            checked = id in value.departmentIds,
            onCheckedChange = { on -> onEvent(AccountHubEvent.TogglePayDepartment(id, on)) },
            label = known[id] ?: id,
            enabled = editable,
        )
    }
}

@Composable
private fun RuleList(
    kind: PayRuleKind,
    value: NonUnionPay,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val rules = value.rulesFor(kind)

    ZillitSectionLabel(kind.label)
    ZillitText(
        text = kind.helper,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
    if (rules.isEmpty()) EmptyLine("No ${kind.label.lowercase()} yet.")
    rules.forEachIndexed { index, rule ->
        PayRuleRow(
            rule = rule,
            kind = kind,
            editable = editable,
            onChange = { next ->
                onEvent(
                    AccountHubEvent.EditNonUnionPay(
                        value.withRules(kind, rules.mapIndexed { i, r -> if (i == index) next else r }),
                    ),
                )
            },
            onRemove = {
                onEvent(
                    AccountHubEvent.EditNonUnionPay(
                        value.withRules(kind, rules.filterIndexed { i, _ -> i != index }),
                    ),
                )
            },
        )
    }
    if (editable) {
        ZillitButton(
            text = "Add ${kind.label.dropLast(1).lowercase()}",
            onClick = {
                onEvent(AccountHubEvent.EditNonUnionPay(value.withRules(kind, rules + newRule(kind, rules.size))))
            },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Add,
        )
    }
}

/** A new rule starts on its list's usual condition, with that template's rate. */
private fun newRule(kind: PayRuleKind, at: Int): PayRule {
    val template = PayRuleTemplate.defaultFor(kind)
    return PayRule(
        id = "${kind.wire}-new-$at",
        label = template.label,
        rateType = template.defaultRateType,
        rateAmount = template.defaultRateAmount,
        basis = template.defaultBasis,
        triggers = listOf(template.trigger(hours = "", clock = "", dayKinds = emptyList(), carrying = PayTriggerEmpty)),
    )
}

private val PayTriggerEmpty = com.zillit.desktop.feature.accounthub.domain.PayTrigger()

@Composable
private fun PayRuleRow(
    rule: PayRule,
    kind: PayRuleKind,
    editable: Boolean,
    onChange: (PayRule) -> Unit,
    onRemove: () -> Unit,
) {
    val trigger = rule.singleTrigger
    val template = PayRuleTemplate.of(trigger)

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitTextField(
                value = rule.label,
                onValueChange = { onChange(rule.copy(label = it)) },
                label = "Name",
                enabled = editable,
                modifier = Modifier.width(LABEL_WIDTH.dp),
            )
            ZillitSelect(
                value = rule.rateType,
                options = PayRateType.entries,
                onSelect = { onChange(rule.copy(rateType = it)) },
                label = { it.label },
                enabled = editable,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = rule.rateAmount,
                onValueChange = { onChange(rule.copy(rateAmount = it)) },
                label = "Amount",
                enabled = editable,
                modifier = Modifier.width(AMOUNT_WIDTH.dp),
            )
            ZillitSelect(
                value = rule.basis,
                options = PayRateBasis.entries,
                onSelect = { onChange(rule.copy(basis = it)) },
                label = { it.label },
                enabled = editable,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = rule.nominalCode,
                onValueChange = { onChange(rule.copy(nominalCode = it)) },
                label = "Nominal",
                enabled = editable,
                modifier = Modifier.width(NOMINAL_WIDTH.dp),
            )
            if (editable) {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Remove ${rule.label.ifBlank { "this rule" }}",
                    onClick = onRemove,
                )
            }
        }

        ConditionRow(rule, kind, template, editable, onChange)
    }
}

/**
 * What fires the rule.
 *
 * A rule whose stored condition matches no template keeps it and says so
 * rather than being quietly re-tagged: picking a template here would overwrite
 * a condition somebody agreed, and a rule the engine already honours is not
 * this screen's to guess at.
 */
@Composable
private fun ConditionRow(
    rule: PayRule,
    kind: PayRuleKind,
    template: PayRuleTemplate?,
    editable: Boolean,
    onChange: (PayRule) -> Unit,
) {
    if (rule.triggers.size > 1 || (rule.singleTrigger != null && template == null)) {
        ZillitNotice(
            text = "This rule's condition was not set here and is left as it is. Choosing " +
                "one below would replace it.",
            tone = StatusTone.Neutral,
            icon = ZillitIcons.Info,
        )
    }
    val current = template ?: PayRuleTemplate.defaultFor(kind)
    val trigger = rule.singleTrigger ?: PayTriggerEmpty

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSelect(
            value = current,
            options = PayRuleTemplate.entries,
            onSelect = { next ->
                onChange(
                    rule.copy(
                        triggers = listOf(
                            next.trigger(
                                hours = next.hoursFrom(trigger),
                                clock = next.clockFrom(trigger),
                                dayKinds = trigger.dayKinds,
                                carrying = trigger,
                            ),
                        ),
                    ),
                )
            },
            label = { "${it.group} · ${it.label}" },
            enabled = editable,
            modifier = Modifier.weight(1f),
        )
        ConditionField(current, trigger, editable) { hours, clock, kinds ->
            onChange(rule.copy(triggers = listOf(current.trigger(hours, clock, kinds, trigger))))
        }
    }
    ZillitText(
        text = current.helper,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
}

@Composable
private fun ConditionField(
    template: PayRuleTemplate,
    trigger: com.zillit.desktop.feature.accounthub.domain.PayTrigger,
    editable: Boolean,
    onChange: (hours: String, clock: String, kinds: List<PayDayKind>) -> Unit,
) {
    when (template.field) {
        PayRuleField.None -> Unit
        PayRuleField.Hours -> ZillitTextField(
            value = template.hoursFrom(trigger),
            onValueChange = { onChange(it, "", trigger.dayKinds) },
            label = "Hours",
            enabled = editable,
            modifier = Modifier.width(FIELD_WIDTH.dp),
        )
        PayRuleField.Clock -> ZillitTextField(
            value = template.clockFrom(trigger),
            onValueChange = { onChange("", it, trigger.dayKinds) },
            label = "Time (HH:MM)",
            enabled = editable,
            modifier = Modifier.width(FIELD_WIDTH.dp),
        )
        PayRuleField.DayKinds -> DayKindPicker(trigger.dayKinds, editable) {
            onChange("", "", it)
        }
    }
}

@Composable
private fun DayKindPicker(
    chosen: List<PayDayKind>,
    editable: Boolean,
    onChange: (List<PayDayKind>) -> Unit,
) {
    Column {
        PayDayKind.entries.forEach { kind ->
            ZillitCheckbox(
                checked = kind in chosen,
                onCheckedChange = { on -> onChange(if (on) chosen + kind else chosen - kind) },
                label = kind.label,
                enabled = editable,
            )
        }
    }
}
