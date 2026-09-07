package com.zillit.desktop.feature.cashexpenses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
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
                placeholder = "Say what needs correcting",
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
                    label = "Note (optional)",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is CashPrompt.Assign -> AssignFields(shown, assignees, batch, onEvent)

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
            text = "Cancel",
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
            text = "Nobody else on this project can take this batch.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        return
    }
    batch?.assignedTo?.takeIf { it.isNotBlank() }?.let { current ->
        ZillitText(
            text = "Currently with ${assignees.firstOrNull { it.userId == current }?.fullName ?: current}",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth().height(ASSIGNEE_LIST_HEIGHT.dp),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        eligible.forEach { person ->
            ZillitButton(
                text = listOf(person.fullName, person.designation)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                onClick = { onEvent(CashEvent.AssignPickUser(person.userId)) },
                variant = if (prompt.selectedUserId == person.userId) {
                    ButtonVariant.Secondary
                } else {
                    ButtonVariant.Tertiary
                },
                size = ButtonSize.Small,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (BatchAssignment.isUnassigned(batch)) return
    ZillitTextField(
        value = prompt.reason,
        onValueChange = { onEvent(CashEvent.AssignReason(it)) },
        label = "Why it is moving",
        placeholder = "Recorded on the batch",
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun CashPrompt?.title(): String = when (this) {
    is CashPrompt.Confirm -> title
    is CashPrompt.Assign -> title
    is CashPrompt.WithReason -> title
    is CashPrompt.WithAmount -> title
    null -> ""
}

private fun CashPrompt?.subtitle(): String? = when (this) {
    is CashPrompt.WithReason -> "This is recorded on the batch and shown to whoever submitted it."
    else -> null
}

private fun CashPrompt?.confirmLabel(): String = when (this) {
    is CashPrompt.WithReason -> when (action) {
        ReasonedAction.RejectFloat, ReasonedAction.RejectBatch -> "Reject"
        ReasonedAction.QueryBatch -> "Send query"
        ReasonedAction.EscalateBatch -> "Escalate"
    }

    is CashPrompt.WithAmount -> "Save"
    is CashPrompt.Assign -> label
    else -> "Confirm"
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
