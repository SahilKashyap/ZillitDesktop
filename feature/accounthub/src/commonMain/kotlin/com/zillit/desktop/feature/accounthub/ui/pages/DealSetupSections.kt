package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
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
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.DealCondition
import com.zillit.desktop.feature.accounthub.domain.PayrollBureau
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.SetupSection

/**
 * The two Deal Memo Setup sections whose data layer shipped without a screen.
 *
 * Standard deal conditions are the clauses every new deal memo starts with,
 * and the payroll bureau list is what a deal memo's payroll step picks from —
 * so an empty list here is not a cosmetic gap: it is a deal memo that cannot
 * be completed.
 */
@Composable
internal fun DealConditionsSection(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val section = state.setup.dealConditions
    val conditions = section.edited
    val editable = state.viewer.canEdit

    SetupSectionCard(
        title = "Standard Deal Conditions",
        description = "The clauses every new deal memo starts with, in the order they appear.",
        dirty = section.dirty,
        saving = section.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.DealConditions)) },
        onRevert = { onEvent(AccountHubEvent.RevertSection(SetupSection.DealConditions)) },
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
                    onEvent(
                        AccountHubEvent.EditDealConditions(
                            conditions.toMutableList().also { it[index] = next },
                        ),
                    )
                },
                onMove = { delta ->
                    onEvent(AccountHubEvent.EditDealConditions(conditions.moved(index, delta)))
                },
                onRemove = {
                    onEvent(
                        AccountHubEvent.EditDealConditions(
                            conditions.filterIndexed { at, _ -> at != index },
                        ),
                    )
                },
            )
        }
        if (!editable) return@SetupSectionCard
        ZillitButton(
            text = "Add condition",
            onClick = {
                onEvent(
                    AccountHubEvent.EditDealConditions(
                        // A blank id: the server mints the real one and echoes
                        // it back, which is what the section then re-snapshots.
                        conditions + DealCondition(id = "", order = conditions.size + 1),
                    ),
                )
            },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Add,
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
            placeholder = "The clause, as it appears on the memo",
            enabled = editable,
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
            contentDescription = "Remove condition ${index + 1}",
            onClick = onRemove,
            tint = ZillitTheme.colors.danger,
        )
    }
}

@Composable
internal fun PayrollBureausSection(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val section = state.setup.payrollBureaus
    val bureaus = section.edited
    val editable = state.viewer.canEdit

    SetupSectionCard(
        title = "Payroll Bureau",
        description = "The bureaux a deal memo's payroll step can be routed to.",
        dirty = section.dirty,
        saving = section.saving,
        onSave = { onEvent(AccountHubEvent.SaveSection(SetupSection.PayrollBureaus)) },
        onRevert = { onEvent(AccountHubEvent.RevertSection(SetupSection.PayrollBureaus)) },
        editable = editable,
    ) {
        if (bureaus.isEmpty()) EmptyLine("No payroll bureaux yet.")
        bureaus.forEachIndexed { index, bureau ->
            BureauRow(
                bureau = bureau,
                editable = editable,
                onChange = { next ->
                    onEvent(
                        AccountHubEvent.EditPayrollBureaus(
                            bureaus.toMutableList().also { it[index] = next },
                        ),
                    )
                },
                onRemove = {
                    onEvent(
                        AccountHubEvent.EditPayrollBureaus(
                            bureaus.filterIndexed { at, _ -> at != index },
                        ),
                    )
                },
            )
        }
        if (!editable) return@SetupSectionCard
        ZillitButton(
            text = "Add bureau",
            onClick = {
                onEvent(AccountHubEvent.EditPayrollBureaus(bureaus + PayrollBureau(id = "")))
            },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Add,
        )
    }
}

@Composable
private fun BureauRow(
    bureau: PayrollBureau,
    editable: Boolean,
    onChange: (PayrollBureau) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitTextField(
            value = bureau.title,
            onValueChange = { onChange(bureau.copy(title = it)) },
            placeholder = "Bureau name",
            enabled = editable,
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = bureau.description,
            onValueChange = { onChange(bureau.copy(description = it)) },
            placeholder = "What it covers (optional)",
            enabled = editable,
            modifier = Modifier.weight(1f),
        )
        if (editable) {
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = "Remove ${bureau.title.ifBlank { "bureau" }}",
                onClick = onRemove,
                tint = ZillitTheme.colors.danger,
            )
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
