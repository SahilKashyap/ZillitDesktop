package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.DealCondition
import com.zillit.desktop.feature.accounthub.domain.PayrollBureau
import com.zillit.desktop.feature.accounthub.domain.ScheduleRules
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.CustomDayText
import com.zillit.desktop.feature.accounthub.ui.DateRangeText
import com.zillit.desktop.feature.accounthub.ui.SetupSection
import com.zillit.desktop.feature.accounthub.ui.components.DateField
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.FieldLabel
import com.zillit.desktop.feature.accounthub.ui.components.GhostAddButton
import com.zillit.desktop.feature.accounthub.ui.components.SectionShell
import com.zillit.desktop.feature.accounthub.ui.components.ToggleRow

// -- schedule ---------------------------------------------------------------

/**
 * The production schedule — the web's `ProductionScheduleSection`.
 *
 * Deal dates, then prep / shoot / wrap with the web's chaining: each phase's
 * earliest date follows the one before, the overlap and boundary rules are
 * shown per row in the web's words, and the custom overlays sit below with
 * no overlap rule of their own — "Night Shoot" runs inside the shoot by design.
 */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
internal fun ScheduleSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val schedule = setup.schedule.edited
    val editable = state.viewer.canEdit
    val errors = ScheduleRules.errors(schedule.toDomain())
    fun update(next: com.zillit.desktop.feature.accounthub.ui.ScheduleForm) =
        onEvent(AccountHubEvent.EditSchedule(next))

    SectionShell(
        title = "Production Schedule",
        description = "Set Production Schedules",
        dirty = setup.schedule.dirty,
        saving = setup.schedule.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.Schedule)) },
        onCancel = { onEvent(AccountHubEvent.RevertSection(SetupSection.Schedule)) },
        editable = editable,
    ) {
        DateRangeRow("Deal dates", schedule.overall, editable, errors[ScheduleRules.OVERALL]) {
            update(schedule.copy(overall = it))
        }
        DateRangeRow(
            "Prep",
            schedule.prep,
            editable,
            errors[ScheduleRules.PREP],
            min = schedule.overall.from,
            max = schedule.overall.to,
        ) {
            update(schedule.copy(prep = it))
        }
        DateRangeRow(
            "Shoot",
            schedule.shoot,
            editable,
            errors[ScheduleRules.SHOOT],
            min = schedule.prep.to.ifBlank { schedule.overall.from },
            max = schedule.overall.to,
        ) {
            update(schedule.copy(shoot = it))
        }
        DateRangeRow(
            "Wrap",
            schedule.wrap,
            editable,
            errors[ScheduleRules.WRAP],
            min = schedule.shoot.to.ifBlank { schedule.overall.from },
            max = schedule.overall.to,
        ) {
            update(schedule.copy(wrap = it))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FieldLabel("Custom days", modifier = Modifier.weight(1f))
            if (editable) {
                GhostAddButton("Add custom day", onClick = {
                    update(
                        schedule.copy(
                            customDays = schedule.customDays + CustomDayText(
                                id = "custom-${schedule.customDays.size}-${schedule.customDays.hashCode()}",
                            ),
                        ),
                    )
                })
            }
        }
        FieldHint("Named overlays on the schedule — Night Shoot, Second Unit. They may sit inside any phase.")
        schedule.customDays.forEachIndexed { index, day ->
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitTextField(
                        value = day.name,
                        onValueChange = { text ->
                            update(
                                schedule.copy(
                                    customDays = schedule.customDays.mapIndexed { i, d ->
                                        if (i == index) d.copy(name = text) else d
                                    },
                                ),
                            )
                        },
                        placeholder = "e.g. Night Shoot",
                        enabled = editable,
                        modifier = Modifier.weight(1f),
                    )
                    DateField(
                        value = day.dates.from,
                        onValueChange = { text ->
                            update(
                                schedule.copy(
                                    customDays = schedule.customDays.mapIndexed { i, d ->
                                        if (i == index) d.copy(dates = d.dates.copy(from = text)) else d
                                    },
                                ),
                            )
                        },
                        label = "From",
                        enabled = editable,
                        modifier = Modifier.width(DATE_WIDTH),
                    )
                    DateField(
                        value = day.dates.to,
                        onValueChange = { text ->
                            update(
                                schedule.copy(
                                    customDays = schedule.customDays.mapIndexed { i, d ->
                                        if (i == index) d.copy(dates = d.dates.copy(to = text)) else d
                                    },
                                ),
                            )
                        },
                        label = "To",
                        enabled = editable,
                        modifier = Modifier.width(DATE_WIDTH),
                    )
                    if (editable) {
                        ZillitIconButton(
                            icon = ZillitIcons.Trash,
                            contentDescription = "Remove custom day",
                            onClick = {
                                update(
                                    schedule.copy(
                                        customDays = schedule.customDays.filterIndexed { i, _ -> i != index },
                                    ),
                                )
                            },
                        )
                    }
                }
                errors[day.id]?.forEach {
                    ZillitText(text = it, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger)
                }
            }
        }
    }
}

/**
 * One phase's two dates.
 *
 * Holds the typed text. Bound to the parsed epoch instead, a date could not be
 * entered at all: `"2026-09-0"` parses to null, so the field emptied itself on
 * every keystroke.
 */
@Composable
private fun DateRangeRow(
    label: String,
    dates: DateRangeText,
    editable: Boolean,
    errors: List<String>?,
    min: String? = null,
    max: String? = null,
    onChange: (DateRangeText) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(text = label, modifier = Modifier.weight(WEIGHT_LABEL))
            DateField(
                value = dates.from,
                onValueChange = { onChange(dates.copy(from = it)) },
                label = "From",
                enabled = editable,
                min = min?.takeIf { it.isNotBlank() },
                max = max?.takeIf { it.isNotBlank() },
                modifier = Modifier.weight(1f),
            )
            DateField(
                value = dates.to,
                onValueChange = { onChange(dates.copy(to = it)) },
                label = "To",
                enabled = editable,
                min = dates.from.takeIf { it.isNotBlank() } ?: min?.takeIf { it.isNotBlank() },
                max = max?.takeIf { it.isNotBlank() },
                modifier = Modifier.weight(1f),
            )
        }
        errors?.forEach {
            ZillitText(text = it, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger)
        }
    }
}

// -- standard deal conditions -----------------------------------------------

/**
 * The clauses every new deal memo starts with — the web's `StandardDealConditionsSection`.
 *
 * The web reorders by drag; here the arrows do the same job. Order is
 * positional and rebuilt on save, so the numbers shown are the ones that go.
 */
@Composable
internal fun DealConditionsSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val section = state.setup.dealConditions
    val conditions = section.edited
    val editable = state.viewer.canEdit

    SectionShell(
        title = "Standard Deal Conditions",
        description = "Default clauses inserted into the 'Terms & Conditions' step of every new deal memo. Crew can " +
            "override per-memo.",
        dirty = section.dirty,
        saving = section.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.DealConditions)) },
        onCancel = { onEvent(AccountHubEvent.RevertSection(SetupSection.DealConditions)) },
        editable = editable,
    ) {
        if (conditions.isEmpty()) EmptyLine("No standard conditions yet.")
        conditions.forEachIndexed { index, condition ->
            ConditionRow(
                index = index,
                condition = condition,
                editable = editable,
                last = index == conditions.lastIndex,
                onChange = { next ->
                    onEvent(AccountHubEvent.EditDealConditions(conditions.toMutableList().also { it[index] = next }))
                },
                onMove = { delta -> onEvent(AccountHubEvent.EditDealConditions(conditions.moved(index, delta))) },
                onRemove = {
                    onEvent(AccountHubEvent.EditDealConditions(conditions.filterIndexed { at, _ -> at != index }))
                },
            )
        }
        if (!editable) return@SectionShell
        GhostAddButton(
            text = "Add condition",
            onClick = {
                onEvent(AccountHubEvent.EditDealConditions(conditions + DealCondition(
                    id = "cond-new-${conditions.size}",
                    order = conditions.size + 1,
                )))
            },
        )
    }
}

@Composable
private fun ConditionRow(
    index: Int,
    condition: DealCondition,
    editable: Boolean,
    last: Boolean,
    onChange: (DealCondition) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The position as it will be sent, not the stored `order` — a list
        // edited by delete arrives with gaps, and showing those gaps would
        // contradict what saving is about to write.
        ZillitText(
            text = "${index + 1}.",
            style = ZillitTheme.typography.numeric,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.width(ORDINAL_WIDTH.dp),
        )
        ZillitTextField(
            value = condition.condition,
            onValueChange = { onChange(condition.copy(condition = it)) },
            placeholder = "Clause text…",
            enabled = editable,
            singleLine = false,
            modifier = Modifier.weight(1f),
        )
        if (!editable) return@Row
        ZillitIconButton(
            icon = ZillitIcons.ChevronUp,
            contentDescription = "Move up",
            onClick = { onMove(-1) },
            enabled = index > 0,
        )
        ZillitIconButton(
            icon = ZillitIcons.ChevronDown,
            contentDescription = "Move down",
            onClick = { onMove(1) },
            enabled = !last,
        )
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = "Remove clause",
            onClick = onRemove,
            tint = ZillitTheme.colors.danger,
        )
    }
}

// -- payroll bureau -----------------------------------------------------------

@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
internal fun PayrollBureausSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val section = state.setup.payrollBureaus
    val bureaus = section.edited
    val editable = state.viewer.canEdit

    SectionShell(
        title = "Payroll Bureau",
        description = "Bureaus this production hands payroll off to. Each entry surfaces in the payroll-run picker " +
            "so " +
            "the coordinator can choose where to send the batch.",
        dirty = section.dirty,
        saving = section.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.PayrollBureaus)) },
        onCancel = { onEvent(AccountHubEvent.RevertSection(SetupSection.PayrollBureaus)) },
        editable = editable,
    ) {
        if (bureaus.isEmpty()) EmptyLine("No payroll bureaux yet.")
        bureaus.forEachIndexed { index, bureau ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitTextField(
                    value = bureau.title,
                    onValueChange = { text ->
                        onEvent(
                            AccountHubEvent.EditPayrollBureaus(
                                bureaus.toMutableList().also { it[index] = bureau.copy(title = text) },
                            ),
                        )
                    },
                    placeholder = "Bureau title (e.g. Sargent-Disc)",
                    enabled = editable,
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = bureau.description,
                    onValueChange = { text ->
                        onEvent(
                            AccountHubEvent.EditPayrollBureaus(
                                bureaus.toMutableList().also { it[index] = bureau.copy(description = text) },
                            ),
                        )
                    },
                    placeholder = "Description — what this bureau handles, contact, cadence…",
                    enabled = editable,
                    modifier = Modifier.weight(WEIGHT_WIDE),
                )
                if (editable) {
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = "Remove bureau",
                        onClick = {
                            onEvent(AccountHubEvent.EditPayrollBureaus(bureaus.filterIndexed { at, _ -> at != index }))
                        },
                        tint = ZillitTheme.colors.danger,
                    )
                }
            }
        }
        if (!editable) return@SectionShell
        GhostAddButton(
            "Add bureau",
            onClick = {
                onEvent(AccountHubEvent.EditPayrollBureaus(bureaus + PayrollBureau(id = "bureau-new-${bureaus.size}")))
            },
        )
    }
}

// -- payroll defaults -------------------------------------------------------

@Composable
internal fun PayrollDefaultsSection(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val setup = state.setup
    val defaults = setup.payrollDefaults.edited
    val editable = state.viewer.canEdit

    SectionShell(
        title = "Deal Memo — Payroll Defaults",
        description = "Project-wide payroll sync settings applied to every deal memo. Moved here from the per-deal " +
            "wizard.",
        dirty = setup.payrollDefaults.dirty,
        saving = setup.payrollDefaults.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.PayrollDefaults)) },
        onCancel = { onEvent(AccountHubEvent.RevertSection(SetupSection.PayrollDefaults)) },
        editable = editable,
    ) {
        ToggleRow(
            label = "Auto-sync signed deals to payroll",
            hint = "A signed deal memo is pushed to payroll without a manual step.",
            checked = defaults.autoSync,
            onCheckedChange = { onEvent(AccountHubEvent.EditPayrollDefaults(defaults.copy(autoSync = it))) },
            enabled = editable,
        )
        ToggleRow(
            label = "Notify payroll when a deal is signed",
            hint = "The payroll team is e-mailed on every signature.",
            checked = defaults.notifyPayroll,
            onCheckedChange = { onEvent(AccountHubEvent.EditPayrollDefaults(defaults.copy(notifyPayroll = it))) },
            enabled = editable,
        )
        ToggleRow(
            label = "Attach the signed PDF",
            hint = "The signed deal memo rides along with that notification.",
            checked = defaults.includePdf,
            onCheckedChange = { onEvent(AccountHubEvent.EditPayrollDefaults(defaults.copy(includePdf = it))) },
            enabled = editable,
        )
        if (!editable) {
            ZillitNotice(text = "Read-only for you.", tone = StatusTone.Neutral, icon = ZillitIcons.Info)
        }
    }
}

/**
 * Moves one entry by [delta], leaving the list alone if that would fall off
 * either end — so the buttons at the extremes are inert rather than wrong.
 */
internal fun <T> List<T>.moved(from: Int, delta: Int): List<T> {
    val to = from + delta
    if (from !in indices || to !in indices) return this
    return toMutableList().also { it.add(to, it.removeAt(from)) }
}

private const val ORDINAL_WIDTH = 24
private const val WEIGHT_LABEL = 0.6f
private const val WEIGHT_WIDE = 2f
private val DATE_WIDTH = 150.dp
