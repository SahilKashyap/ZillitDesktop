package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.email.domain.EmailSignature
import com.zillit.desktop.feature.email.domain.htmlToPlainText

/**
 * Managing sign-offs.
 *
 * A list, and an editor that replaces it. Not side by side: a signature editor
 * needs the width for a rich text field, and a two-pane layout at this window
 * size leaves both halves too narrow to use.
 */
@Composable
internal fun SignatureManagerScreen(
    state: SignatureManagerUiState,
    onEvent: (SignatureEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        when {
            state.draft != null -> SignatureEditor(state, onEvent)
            else -> SignatureList(state, onEvent)
        }

        state.pendingDelete?.let { signature ->
            ModalCard(onDismiss = { onEvent(SignatureEvent.DismissDelete) }) {
                ZillitText(
                    text = "Delete \"${signature.title}\"?",
                    style = ZillitTheme.typography.titleMedium,
                )
                ZillitText(
                    text = "This cannot be undone.",
                    style = ZillitTheme.typography.bodyMedium,
                    color = ZillitTheme.colors.textSecondary,
                )
                DialogButtons(
                    action = "Delete",
                    variant = ButtonVariant.Danger,
                    onConfirm = { onEvent(SignatureEvent.ConfirmDelete) },
                    onDismiss = { onEvent(SignatureEvent.DismissDelete) },
                )
            }
        }
    }
}

@Composable
private fun SignatureList(state: SignatureManagerUiState, onEvent: (SignatureEvent) -> Unit) {
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(text = "Signatures", style = ZillitTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = "New signature",
                leadingIcon = ZillitIcons.Add,
                size = ButtonSize.Small,
                onClick = { onEvent(SignatureEvent.Edit(null)) },
            )
        }

        state.error?.let { message ->
            ZillitText(
                text = message,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
            )
        }

        when {
            state.isLoading -> Hint("Loading…")

            state.signatures.isEmpty() -> Hint(
                "No signatures yet. Add one and it can be appended to messages automatically.",
            )

            else -> state.signatures.forEach { signature ->
                SignatureRow(signature, onEvent)
            }
        }
    }
}

/**
 * One signature: what it says, and when it is used.
 *
 * The two usage checkboxes are the whole reason this screen exists — creating a
 * signature nobody can make automatic is only half the feature.
 */
@Composable
private fun SignatureRow(signature: EmailSignature, onEvent: (SignatureEvent) -> Unit) {
    val colors = ZillitTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surface)
            .border(HAIRLINE, colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = signature.title,
                style = ZillitTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = "Edit",
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                onClick = { onEvent(SignatureEvent.Edit(signature)) },
            )
            ZillitButton(
                text = "Delete",
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                onClick = { onEvent(SignatureEvent.AskDelete(signature)) },
            )
        }

        // Flattened: this is a reminder of which signature this is, not a
        // rendering of it. The editor shows the real thing.
        ZillitText(
            text = htmlToPlainText(signature.body),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
            maxLines = PREVIEW_LINES,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitCheckbox(
                checked = signature.useForNew,
                label = "Use for new messages",
                onCheckedChange = { on ->
                    onEvent(SignatureEvent.UsageChanged(signature, on, signature.useForReply))
                },
            )
            ZillitCheckbox(
                checked = signature.useForReply,
                label = "Use for replies",
                onCheckedChange = { on ->
                    onEvent(SignatureEvent.UsageChanged(signature, signature.useForNew, on))
                },
            )
        }
    }
}

@Composable
private fun SignatureEditor(state: SignatureManagerUiState, onEvent: (SignatureEvent) -> Unit) {
    val draft = state.draft ?: return

    Column(
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = if (draft.isNew) "New signature" else "Edit signature",
            style = ZillitTheme.typography.titleMedium,
        )

        ZillitTextField(
            value = draft.title,
            onValueChange = { onEvent(SignatureEvent.TitleChanged(it)) },
            placeholder = "Name — only you see this",
            modifier = Modifier.fillMaxWidth(),
        )

        // The same editor the composer uses: a signature is where people want
        // bold and a phone number, and two editors would format differently.
        RichTextEditor(
            value = draft.body,
            onValueChange = { onEvent(SignatureEvent.BodyChanged(it)) },
            placeholder = "Your sign-off…",
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        state.error?.let { message ->
            ZillitText(
                text = message,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
            )
        }

        DialogButtons(
            action = if (draft.isNew) "Create" else "Save",
            enabled = draft.canSave,
            loading = state.isSaving,
            onConfirm = { onEvent(SignatureEvent.Save) },
            onDismiss = { onEvent(SignatureEvent.CancelEdit) },
        )
    }
}

@Composable
private fun Hint(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().heightIn(min = HINT_HEIGHT).padding(ZillitTheme.spacing.lg),
    )
}

private val HAIRLINE = 1.dp
private val HINT_HEIGHT = 80.dp
private const val PREVIEW_LINES = 3
