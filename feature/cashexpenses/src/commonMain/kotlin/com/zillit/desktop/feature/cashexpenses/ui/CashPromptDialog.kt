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
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

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
fun CashPromptDialog(prompt: CashPrompt?, onEvent: (CashEvent) -> Unit) {
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

            null -> Unit
        }

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
}

private fun CashPrompt?.title(): String = when (this) {
    is CashPrompt.Confirm -> title
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
