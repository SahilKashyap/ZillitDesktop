package com.zillit.desktop.feature.cardexpenses.ui

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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The card tool's confirmation dialog.
 *
 * Four shapes, matching the four kinds of irreversible action here: a plain
 * yes/no, one that needs a reason, one that needs an amount, and the physical
 * card assignment, which needs a card number and is the only place in this
 * client where one is typed.
 */
@Suppress("LongMethod") // Four dialog shapes in one place; splitting them lets them drift.
@Composable
fun CardPromptDialog(prompt: CardPrompt?, onEvent: (CardEvent) -> Unit) {
    val shown = remember(prompt) { prompt }

    ZillitDialogShell(
        title = shown.title(),
        icon = ZillitIcons.Info,
        visible = prompt != null,
        onDismiss = { onEvent(CardEvent.DismissPrompt) },
    ) {
        when (shown) {
            is CardPrompt.Confirm -> ZillitText(
                text = shown.message,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )

            is CardPrompt.WithReason -> ZillitTextField(
                value = shown.reason,
                onValueChange = { onEvent(CardEvent.UpdatePrompt(shown.copy(reason = it))) },
                label = shown.label,
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )

            is CardPrompt.WithAmount -> {
                ZillitTextField(
                    value = shown.amount,
                    onValueChange = { onEvent(CardEvent.UpdatePrompt(shown.copy(amount = it))) },
                    label = shown.label,
                    placeholder = "0.00",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.fillMaxWidth(),
                )
                ZillitTextField(
                    value = shown.note,
                    onValueChange = { onEvent(CardEvent.UpdatePrompt(shown.copy(note = it))) },
                    label = str(S.notes_optional),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is CardPrompt.WithCardNumber -> {
                ZillitTextField(
                    value = shown.number,
                    onValueChange = { onEvent(CardEvent.UpdatePrompt(shown.copy(number = it))) },
                    label = str(S.desktop_card_number),
                    placeholder = "4000 0000 0000 0000",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.fillMaxWidth(),
                )
                ZillitText(
                    text = str(S.desktop_card_last_four_note),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
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
                text = str(S.cancel),
                onClick = { onEvent(CardEvent.DismissPrompt) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = shown.confirmLabel(),
                onClick = { onEvent(CardEvent.ConfirmPrompt) },
                variant = if (shown.isDestructive()) ButtonVariant.Danger else ButtonVariant.Primary,
            )
        }
    }
}

private fun CardPrompt?.title(): String = when (this) {
    is CardPrompt.Confirm -> title
    is CardPrompt.WithReason -> title
    is CardPrompt.WithAmount -> title
    is CardPrompt.WithCardNumber -> title
    null -> ""
}

private fun CardPrompt?.confirmLabel(): String = when (this) {
    is CardPrompt.WithReason ->
        if (action == CardReasonAction.ResolveAlert) str(S.desktop_resolve) else str(S.reject)

    is CardPrompt.WithAmount -> str(S.save)
    is CardPrompt.WithCardNumber -> str(S.assign)
    else -> str(S.confirm)
}

private fun CardPrompt?.isDestructive(): Boolean = when (this) {
    is CardPrompt.WithReason -> action != CardReasonAction.ResolveAlert
    is CardPrompt.Confirm -> action in DESTRUCTIVE
    else -> false
}

/**
 * The actions that cannot be undone from inside the tool.
 *
 * Posting moves money, suspension stops a card in someone's pocket working,
 * deletion loses evidence, and an override skips people who were meant to
 * decide. Those four get the red button; nothing else does, so the colour
 * keeps meaning something.
 */
private val DESTRUCTIVE = setOf(
    CardConfirmAction.PostReceipt,
    CardConfirmAction.PostTransaction,
    CardConfirmAction.SuspendCard,
    CardConfirmAction.DeleteReceipt,
    CardConfirmAction.OverrideCard,
    CardConfirmAction.OverrideReceipt,
    CardConfirmAction.BulkReject,
)
