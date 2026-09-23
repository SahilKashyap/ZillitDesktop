package com.zillit.desktop.feature.cashexpenses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.BatchAssignment
import com.zillit.desktop.feature.cashexpenses.domain.AssigneeOption

/**
 * The one dialog this tool shows.
 *
 * Three shapes — confirm, confirm-with-reason, confirm-with-amount — because
 * that is every irreversible action in the module. Rendering them from one
 * composable driven by [CashPrompt] means the destructive ones cannot end up
 * with a friendlier confirmation than the harmless ones, which is how a
 * "reject" ships without a reason field.
 *
 * Kept composed and driven by visibility so the dismissal animation can play;
 * see [ZillitDialogShell].
 */
@Composable
fun CashPromptDialog(
    prompt: CashPrompt?,
    assignees: List<AssigneeOption>,
    batch: ClaimBatch?,
    onEvent: (CashEvent) -> Unit,
    /** Where the float dialogs find their companies and floats; empty in the older shapes' tests. */
    state: CashUiState? = null,
) {
    // The last non-null prompt, so the content stays drawn while the dialog
    // animates out instead of vanishing a frame early.
    val shown = remember(prompt) { prompt }

    ZillitDialogShell(
        title = shown.title(),
        subtitle = shown.subtitle(),
        icon = ZillitIcons.Info,
        visible = prompt != null,
        onDismiss = { onEvent(CashEvent.DismissPrompt) },
    ) {
        when (shown) {
            is CashPrompt.Confirm -> ZillitText(
                text = shown.message,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )

            is CashPrompt.WithReason -> ZillitTextField(
                value = shown.reason,
                onValueChange = { onEvent(CashEvent.UpdatePrompt(shown.copy(reason = it))) },
                label = shown.label,
                placeholder = str(S.desktop_ce_say_what_needs_correcting),
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )

            is CashPrompt.WithAmount -> {
                ZillitTextField(
                    value = shown.amount,
                    onValueChange = { onEvent(CashEvent.UpdatePrompt(shown.copy(amount = it))) },
                    label = shown.label,
                    placeholder = "0.00",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.fillMaxWidth(),
                )
                ZillitTextField(
                    value = shown.note,
                    onValueChange = { onEvent(CashEvent.UpdatePrompt(shown.copy(note = it))) },
                    // A partial top-up says why it is short; the web will not
                    // record one without (`PCTopUpsPage.jsx:280`).
                    label = if (shown.action == AmountAction.PartialTopUp) {
                        str(S.desktop_ce_reason_for_partial)
                    } else {
                        str(S.notes_optional)
                    },
                    singleLine = shown.action != AmountAction.PartialTopUp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is CashPrompt.Assign -> AssignFields(shown, assignees, batch, onEvent)

            is CashPrompt.ReadyToCollect -> ReadyToCollectFields(shown, state?.companies.orEmpty(), onEvent)

            is CashPrompt.RecordReturn -> RecordReturnFields(shown, state, onEvent)

            is CashPrompt.NewReconciliation -> NewReconciliationFields(shown, onEvent)

            null -> Unit
        }

        PromptActions(shown, onEvent)
    }
}

@Composable
private fun PromptActions(shown: CashPrompt?, onEvent: (CashEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Spacer(Modifier.weight(1f))
        ZillitButton(
            text = str(S.cancel),
            onClick = { onEvent(CashEvent.DismissPrompt) },
            variant = ButtonVariant.Tertiary,
        )
        ZillitButton(
            text = shown.confirmLabel(),
            onClick = { onEvent(CashEvent.ConfirmPrompt) },
            variant = if (shown.isDestructive()) ButtonVariant.Danger else ButtonVariant.Primary,
        )
    }
}

/**
 * The person, then the reason.
 *
 * The reason box appears only on a reassignment — a first assignment has
 * nothing to explain, and the server takes an empty one — so showing it
 * always would read as a required field that is silently optional.
 */
/** One candidate: their face, their name, what they do, and a tick when chosen. */
@Composable
private fun AssigneeRow(person: AssigneeOption, picked: Boolean, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(if (picked) ZillitTheme.colors.accentSoft else ZillitTheme.colors.surface)
            .clickable(onClick = onPick)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CashPerson(
            userId = person.userId,
            recordedName = person.fullName,
            // A designation can arrive as its label key; the crew list does
            // not translate it, so it is done here.
            secondary = person.designation.takeIf { it.isNotBlank() }?.localised(),
            modifier = Modifier.weight(1f),
        )
        if (picked) {
            ZillitIcon(icon = ZillitIcons.Check, tint = ZillitTheme.colors.accentText, size = PICKED_TICK)
        }
    }
}

@Composable
private fun ColumnScope.AssignFields(
    prompt: CashPrompt.Assign,
    assignees: List<AssigneeOption>,
    batch: ClaimBatch?,
    onEvent: (CashEvent) -> Unit,
) {
    val eligible = BatchAssignment.eligible(assignees, batch)
    if (eligible.isEmpty()) {
        ZillitText(
            text = str(S.desktop_ce_nobody_else_can_take),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        return
    }
    batch?.assignedTo?.takeIf { it.isNotBlank() }?.let { current ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = str(S.desktop_ce_currently_with) + " ",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            CashPerson(userId = current, size = SMALL_FACE)
        }
    }
    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth().height(ASSIGNEE_LIST_HEIGHT.dp),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        eligible.forEach { person ->
            AssigneeRow(
                person = person,
                picked = prompt.selectedUserId == person.userId,
                onPick = { onEvent(CashEvent.AssignPickUser(person.userId)) },
            )
        }
    }
    if (BatchAssignment.isUnassigned(batch)) return
    ZillitTextField(
        value = prompt.reason,
        onValueChange = { onEvent(CashEvent.AssignReason(it)) },
        label = str(S.desktop_ce_why_it_is_moving),
        placeholder = str(S.desktop_ce_recorded_on_the_batch),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun CashPrompt?.title(): String = when (this) {
    is CashPrompt.Confirm -> title
    is CashPrompt.Assign -> title
    is CashPrompt.WithReason -> title
    is CashPrompt.WithAmount -> title
    is CashPrompt.ReadyToCollect -> str(S.desktop_ce_set_company_bs_code)
    is CashPrompt.RecordReturn -> str(S.desktop_ce_record_manual_return)
    is CashPrompt.NewReconciliation -> str(S.desktop_ce_new_reconciliation)
    null -> ""
}

private fun CashPrompt?.subtitle(): String? = when (this) {
    is CashPrompt.WithReason -> str(S.desktop_ce_reason_shown_to_submitter)
    is CashPrompt.ReadyToCollect -> str(S.desktop_ce_ready_to_collect_note)
    is CashPrompt.RecordReturn -> str(S.desktop_ce_record_return_note)
    else -> null
}

private fun CashPrompt?.confirmLabel(): String = when (this) {
    is CashPrompt.WithReason -> when (action) {
        ReasonedAction.RejectFloat, ReasonedAction.RejectBatch -> str(S.reject)
        ReasonedAction.EscalateBatch -> str(S.desktop_ce_escalate)
    }

    is CashPrompt.WithAmount -> str(S.save)
    is CashPrompt.Assign -> label
    is CashPrompt.ReadyToCollect -> str(S.desktop_ce_confirm_ready_to_collect)
    is CashPrompt.RecordReturn -> str(S.desktop_ce_record_cash_return)
    is CashPrompt.NewReconciliation -> str(S.desktop_ce_start_reconciliation)
    else -> str(S.confirm)
}

/**
 * Whether the action cannot be undone from inside the tool.
 *
 * A rejection sends a batch back to its submitter and clears its approvals;
 * posting moves money. Both get the red button — not as decoration, but so the
 * two clicks that are genuinely different from the rest look different.
 */
private fun CashPrompt?.isDestructive(): Boolean = when (this) {
    is CashPrompt.WithReason ->
        action == ReasonedAction.RejectBatch || action == ReasonedAction.RejectFloat

    is CashPrompt.Confirm ->
        action == ConfirmAction.PostBatch ||
            action == ConfirmAction.CloseFloat ||
            action == ConfirmAction.OverrideBatch ||
            action == ConfirmAction.OverrideFloat

    else -> false
}

private const val ASSIGNEE_LIST_HEIGHT = 180
private val PICKED_TICK = 16.dp
private val SMALL_FACE = 20.dp
