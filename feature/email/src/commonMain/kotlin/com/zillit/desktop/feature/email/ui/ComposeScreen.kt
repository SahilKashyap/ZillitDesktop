package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.email.domain.EmailContact
import com.zillit.desktop.feature.email.domain.EmailSignature
import com.zillit.desktop.feature.email.domain.htmlToPlainText
import com.zillit.desktop.feature.email.domain.OutgoingAttachment
import com.zillit.desktop.feature.email.domain.UploadState

/**
 * Writing a message — the form, without its chrome.
 *
 * The title bar and the minimise/expand/close controls belong to whatever is
 * holding this: see [ComposerDock]. This is only the fields, so the same form
 * serves a card standing in the corner and one filling the mailbox.
 *
 * ## Laid out the way every mail client lays it out
 *
 * Addresses at the top, subject under them, the message filling what is left,
 * and the send controls along the bottom with the discard as far from Send as
 * the row allows. Familiarity is the whole argument: nobody wants to learn a
 * mail composer, and every departure from the shape they already know is a
 * moment spent looking for something instead of writing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposeScreen(
    state: ComposeUiState,
    onEvent: (ComposeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = ZillitTheme.spacing.md,
                    vertical = ZillitTheme.spacing.sm,
                ),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            RecipientFields(state, onEvent)

            ZillitTextField(
                value = state.draft.subject,
                onValueChange = { onEvent(ComposeEvent.SubjectChanged(it)) },
                placeholder = "Subject",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ZillitDivider()

        RichTextEditor(
            value = state.body,
            onValueChange = { onEvent(ComposeEvent.BodyChanged(it)) },
            placeholder = "Write your message…",
            // Takes the rest of the card: the body is what the user is actually
            // here to write, and a fixed-height box wastes whatever room the
            // window was given.
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            state.error?.let { message ->
                ZillitText(
                    text = message,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.danger,
                )
            }

            state.signature?.let { SignaturePreview(it) }

            if (state.attachments.isNotEmpty()) AttachmentBar(state, onEvent)
        }

        ActionBar(state, onEvent)
    }
}

/**
 * What the sign-off will look like.
 *
 * Shown because the signature is deliberately not in the editor — see
 * `composedBody`. Without this the user has no way to know what is about to be
 * appended to their message.
 */
@Composable
private fun SignaturePreview(signature: EmailSignature) {
    val colors = ZillitTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = signature.title,
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )
        // Flattened for the preview: this is a summary of what will be sent,
        // not a rendering of it.
        ZillitText(
            text = htmlToPlainText(signature.body),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = SIGNATURE_PREVIEW_LINES,
        )
    }
}

/**
 * Choosing a sign-off.
 *
 * Shown even with no signatures, because "Manage signatures…" is how the first
 * one gets made — hiding the menu until one exists would leave no way in.
 */
@Composable
private fun SignatureMenu(state: ComposeUiState, onEvent: (ComposeEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Box {
        ZillitButton(
            text = state.signature?.title ?: "No signature",
            trailingIcon = ZillitIcons.ChevronDown,
            variant = ButtonVariant.Tertiary,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { ZillitText("No signature", style = ZillitTheme.typography.bodyMedium) },
                onClick = {
                    open = false
                    onEvent(ComposeEvent.SignatureChosen(null))
                },
            )
            state.signatures.forEach { signature ->
                DropdownMenuItem(
                    text = { ZillitText(signature.title, style = ZillitTheme.typography.bodyMedium) },
                    onClick = {
                        open = false
                        onEvent(ComposeEvent.SignatureChosen(signature))
                    },
                )
            }
            DropdownMenuItem(
                text = {
                    ZillitText(
                        text = "Manage signatures…",
                        style = ZillitTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textMuted,
                    )
                },
                onClick = {
                    open = false
                    onEvent(ComposeEvent.ManageSignatures)
                },
            )
        }
    }
}

/**
 * The files going out with this message.
 *
 * Each chip shows where its upload has got to, because Send is disabled until
 * they all finish and the user needs to see why.
 */
@Composable
private fun AttachmentBar(state: ComposeUiState, onEvent: (ComposeEvent) -> Unit) {
    val colors = ZillitTheme.colors

    FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        state.attachments.forEach { attachment ->
            Row(
                modifier = Modifier
                    .clip(ZillitTheme.shapes.medium)
                    .background(colors.surfaceSunken)
                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = attachment.fileName,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
                ZillitText(
                    text = attachment.status(),
                    style = ZillitTheme.typography.labelSmall,
                    color = if (attachment.state is UploadState.Failed) colors.danger else colors.textMuted,
                    maxLines = 1,
                )
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = "Remove ${attachment.fileName}",
                    onClick = { onEvent(ComposeEvent.RemoveAttachment(attachment.id)) },
                )
            }
        }
    }
}

/**
 * The trailing text on a chip.
 *
 * A settled attachment shows its size, which is what tells the recipient's
 * mail server whether it will accept the message. An unsettled one says so.
 */
private fun OutgoingAttachment.status(): String = when (val current = state) {
    UploadState.Pending -> "Waiting…"
    is UploadState.InProgress -> "Uploading…"
    is UploadState.Uploaded -> readableSize
    is UploadState.Failed -> current.reason
}

/**
 * Send on the left, the tools beside it, discard as far away as the row goes.
 *
 * The distance is the point: Send and Discard are the two irreversible things
 * on this screen and they must not be neighbours. Everything between them is
 * something that can be undone by doing it again.
 */
@Composable
private fun ActionBar(state: ComposeUiState, onEvent: (ComposeEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitButton(
            text = "Send",
            onClick = { onEvent(ComposeEvent.Send) },
            enabled = state.canSend,
            loading = state.isSending,
        )

        ZillitIconButton(
            icon = ZillitIcons.Paperclip,
            contentDescription = "Attach a file",
            onClick = { onEvent(ComposeEvent.PickFiles) },
        )

        SignatureMenu(state, onEvent)

        Spacer(Modifier.weight(1f))

        // Only once something is saved is there anything to say or discard.
        // Autosave that gives no sign it happened leaves people copying their
        // text somewhere else before daring to close the window.
        if (state.isDraftSaved) {
            ZillitText(
                text = "Draft saved",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = "Discard this draft",
                onClick = { onEvent(ComposeEvent.Discard) },
                tint = ZillitTheme.colors.danger,
            )
        }
    }
}

@Composable
private fun RecipientFields(state: ComposeUiState, onEvent: (ComposeEvent) -> Unit) {
    RecipientField(
        field = RecipientField.To,
        value = state.toText,
        placeholder = "To",
        state = state,
        onEvent = onEvent,
        onChange = { onEvent(ComposeEvent.ToChanged(it)) },
        // Riding the To row rather than sitting in the button bar: this is
        // where every mail client puts it, and it is the only place where the
        // control is next to the thing it adds a field beside.
        trailing = {
            ZillitButton(
                text = if (state.showsCopyFields) "Hide" else "Cc Bcc",
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                onClick = { onEvent(ComposeEvent.ToggleCopyFields) },
            )
        },
    )

    // Hidden by default: most mail needs neither, and two empty fields above
    // the subject line push the message itself down the window for no reason.
    if (state.showsCopyFields) {
        RecipientField(
            field = RecipientField.Cc,
            value = state.ccText,
            placeholder = "Cc",
            state = state,
            onEvent = onEvent,
            onChange = { onEvent(ComposeEvent.CcChanged(it)) },
        )
        RecipientField(
            field = RecipientField.Bcc,
            value = state.bccText,
            placeholder = "Bcc",
            state = state,
            onEvent = onEvent,
            onChange = { onEvent(ComposeEvent.BccChanged(it)) },
        )
    }
}

/**
 * One address field, with its suggestions.
 *
 * The list is anchored under the field it belongs to rather than floating: with
 * three fields stacked, a dropdown that is not visibly attached to one of them
 * is genuinely ambiguous.
 */
@Composable
private fun RecipientField(
    field: RecipientField,
    value: String,
    placeholder: String,
    state: ComposeUiState,
    onEvent: (ComposeEvent) -> Unit,
    onChange: (String) -> Unit,
    /** Rides inside the field, for the To row's Cc/Bcc control. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val isFocused = state.focusedField == field
    val suggestions = if (isFocused) state.suggestions else emptyList()

    Box {
        ZillitTextField(
            value = value,
            onValueChange = onChange,
            placeholder = placeholder,
            trailingContent = trailing,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focus ->
                    // Reported both ways: leaving the field has to close the
                    // list, or it hangs over the one below.
                    onEvent(ComposeEvent.FocusChanged(if (focus.isFocused) field else null))
                },
        )

        DropdownMenu(
            expanded = suggestions.isNotEmpty(),
            // Dismissing must not steal focus back from the field.
            onDismissRequest = { onEvent(ComposeEvent.FocusChanged(null)) },
            properties = PopupProperties(focusable = false),
        ) {
            suggestions.forEach { contact ->
                DropdownMenuItem(
                    text = { SuggestionRow(contact) },
                    onClick = { onEvent(ComposeEvent.ContactPicked(contact)) },
                )
            }
        }
    }
}

/**
 * A suggestion: who they are, and how to tell them from someone else with the
 * same name.
 */
@Composable
private fun SuggestionRow(contact: EmailContact) {
    Column {
        ZillitText(text = contact.label, style = ZillitTheme.typography.bodyMedium)

        // The address is the second line unless it is already the first, which
        // happens for a contact saved without a name.
        val detail = listOfNotNull(
            contact.address.takeIf { it != contact.label },
            contact.subtitle.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

        if (detail.isNotEmpty()) {
            ZillitText(
                text = detail,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

private const val SIGNATURE_PREVIEW_LINES = 4
