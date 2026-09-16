package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuSurface
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTokenField
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.copyTextToClipboard
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.media.ALL_ATTACHMENT_KINDS
import com.zillit.desktop.core.media.AttachMenu
import com.zillit.desktop.feature.email.domain.EmailAttachment
import com.zillit.desktop.feature.email.domain.EmailContact
import com.zillit.desktop.feature.email.domain.EmailSignature
import com.zillit.desktop.feature.email.domain.OutgoingAttachment
import com.zillit.desktop.feature.email.domain.UploadState
import com.zillit.desktop.feature.email.domain.htmlToSpans
import com.zillit.desktop.feature.email.domain.isValidEmail

/**
 * Writing a message, in the reading pane — the web's inline composer:
 * `ComposeInlineToolbar` over `ComposeModal`.
 *
 * Send, Attach, Save as Draft and Discard along the top with Popout on the
 * far right; the From row with a copy button; To, Cc and Bcc as chip rows
 * with suggestions; the subject; the editor with its format bar; the quoted
 * original folded under it on a reply; the files; and the sign-off the
 * message will carry.
 */
@Composable
internal fun ComposePane(
    state: ComposeUiState,
    onEvent: (ComposeEvent) -> Unit,
    modifier: Modifier = Modifier,
    /** Null in a window of its own, where there is nothing to pop out of. */
    onPopOut: (() -> Unit)? = null,
    /** Whether the host can put files in storage; hides Attach otherwise. */
    canAttach: Boolean = true,
) {
    val colors = ZillitTheme.colors
    Column(modifier = modifier.fillMaxSize().background(colors.surface)) {
        ComposeToolbar(state, onEvent, onPopOut, canAttach)
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .zillitVerticalScroll(scroll)
                .padding(horizontal = PANE_INSET, vertical = ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            FromRow(state.fromAddress)
            RecipientRow(RecipientField.To, "To", state, onEvent, required = true)
            RecipientRow(RecipientField.Cc, "Cc", state, onEvent)
            RecipientRow(RecipientField.Bcc, "Bcc", state, onEvent)
            SubjectRow(state, onEvent)

            state.error?.let { message ->
                ZillitText(
                    text = message,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.danger,
                    modifier = Modifier.padding(vertical = ZillitTheme.spacing.xs),
                )
            }

            RichTextEditor(
                value = state.body,
                onValueChange = { onEvent(ComposeEvent.BodyChanged(it)) },
                placeholder = "Write your message…",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = EDITOR_MIN_HEIGHT)
                    .padding(top = ZillitTheme.spacing.sm),
            )

            if (state.hasQuote) QuotedOriginal(state, onEvent)
            if (state.attachments.isNotEmpty() || state.draft.forwarded.isNotEmpty()) AttachmentBar(state, onEvent)
            SignatureRow(state, onEvent)
        }
    }

    if (state.asksSubjectless) SubjectlessDialog(state.isSending, onEvent)
}

/** Send · Attach · Save as Draft · Discard … Popout. */
@Composable
private fun ComposeToolbar(
    state: ComposeUiState,
    onEvent: (ComposeEvent) -> Unit,
    onPopOut: (() -> Unit)?,
    canAttach: Boolean,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm)
            .testTag(COMPOSE_TOOLBAR_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitButton(
            text = "Send",
            leadingIcon = ZillitIcons.Send,
            onClick = { onEvent(ComposeEvent.Send) },
            enabled = !state.isSending,
            loading = state.isSending,
            modifier = Modifier.testTag(SEND_TAG),
        )
        if (canAttach) {
            // The phones' attach sheet behind the paperclip: Photo, Video,
            // Document, Audio — Android's `ComposeActivity` offers the same four.
            AttachMenu(
                kinds = ALL_ATTACHMENT_KINDS,
                icon = ZillitIcons.Paperclip,
                contentDescription = "Attach",
                onPick = { kind -> onEvent(ComposeEvent.PickFilesOf(kind)) },
            )
        }
        ZillitButton(
            text = if (state.isSavingDraft) "Saving Draft…" else "Save as Draft",
            leadingIcon = ZillitIcons.Save,
            variant = ButtonVariant.Tertiary,
            enabled = !state.isSavingDraft && !state.isSending,
            onClick = { onEvent(ComposeEvent.SaveDraft) },
            modifier = Modifier.testTag(SAVE_DRAFT_TAG),
        )
        DiscardButton(onEvent)
        Spacer(Modifier.weight(1f))
        if (onPopOut != null) {
            ZillitButton(
                text = "Popout",
                leadingIcon = ZillitIcons.Detach,
                variant = ButtonVariant.Tertiary,
                onClick = onPopOut,
                modifier = Modifier.testTag(POPOUT_TAG),
            )
        }
    }
}

/** Discard, in the web's red. */
@Composable
private fun DiscardButton(onEvent: (ComposeEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.medium)
            .background(if (hovered) colors.dangerSoft else Color.Transparent)
            .hoverable(interaction)
            .clickable { onEvent(ComposeEvent.Discard) }
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm)
            .testTag(DISCARD_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(ZillitIcons.Trash, contentDescription = null, tint = colors.danger, size = TOOLBAR_ICON)
        ZillitText(text = "Discard", style = ZillitTheme.typography.button, color = colors.danger)
    }
}

/** From: the mailbox's address, with the copy button the web puts beside it (ZL-16601). */
@Composable
private fun FromRow(address: String) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        FieldLabel("From")
        ZillitText(
            text = address.ifBlank { "—" },
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f).testTag(FROM_TAG),
        )
        if (address.isNotBlank()) {
            ZillitTooltip("Copy email") {
                ZillitIconButton(
                    icon = ZillitIcons.Copy,
                    contentDescription = "Copy email",
                    onClick = { copyTextToClipboard(address) },
                    size = TOOLBAR_BUTTON,
                )
            }
        }
    }
    Rule()
}

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.divider))
}

@Composable
private fun FieldLabel(text: String, required: Boolean = false) {
    Row(Modifier.width(FIELD_LABEL_WIDTH)) {
        ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
        if (required) {
            ZillitText(text = "*", style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger)
        }
    }
}

/**
 * One address row: chips for every recipient, the text after them, and the
 * suggestions anchored under the row while it has focus — the web's
 * `ListWrapper` rows. An address the mailbox does not know wears a small
 * "add to contacts" button on its chip.
 */
@Composable
private fun RecipientRow(
    field: RecipientField,
    label: String,
    state: ComposeUiState,
    onEvent: (ComposeEvent) -> Unit,
    required: Boolean = false,
) {
    val tokens = state.tokensFor(field)
    val input = state.inputFor(field)
    val isFocused = state.focusedField == field
    val suggestions = if (isFocused) state.suggestions else emptyList()

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Box(Modifier.padding(top = ZillitTheme.spacing.sm)) { FieldLabel(label, required) }
            Box(Modifier.weight(1f)) {
                ZillitTokenField(
                    tokens = tokens,
                    input = input,
                    onValueChange = { updated, text -> onEvent(ComposeEvent.RecipientsChanged(field, updated, text)) },
                    placeholder = if (tokens.isEmpty()) label else null,
                    tokenLabel = { state.labelFor(it) },
                    isTokenValid = { it.isValidEmail() },
                    onFocusChanged = { focused -> onEvent(ComposeEvent.FocusChanged(if (focused) field else null)) },
                    modifier = Modifier.fillMaxWidth().testTag("compose-${label.lowercase()}"),
                )
                ZillitMenuSurface(
                    expanded = suggestions.isNotEmpty(),
                    // Dismissing must not steal focus back from the field.
                    onDismissRequest = { onEvent(ComposeEvent.FocusChanged(null)) },
                    properties = PopupProperties(focusable = false),
                ) {
                    Column(Modifier.padding(horizontal = SUGGESTION_INSET).widthIn(min = SUGGESTION_MIN_WIDTH)) {
                        suggestions.forEach { contact ->
                            SuggestionRow(contact) { onEvent(ComposeEvent.ContactPicked(contact)) }
                        }
                    }
                }
            }
        }
        UnknownAddresses(tokens, state, onEvent)
        Rule()
    }
}

/** "Add to contacts" for the addresses on the row the mailbox has never seen. */
@Composable
private fun UnknownAddresses(tokens: List<String>, state: ComposeUiState, onEvent: (ComposeEvent) -> Unit) {
    val unknown = tokens.filter { it.isValidEmail() && !state.isKnown(it) }
    if (unknown.isEmpty()) return
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.padding(
            start = FIELD_LABEL_WIDTH + ZillitTheme.spacing.md,
            bottom = ZillitTheme.spacing.xs,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        unknown.forEach { address ->
            Row(
                modifier = Modifier
                    .clip(ZillitTheme.shapes.pill)
                    .clickable { onEvent(ComposeEvent.AddToContacts(address)) }
                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitIcon(ZillitIcons.UserPlus, contentDescription = null, tint = colors.accentText, size = CHIP_ICON)
                ZillitText(
                    text = "Add $address to contacts",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.accentText,
                )
            }
        }
    }
}

@Composable
private fun SubjectRow(state: ComposeUiState, onEvent: (ComposeEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        FieldLabel("Subject")
        ZillitTextField(
            value = state.draft.subject,
            onValueChange = { if (it.length <= SUBJECT_LIMIT) onEvent(ComposeEvent.SubjectChanged(it)) },
            placeholder = "Subject",
            modifier = Modifier.weight(1f).testTag(SUBJECT_TAG),
        )
    }
}

/**
 * A suggestion: who they are, and how to tell them from someone else with the
 * same name.
 */
@Composable
private fun SuggestionRow(contact: EmailContact, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(if (hovered) ZillitTheme.colors.accentSoft else Color.Transparent)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = SUGGESTION_PADDING_X, vertical = SUGGESTION_PADDING_Y)
            .testTag("suggestion-${contact.address}"),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitAvatar(name = contact.label, userId = contact.userId, size = SUGGESTION_AVATAR)
        Column {
            ZillitText(text = contact.label, style = ZillitTheme.typography.bodyMedium)
            // The address is the second line unless it is already the first,
            // which happens for a contact saved without a name.
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
}

/**
 * The original, quoted below the editor as the web and Gmail show it —
 * folded to one line until unfolded, sent whole either way.
 */
@Composable
private fun QuotedOriginal(state: ComposeUiState, onEvent: (ComposeEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ZillitTheme.spacing.sm)
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEvent(ComposeEvent.ToggleQuote) }
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm)
                .testTag(QUOTE_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(
                icon = if (state.quoteExpanded) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                contentDescription = null,
                tint = colors.textMuted,
                size = CHIP_ICON,
            )
            ZillitText(
                text = if (state.quoteExpanded) "Quoted message" else "Show quoted message",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
        if (state.quoteExpanded) {
            val spans = remember(state.draft.quotedHtml) { htmlToSpans(state.draft.quotedHtml) }
            Box(Modifier.padding(horizontal = ZillitTheme.spacing.md).padding(bottom = ZillitTheme.spacing.md)) {
                HtmlBody(spans = spans, onOpenLink = {})
            }
        }
    }
}

/**
 * The files going out with this message: uploads with their progress, and
 * the original's files on a reply or forward. Send is disabled until every
 * upload finishes, so each chip says where its upload has got to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentBar(state: ComposeUiState, onEvent: (ComposeEvent) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        state.draft.forwarded.forEach { attachment ->
            FileChip(attachment.fileName, attachment.readableSize, failed = false) {
                onEvent(ComposeEvent.RemoveForwarded(attachment.id))
            }
        }
        state.attachments.forEach { attachment ->
            FileChip(attachment.fileName, attachment.status(), failed = attachment.state is UploadState.Failed) {
                onEvent(ComposeEvent.RemoveAttachment(attachment.id))
            }
        }
    }
}

@Composable
private fun FileChip(name: String, caption: String, failed: Boolean, onRemove: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .padding(start = ZillitTheme.spacing.sm, top = ZillitTheme.spacing.xxs, bottom = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(ZillitIcons.Paperclip, contentDescription = null, tint = colors.textMuted, size = CHIP_ICON)
        ZillitText(text = name, style = ZillitTheme.typography.labelSmall, color = colors.textSecondary, maxLines = 1)
        ZillitText(
            text = caption,
            style = ZillitTheme.typography.labelSmall,
            color = if (failed) colors.danger else colors.textMuted,
            maxLines = 1,
        )
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Remove $name",
            onClick = onRemove,
            size = TOOLBAR_BUTTON,
        )
    }
}

/** A settled attachment shows its size; an unsettled one says so. */
private fun OutgoingAttachment.status(): String = when (val current = state) {
    UploadState.Pending -> "Waiting…"
    is UploadState.InProgress -> "Uploading… ${current.percent}%"
    is UploadState.Uploaded -> readableSize
    is UploadState.Failed -> current.reason
}

/**
 * The sign-off that goes out with the message, and the menu to change it.
 *
 * Shown because the signature is deliberately not in the editor — see
 * `composedBody`. Without this the user has no way to know what is about
 * to be appended. "Manage signatures…" is always there, because it is how
 * the first signature gets made.
 */
@Composable
private fun SignatureRow(state: ComposeUiState, onEvent: (ComposeEvent) -> Unit) {
    val colors = ZillitTheme.colors
    var open by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ZillitTheme.spacing.md)
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitIcon(ZillitIcons.Signature, contentDescription = null, tint = colors.textMuted, size = CHIP_ICON)
            Spacer(Modifier.width(ZillitTheme.spacing.xs))
            ZillitText(
                text = "Signature",
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            Box {
                ZillitButton(
                    text = state.signature?.title ?: "No signature",
                    trailingIcon = ZillitIcons.ChevronDown,
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    onClick = { open = true },
                    modifier = Modifier.testTag(SIGNATURE_MENU_TAG),
                )
                ZillitActionMenu(
                    expanded = open,
                    onDismissRequest = { open = false },
                    entries = buildList {
                        add(
                            ZillitMenuEntry.Action("No signature", ZillitIcons.Minus) {
                                onEvent(ComposeEvent.SignatureChosen(null))
                            },
                        )
                        state.signatures.forEach { signature ->
                            add(
                                ZillitMenuEntry.Action(signature.title, ZillitIcons.Signature, ZillitMenuTone.Primary) {
                                    onEvent(ComposeEvent.SignatureChosen(signature))
                                },
                            )
                        }
                        add(ZillitMenuEntry.Divider)
                        add(
                            ZillitMenuEntry.Action("Manage signatures…", ZillitIcons.Settings) {
                                onEvent(ComposeEvent.ManageSignatures)
                            },
                        )
                    },
                )
            }
        }
        state.signature?.let { SignaturePreview(it) }
    }
}

@Composable
private fun SignaturePreview(signature: EmailSignature) {
    val spans = remember(signature.id, signature.body) { htmlToSpans(signature.body) }
    if (spans.isEmpty()) return
    Box(Modifier.padding(start = ZillitTheme.spacing.md)) { HtmlBody(spans = spans, onOpenLink = {}) }
}

/** "Do you want to send the email without a subject?" — Don't Send / Send Anyway. */
@Composable
private fun SubjectlessDialog(sending: Boolean, onEvent: (ComposeEvent) -> Unit) {
    ModalCard(onDismiss = { onEvent(ComposeEvent.DismissSubjectless) }) {
        ZillitText(text = "Subject", style = ZillitTheme.typography.titleMedium)
        ZillitText(
            text = "Do you want to send the email without a subject?",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.End),
        ) {
            ZillitButton(
                text = "Don't Send",
                variant = ButtonVariant.Tertiary,
                onClick = { onEvent(ComposeEvent.DismissSubjectless) },
            )
            ZillitButton(
                text = "Send Anyway",
                loading = sending,
                enabled = !sending,
                onClick = { onEvent(ComposeEvent.SendAnyway) },
                modifier = Modifier.testTag(SEND_ANYWAY_TAG),
            )
        }
    }
}

internal const val COMPOSE_TOOLBAR_TAG = "compose-toolbar"
internal const val SEND_TAG = "compose-send"
internal const val SEND_ANYWAY_TAG = "compose-send-anyway"
internal const val SAVE_DRAFT_TAG = "compose-save-draft"
internal const val DISCARD_TAG = "compose-discard"
internal const val POPOUT_TAG = "compose-popout"
internal const val FROM_TAG = "compose-from"
internal const val SUBJECT_TAG = "compose-subject"
internal const val QUOTE_TAG = "compose-quote"
internal const val SIGNATURE_MENU_TAG = "compose-signature"

private val PANE_INSET = 24.dp
private val FIELD_LABEL_WIDTH = 56.dp
private val EDITOR_MIN_HEIGHT = 260.dp
private val TOOLBAR_ICON = 16.dp
private val TOOLBAR_BUTTON = 24.dp
private val CHIP_ICON = 14.dp
private val SUGGESTION_INSET = 6.dp
private val SUGGESTION_MIN_WIDTH = 280.dp
private val SUGGESTION_PADDING_X = 10.dp
private val SUGGESTION_PADDING_Y = 6.dp
private val SUGGESTION_AVATAR = 28.dp
private const val SUBJECT_LIMIT = 512
