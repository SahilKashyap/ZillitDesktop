package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CoordinatorRules
import com.zillit.desktop.feature.cashexpenses.domain.DeductionRuleEdit
import com.zillit.desktop.feature.cashexpenses.domain.RuleProcess
import com.zillit.desktop.feature.cashexpenses.domain.RuleThreshold
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPerson
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import com.zillit.desktop.feature.cashexpenses.ui.SettingsEvent

/**
 * The coordinator row's department or people picker — the web's buffered
 * modal (`PCSettingsPage.jsx:851-927`): a search, the list, and the choice
 * applied only on Done.
 *
 * Departments already coordinated by another row are not offered; people are
 * everyone in the row's department.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // Both pickers share one shell and footer.
@Composable
internal fun CoordinatorPickerDialog(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val picker = state.settingsUi.coordPicker
    val people = LocalCashPeople.current
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = if (picker?.users == true) str(S.select_users) else str(S.select_department),
        visible = picker != null,
        onDismiss = { onEvent(SettingsEvent.CloseCoordinatorPicker) },
        width = PICKER_WIDTH,
        actions = {
            val count = when {
                picker == null -> 0
                picker.users -> picker.userIds.size
                picker.departmentId.isNotBlank() -> 1
                else -> 0
            }
            ZillitText(
                text = if (count == 0) str(S.dm_nda_none_selected) else str(S.dd_n_selected, count),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            Spacer(Modifier.weight(1f))
            ZillitButton(text = str(S.ah_done), onClick = { onEvent(SettingsEvent.ApplyCoordinatorPicker) })
        },
    ) {
        if (picker == null) return@ZillitDialogShell
        val rows = (state.settingsDraft ?: state.settings)?.departmentCoordinators.orEmpty()
        val query = picker.search.trim()
        ZillitSearchField(
            value = picker.search,
            onValueChange = { onEvent(SettingsEvent.EditCoordinatorPicker(picker.copy(search = it))) },
            placeholder = str(if (picker.users) S.desktop_pc_search_select_users else S.desktop_pc_search_departments),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!picker.users) {
            val options = CoordinatorRules.departmentsFor(picker.row, rows, state.departments)
                .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
            if (options.isEmpty()) QuietLine(str(S.desktop_drive_no_results_found))
            options.forEach { department ->
                val selected = picker.departmentId == department.id
                PickerRow(selected = selected, onClick = {
                    onEvent(SettingsEvent.EditCoordinatorPicker(picker.copy(departmentId = department.id)))
                }) {
                    ZillitText(
                        text = department.name,
                        style = ZillitTheme.typography.bodyMedium.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            val departmentId = rows.getOrNull(picker.row)?.departmentId.orEmpty()
            val options = state.assignees
                .filter { it.departmentId == departmentId }
                .filter {
                    query.isEmpty() || people.nameOf(it.userId, it.fullName).contains(query, ignoreCase = true) ||
                        it.designation.contains(query, ignoreCase = true)
                }
            if (options.isEmpty()) QuietLine(str(S.desktop_drive_no_results_found))
            options.forEach { person ->
                val selected = person.userId in picker.userIds
                PickerRow(selected = selected, onClick = {
                    val next = if (selected) picker.userIds - person.userId else picker.userIds + person.userId
                    onEvent(SettingsEvent.EditCoordinatorPicker(picker.copy(userIds = next)))
                }) {
                    CashPerson(
                        userId = person.userId,
                        recordedName = person.fullName,
                        secondary = person.designation.ifBlank { null },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerRow(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.accentSoft else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        content()
        if (selected) ZillitIcon(icon = ZillitIcons.Check, tint = colors.accent)
    }
}

/**
 * Add or edit a deduction rule — the web's rule modal
 * (`PCSettingsPage.jsx:1053-1229`). A shipped rule keeps its title and
 * description; anything but a deduction reads a minimum amount.
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod", "CyclomaticComplexMethod") // The modal, top to bottom.
@Composable
internal fun DeductionRuleDialog(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val edit = state.settingsUi.ruleEditor
    val symbol = state.defaultSymbol()
    ZillitDialogShell(
        title = if (edit?.isNew != false) str(S.desktop_pc_add_rule) else str(S.desktop_pc_edit_rule),
        visible = edit != null,
        onDismiss = { onEvent(SettingsEvent.UpdateRuleEditor(null)) },
        width = RULE_WIDTH,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = if (edit?.isNew != false) str(S.add) else str(S.update),
                onClick = { onEvent(SettingsEvent.CommitDeductionRule) },
                enabled = edit?.canCommit == true,
            )
        },
    ) {
        if (edit == null) return@ZillitDialogShell
        val rule = edit.rule
        fun update(next: DeductionRuleEdit) = onEvent(SettingsEvent.UpdateRuleEditor(next))

        FieldCaption(str(S.ce_note_title_label))
        ZillitTextField(
            value = rule.title,
            onValueChange = { update(edit.copy(rule = rule.copy(title = it))) },
            placeholder = str(S.desktop_pc_rule_title_placeholder),
            enabled = !rule.systemDefault,
            modifier = Modifier.fillMaxWidth(),
        )
        FieldCaption(str(S.description))
        ZillitTextField(
            value = rule.description,
            onValueChange = { update(edit.copy(rule = rule.copy(description = it))) },
            placeholder = str(S.desktop_pc_rule_desc_placeholder),
            enabled = !rule.systemDefault,
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        val deducts = rule.processType == RuleProcess.DeductAmount
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                FieldCaption(str(S.ah_process))
                ZillitSelect(
                    value = rule.processType,
                    options = RuleProcess.entries,
                    onSelect = { update(edit.withProcess(it)) },
                    label = { it.processLabel() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                FieldCaption(if (deducts) str(S.desktop_pc_deduct_by) else str(S.desktop_threshold))
                ZillitSelect(
                    value = rule.thresholdType,
                    options = if (deducts) RuleThreshold.entries else listOf(RuleThreshold.MinAmount),
                    onSelect = { update(edit.copy(rule = rule.copy(thresholdType = it))) },
                    label = { it.thresholdLabel(deducts) },
                    enabled = deducts,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                val percent = rule.thresholdType == RuleThreshold.Percentage
                FieldCaption(str(S.desktop_pc_value_unit, if (percent) "%" else symbol))
                ZillitTextField(
                    value = plainFigure(rule.thresholdValue),
                    onValueChange = { typed ->
                        update(edit.copy(rule = rule.copy(thresholdValue = typed.toDoubleOrNull() ?: 0.0)))
                    },
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSwitch(
                checked = rule.enabled,
                onCheckedChange = { update(edit.copy(rule = rule.copy(enabled = it))) },
            )
            ZillitText(text = str(S.dm_allow_enabled), style = ZillitTheme.typography.bodySmall)
        }
        FieldCaption(str(S.desktop_pc_trigger_codes))
        if (rule.triggerCodes.isEmpty()) {
            QuietLine(str(S.desktop_pc_no_triggers))
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                rule.triggerCodes.forEach { code ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ZillitText(text = code, style = ZillitTheme.typography.bodySmall)
                        ZillitIconButton(
                            icon = ZillitIcons.Close,
                            contentDescription = str(S.bs_chip_remove, code),
                            onClick = {
                                update(edit.copy(rule = rule.copy(triggerCodes = rule.triggerCodes - code)))
                            },
                        )
                    }
                }
            }
        }
        FieldCaption(str(S.desktop_pc_add_from_quick_codes))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            val codes = (state.settingsDraft ?: state.settings)?.quickCodes.orEmpty().filter { it.name.isNotBlank() }
            codes.forEach { code ->
                // A chip whose words are already triggers is spent, as the web greys it out.
                if (DeductionRuleEdit.alreadyAdded(rule, code)) {
                    ZillitTag(label = code.name)
                } else {
                    ZillitChoiceChip(
                        label = code.name,
                        selected = false,
                        onClick = { update(edit.withQuickCode(code)) },
                    )
                }
            }
        }
    }
}

private fun RuleProcess.processLabel(): String = when (this) {
    RuleProcess.DeductAmount -> str(S.desktop_pc_process_deduct)
    RuleProcess.SeniorReview -> str(S.desktop_ce_senior_review)
    RuleProcess.NeedQuery -> str(S.desktop_pc_process_query)
}

private fun RuleThreshold.thresholdLabel(deducts: Boolean): String = when {
    this == RuleThreshold.Percentage -> str(S.desktop_dm_percentage)
    deducts -> str(S.amount)
    else -> str(S.desktop_pc_min_amount)
}

private val PICKER_WIDTH = 480.dp
private val RULE_WIDTH = 560.dp
