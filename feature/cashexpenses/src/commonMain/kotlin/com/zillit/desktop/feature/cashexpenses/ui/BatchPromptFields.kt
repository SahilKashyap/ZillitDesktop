package com.zillit.desktop.feature.cashexpenses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.AssigneeOption
import com.zillit.desktop.feature.cashexpenses.domain.BatchAssignment
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.ui.pages.FieldLabel

/**
 * The web's reassignment reasons (`REASSIGN_REASONS`, `PCPostLedgerPage.jsx:447-453`),
 * then "Other (custom reason)".
 */
internal val REASSIGN_REASONS: List<String>
    get() = listOf(
        str(S.desktop_pc_reason_complex),
        str(S.desktop_inv_reason_workload),
        str(S.desktop_card_reason_expertise),
        str(S.desktop_card_reason_cover),
        str(S.desktop_inv_reason_escalation),
    )

/** A custom reason's cap, as the web's textarea slices it. */
internal const val CUSTOM_REASON_MAX = 500

/**
 * Assign or Reassign #REF — the web's `ReassignModal`: who has it now, the
 * accounts team to hand it to, and on a reassignment a reason from the list
 * or typed.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // The web's modal, field by field.
@Composable
internal fun ColumnScope.BatchAssignFields(
    prompt: CashPrompt.Assign,
    assignees: List<AssigneeOption>,
    batch: ClaimBatch?,
    onEvent: (CashEvent) -> Unit,
    row: @Composable (AssigneeOption, Boolean, () -> Unit) -> Unit,
) {
    val people = LocalCashPeople.current
    batch?.assignedTo?.takeIf { it.isNotBlank() }?.let { current ->
        val role = people.designationOf(current)?.localised()
        val who = people.nameOf(current) + (role?.let { " ($it)" } ?: "")
        ZillitText(
            text = str(S.desktop_card_currently_assigned_to, who),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.surfaceSunken)
                .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
                .padding(ZillitTheme.spacing.md),
        )
    }
    val eligible = BatchAssignment.eligible(assignees, batch)
    FieldLabel(str(S.desktop_pc_assign_to), required = true)
    if (eligible.isEmpty()) {
        ZillitText(
            text = str(S.desktop_ce_nobody_else_can_take),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    } else {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxWidth().height(ASSIGNEE_LIST_HEIGHT),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            eligible.forEach { person ->
                row(person, prompt.selectedUserId == person.userId) {
                    onEvent(CashEvent.AssignPickUser(person.userId))
                }
            }
        }
    }
    // A first assignment needs no reason (`canSubmitAssignment`).
    if (BatchAssignment.isUnassigned(batch)) return

    val presets = REASSIGN_REASONS
    var custom by remember(prompt.batchId) {
        mutableStateOf(prompt.reason.isNotBlank() && prompt.reason !in presets)
    }
    FieldLabel(str(S.reason), required = true)
    ZillitSelect(
        value = if (custom) OTHER else prompt.reason.takeIf { it in presets },
        options = listOf<String?>(null) + presets + OTHER,
        onSelect = { picked ->
            custom = picked == OTHER
            onEvent(CashEvent.AssignReason(if (picked == OTHER || picked == null) "" else picked))
        },
        label = {
            when (it) {
                null -> str(S.desktop_pc_select_reason)
                OTHER -> str(S.desktop_other_custom_reason)
                else -> it
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (custom) {
        FieldLabel(str(S.desktop_pc_custom_reason), required = true)
        ZillitTextField(
            value = prompt.reason,
            onValueChange = { onEvent(CashEvent.AssignReason(it.take(CUSTOM_REASON_MAX))) },
            placeholder = str(S.desktop_pc_enter_reason),
            singleLine = false,
            maxLength = CUSTOM_REASON_MAX,
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitText(
            text = str(S.desktop_pc_char_count, prompt.reason.length),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Escalate #REF to Senior — the web's escalation modal: its note, and the required reason. */
@Composable
internal fun EscalateFields(prompt: CashPrompt.WithReason, onEvent: (CashEvent) -> Unit) {
    ZillitText(
        text = str(S.desktop_pc_escalate_body),
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
    FieldLabel(str(S.desktop_card_reason_for_escalation), required = true)
    ZillitTextField(
        value = prompt.reason,
        onValueChange = { onEvent(CashEvent.UpdatePrompt(prompt.copy(reason = it))) },
        placeholder = str(S.desktop_pc_escalation_hint),
        singleLine = false,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Whether the prompt's confirm may be pressed — the web disables it until the form is whole. */
internal fun CashPrompt?.canConfirm(batch: ClaimBatch?): Boolean = when (this) {
    is CashPrompt.Assign -> BatchAssignment.canSubmit(batch, selectedUserId, reason)
    is CashPrompt.WithReason -> action != ReasonedAction.EscalateBatch || reason.isNotBlank()
    else -> true
}

private const val OTHER = "__custom"
private val ASSIGNEE_LIST_HEIGHT = 180.dp
