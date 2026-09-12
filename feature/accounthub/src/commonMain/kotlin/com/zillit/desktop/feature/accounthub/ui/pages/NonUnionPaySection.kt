package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSegmented
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitTab
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
import com.zillit.desktop.feature.accounthub.domain.PayTrigger
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.SetupSection
import com.zillit.desktop.feature.accounthub.ui.asAmountText
import com.zillit.desktop.feature.accounthub.ui.components.CalcField
import com.zillit.desktop.feature.accounthub.ui.components.CoaCodeField
import com.zillit.desktop.feature.accounthub.ui.components.quickCreateHandler
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.FieldLabel
import com.zillit.desktop.feature.accounthub.ui.components.GhostAddButton
import com.zillit.desktop.feature.accounthub.ui.components.HoverRow
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect
import com.zillit.desktop.feature.accounthub.ui.components.Pill
import com.zillit.desktop.feature.accounthub.ui.components.SectionShell
import com.zillit.desktop.feature.accounthub.ui.components.SubCard
import com.zillit.desktop.feature.accounthub.ui.components.ToggleRow

/**
 * The production's own overtime, premium and penalty rules — the web's
 * `NonUnionPayBreakdownSection`.
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
internal fun ColumnScope.NonUnionPaySection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val section = state.setup.nonUnionPay
    val value = section.edited
    val editable = state.viewer.canEdit

    SectionShell(
        title = "Non-Union Pay Breakdown",
        description = "Overtime / premium / penalty rules for non-union productions. Same shape as union rate cards, " +
            "so the OT engine reads either source uniformly.",
        dirty = section.dirty,
        saving = section.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.NonUnionPay)) },
        onCancel = { onEvent(AccountHubEvent.RevertSection(SetupSection.NonUnionPay)) },
        editable = editable,
    ) {
        ApplyScope(state, value, editable, onEvent)
        DayTypesEditor(state, onEvent)
        PayRuleKind.entries.forEach { kind -> RuleList(kind, value, editable, state, onEvent) }
    }

    PayRuleDialog(state, onEvent)
    DepartmentPickerDialog(state, value, onEvent)
}

/**
 * Who every rule in this breakdown pays — the web's two option cards.
 *
 * Section-level, not per rule, and the first thing on the card because it
 * changes what all of it means. Neither card reads as picked until somebody
 * picks one: the server's own pristine state is "not chosen", which the engine
 * treats as everybody, and showing "All Departments" as already selected
 * would claim a decision nobody made.
 */
@Composable
private fun ApplyScope(
    state: AccountHubUiState,
    value: NonUnionPay,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val known = state.setup.departments
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        ScopeCard(
            title = "All Departments / Crew",
            subtitle = "Rules apply to every department",
            active = value.applyMode == PayApplyMode.All,
            enabled = editable,
            onClick = { onEvent(AccountHubEvent.ApplyPayToEveryone(true)) },
            modifier = Modifier.weight(1f),
        )
        ScopeCard(
            title = "Select Department",
            subtitle = when {
                value.applyMode != PayApplyMode.Departments -> "Pick the departments these rules pay"
                value.departmentIds.isEmpty() -> "No departments chosen"
                else -> value.departmentIds.map { known[it] ?: it }.joinToString(", ")
            },
            active = value.applyMode == PayApplyMode.Departments,
            enabled = editable,
            onClick = {
                onEvent(AccountHubEvent.ApplyPayToEveryone(false))
                onEvent(AccountHubEvent.ToggleDepartmentPicker(true))
            },
            modifier = Modifier.weight(1f),
        )
    }
    if (value.applyMode == PayApplyMode.Unset) {
        FieldHint("Nobody has chosen yet, which pays every department — the same as choosing everyone.")
    }
    if (value.appliesToNobody) {
        ZillitNotice(
            text = "No department is chosen, so these rules pay nobody. Pick at least one, or apply them to everyone.",
            tone = StatusTone.Rejected,
            icon = ZillitIcons.Warning,
        )
    }
}

@Composable
private fun ScopeCard(
    title: String,
    subtitle: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(if (active) colors.accentSoft else colors.surface)
            .border(if (active) 2.dp else 1.dp, if (active) colors.accent else colors.border, ZillitTheme.shapes.large)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = title,
            style = ZillitTheme.typography.titleSmall,
            color = if (active) colors.accentText else colors.textPrimary,
        )
        FieldHint(subtitle)
    }
}

/** "Select departments" — the web's picker modal with a search box. */
@Composable
private fun DepartmentPickerDialog(state: AccountHubUiState, value: NonUnionPay, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val known = setup.departments
    val ids = (known.keys + value.departmentIds).distinct().sortedBy { known[it] ?: it }
    val shown = ids.filter {
        setup.departmentPickerSearch.isBlank() || (known[it] ?: it).contains(setup.departmentPickerSearch, true)
    }
    ZillitDialogShell(
        title = "Select departments",
        subtitle = "${value.departmentIds.size} chosen",
        icon = ZillitIcons.Users,
        visible = setup.departmentPickerOpen,
        onDismiss = { onEvent(AccountHubEvent.ToggleDepartmentPicker(false)) },
        scrollable = false,
        actions = {
            ZillitButton(text = "Done", onClick = { onEvent(AccountHubEvent.ToggleDepartmentPicker(false)) })
        },
    ) {
        ZillitSearchField(
            value = setup.departmentPickerSearch,
            onValueChange = { onEvent(AccountHubEvent.SearchDepartmentPicker(it)) },
            placeholder = "Search departments…",
            modifier = Modifier.fillMaxWidth(),
        )
        if (ids.isEmpty()) FieldHint("No departments to choose from on this production.")
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = PICKER_LIST).verticalScroll(rememberScrollState())) {
            shown.forEach { id ->
                ZillitCheckbox(
                    checked = id in value.departmentIds,
                    onCheckedChange = { on -> onEvent(AccountHubEvent.TogglePayDepartment(id, on)) },
                    // A stored id the host could not name is still listed and
                    // still checked — hiding it would drop it on the next save.
                    label = known[id] ?: id,
                    enabled = state.viewer.canEdit,
                    modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
                )
            }
        }
    }
}

// -- the three lists -------------------------------------------------------------

@Composable
private fun RuleList(
    kind: PayRuleKind,
    value: NonUnionPay,
    editable: Boolean,
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val rules = value.rulesFor(kind)
    SubCard(
        title = kind.label,
        hint = kind.helper,
        action = {
            if (editable) GhostAddButton("Add rule", onClick = { onEvent(AccountHubEvent.ComposePayRule(kind, null)) })
        },
        padded = false,
    ) {
        if (rules.isEmpty()) {
            FieldHint(
                "No ${kind.label.lowercase()} yet — add the first one.",
                Modifier.padding(ZillitTheme.spacing.lg),
            )
        }
        rules.forEachIndexed { index, rule ->
            HoverRow(
                modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
                onClick = if (editable) ({ onEvent(AccountHubEvent.ComposePayRule(kind, index)) }) else null,
                actions = { hovered ->
                    if (editable && hovered) {
                        ZillitIconButton(
                            icon = ZillitIcons.Edit,
                            contentDescription = "Edit rule",
                            onClick = { onEvent(AccountHubEvent.ComposePayRule(kind, index)) },
                        )
                        ZillitIconButton(
                            icon = ZillitIcons.Trash,
                            contentDescription = "Remove rule",
                            onClick = { onEvent(AccountHubEvent.RemovePayRule(kind, index)) },
                            tint = ZillitTheme.colors.danger,
                        )
                    }
                },
            ) {
                RuleSummary(rule, state, Modifier.weight(1f))
            }
        }
    }
}

/** One rule as the web's row prints it: name, rate, the condition in words, and chips for its gates. */
@Suppress("CyclomaticComplexMethod") // One branch per pay-rule template.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleSummary(rule: PayRule, state: AccountHubUiState, modifier: Modifier = Modifier) {
    val template = PayRuleTemplate.of(rule.singleTrigger)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = rule.label.ifBlank { "Unnamed rule" },
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Pill(rateLabel(rule), tone = StatusTone.Pending)
            if (rule.isEnhancement) Pill("Basic + OT on top", tone = StatusTone.Progress)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            FieldHint(summarise(rule, template))
            rule.dayType.takeIf { it.isNotBlank() }?.let { code ->
                val label = state.setup.dayTypes.edited.firstOrNull { it.dayType == code }
                    ?.label?.ifBlank { code } ?: code
                FieldHint("· day type $label")
            }
            rule.incrementMinutes?.let { FieldHint("· billed in $it-minute increments") }
            if (rule.capped && rule.capAmount.isNotBlank()) FieldHint("· capped at ${rule.capAmount}")
            if (rule.bdrMin != null || rule.bdrMax != null) FieldHint("· BDR " +
                "${rule.bdrMin ?: "…"}–${rule.bdrMax ?: "…"}")
            if (rule.nominalCode.isNotBlank()) FieldHint("· nominal ${rule.nominalCode}")
        }
    }
}

private fun rateLabel(rule: PayRule): String = when (rule.rateType) {
    PayRateType.Multiplier -> "×${rule.rateAmount.ifBlank { "?" }} ${rule.basis.label.lowercase()}"
    PayRateType.Flat -> "${rule.rateAmount.ifBlank { "?" }} flat · ${rule.basis.label.lowercase()}"
    PayRateType.Percentage -> "${rule.rateAmount.ifBlank { "?" }}% · ${rule.basis.label.lowercase()}"
}

/** The condition in words — the web's `summarizeEntry`. */
private fun summarise(rule: PayRule, template: PayRuleTemplate?): String {
    val trigger = rule.singleTrigger
        ?: return if (rule.triggers.isEmpty()) "No condition" else "${rule.triggers.size} conditions (any)"
    if (template == null) return "Custom condition"
    val detail = when (template.field) {
        PayRuleField.Hours -> template.hoursFrom(trigger).takeIf { it.isNotBlank() }?.let { "$it hrs" }
        PayRuleField.Clock -> template.clockFrom(trigger).takeIf { it.isNotBlank() }
        PayRuleField.DayKinds -> trigger.dayKinds.joinToString(", ") { it.label }.takeIf { it.isNotBlank() }
        PayRuleField.None -> null
    }
    return listOfNotNull(template.label, detail).joinToString(" · ")
}

// -- the rule editor ----------------------------------------------------------------

/**
 * Adding or editing one rule — the web's `RateRowModal`.
 *
 * Rule type, name, rate type, amount, base rate, the condition's own field,
 * the day type, "Bill in increments", "Cap maximum payout", the min/max
 * basic-daily-rate gate, "Basic + OT on top", the nominal and notes — every
 * field the grid shows, in the web's order and with its hints.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // One form, in the order the web lays it out.
@Composable
private fun PayRuleDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val editor = state.setup.ruleEditor
    val rule = editor?.rule
    val trigger = rule?.singleTrigger ?: PayTrigger()
    val template = PayRuleTemplate.of(rule?.singleTrigger) ?: editor?.let { PayRuleTemplate.defaultFor(it.kind) }
    fun update(next: PayRule) = onEvent(AccountHubEvent.EditPayRule(next))

    ZillitDialogShell(
        title = if (editor?.index == null) "Add rule" else "Edit rule",
        subtitle = editor?.kind?.label,
        icon = ZillitIcons.Ledger,
        visible = editor != null,
        onDismiss = { onEvent(AccountHubEvent.DismissPayRule) },
        width = DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.DismissPayRule) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (editor?.index == null) "Add rule" else "Update rule",
                onClick = { onEvent(AccountHubEvent.CommitPayRule) },
            )
        },
    ) {
        if (editor == null || rule == null || template == null) return@ZillitDialogShell

        FieldLabel("Rule type", required = true)
        HubSelect(
            value = template,
            options = PayRuleTemplate.entries.toList(),
            label = { "${it.group} · ${it.label}" },
            onSelect = { next ->
                if (next != null) {
                    update(
                        rule.copy(
                            label =
                                if (rule.label.isBlank() || rule.label == template.label) next.label else rule.label,
                            rateType = if (rule.rateAmount.isBlank()) next.defaultRateType else rule.rateType,
                            rateAmount = rule.rateAmount.ifBlank { next.defaultRateAmount },
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
                }
            },
            searchable = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FieldHint(template.helper)
        if (rule.triggers.size > 1) {
            ZillitNotice(
                text = "This rule has several conditions (any of them fires it). Choosing a type above replaces " +
                    "them with one.",
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Info,
            )
        }

        ZillitTextField(
            value = rule.label,
            onValueChange = { update(rule.copy(label = it)) },
            label = "Name",
            placeholder = template.label,
        )

        FieldLabel("Rate type")
        ZillitSegmented(
            options = PayRateType.entries.map { ZillitTab(it.wire, it.label) },
            activeId = rule.rateType.wire,
            onSelect = { wire ->
                PayRateType.entries.firstOrNull { it.wire == wire }?.let { update(rule.copy(rateType = it)) }
            },
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            CalcField(
                value = rule.rateAmount,
                onValueChange = { update(rule.copy(rateAmount = it)) },
                label = "Amount",
                placeholder = "100",
                modifier = Modifier.weight(1f),
            )
            ZillitSelect(
                value = rule.basis,
                options = PayRateBasis.entries,
                onSelect = { update(rule.copy(basis = it)) },
                label = { it.label },
                modifier = Modifier.weight(1f),
            )
        }

        ConditionField(template, trigger) { hours, clock, kinds ->
            update(rule.copy(triggers = listOf(template.trigger(hours, clock, kinds, trigger))))
        }

        val dayTypes = state.setup.dayTypes.edited
        HubSelect(
            value = dayTypes.firstOrNull { it.dayType == rule.dayType },
            options = dayTypes,
            label = { "${it.dayType} · ${it.label}".trimEnd(' ', '·') },
            onSelect = { update(rule.copy(dayType = it?.dayType.orEmpty())) },
            placeholder = "Any day type",
            fieldLabel = "Day type",
            clearable = true,
            searchable = false,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldLabel("Bill in increments")
        FieldHint("Round matched windows up to a multiple of N minutes (15 = UK, 6 = US union).")
        ZillitTextField(
            value = rule.incrementMinutes?.toString().orEmpty(),
            onValueChange = { text ->
                val minutes = text.filter { it.isDigit() }.toIntOrNull()
                update(rule.withTriggerGates(minutes, rule.bdrMin, rule.bdrMax))
            },
            placeholder = "Minutes — e.g. 15",
        )

        ToggleRow(
            label = "Cap maximum payout",
            hint = "Clip the computed payout to this ceiling per matched window.",
            checked = rule.capped,
            onCheckedChange = { update(rule.copy(capped = it)) },
        )
        if (rule.capped) {
            CalcField(
                value = rule.capAmount,
                onValueChange = { update(rule.copy(capAmount = it)) },
                placeholder = "Cap amount — e.g. 500",
            )
        }

        FieldLabel("For Min/Max Basic Daily Rate")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            CalcField(
                value = rule.bdrMin?.let { it.asAmountText() }.orEmpty(),
                onValueChange = { update(rule.withTriggerGates(
                    rule.incrementMinutes,
                    it.toDoubleOrNull(),
                    rule.bdrMax,
                )) },
                placeholder = "Min BDR",
                modifier = Modifier.weight(1f),
            )
            CalcField(
                value = rule.bdrMax?.let { it.asAmountText() }.orEmpty(),
                onValueChange = { update(rule.withTriggerGates(
                    rule.incrementMinutes,
                    rule.bdrMin,
                    it.toDoubleOrNull(),
                )) },
                placeholder = "Max BDR",
                modifier = Modifier.weight(1f),
            )
        }

        ToggleRow(
            label = "Basic + OT on Top",
            hint = "Pay this amount over the basic rate rather than in place of it.",
            checked = rule.isEnhancement,
            onCheckedChange = { update(rule.copy(isEnhancement = it)) },
        )

        CoaCodeField(
            value = rule.nominalCode,
            onValueChange = { update(rule.copy(nominalCode = it)) },
            accounts = state.chart.accounts,
            label = "Nominal",
            placeholder = "e.g. 4421",
            onCreate = quickCreateHandler(state, onEvent),
        )
        ZillitTextField(
            value = rule.note,
            onValueChange = { update(rule.copy(note = it)) },
            label = "Notes",
            placeholder = "Statute reference, edge cases, etc.",
            singleLine = false,
        )
    }
}

@Composable
private fun ConditionField(
    template: PayRuleTemplate,
    trigger: PayTrigger,
    onChange: (hours: String, clock: String, kinds: List<PayDayKind>) -> Unit,
) {
    when (template.field) {
        PayRuleField.None -> Unit
        PayRuleField.Hours -> ZillitTextField(
            value = template.hoursFrom(trigger),
            onValueChange = { onChange(it, "", trigger.dayKinds) },
            label = "Trigger",
            placeholder = "hours",
            modifier = Modifier.width(FIELD_WIDTH),
        )
        PayRuleField.Clock -> ZillitTextField(
            value = template.clockFrom(trigger),
            onValueChange = { onChange("", it, trigger.dayKinds) },
            label = "Time (HH:MM)",
            placeholder = "05:00",
            modifier = Modifier.width(FIELD_WIDTH),
        )
        PayRuleField.DayKinds -> {
            FieldLabel("Day kinds")
            Column {
                PayDayKind.entries.forEach { kind ->
                    ZillitCheckbox(
                        checked = kind in trigger.dayKinds,
                        onCheckedChange = { on -> onChange(
                            "",
                            "",
                            if (on) trigger.dayKinds + kind else trigger.dayKinds - kind,
                        ) },
                        label = kind.label,
                    )
                }
            }
        }
    }
}

private val DIALOG_WIDTH = 620.dp
private val FIELD_WIDTH = 160.dp
private val PICKER_LIST = 320.dp
