package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTokenField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.documentdistribution.domain.HtmlText
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.MAX_TOTAL_ATTACHMENT_BYTES
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.domain.SupportedUploads
import com.zillit.desktop.feature.documentdistribution.domain.WATERMARK_SUPPORTED_LABEL
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkLine
import com.zillit.desktop.feature.documentdistribution.domain.formatBytes
import com.zillit.desktop.feature.documentdistribution.domain.isValidEmail
import com.zillit.desktop.feature.documentdistribution.ui.AddressField
import com.zillit.desktop.feature.documentdistribution.ui.ComposerStage
import com.zillit.desktop.feature.documentdistribution.ui.ComposerState
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import com.zillit.desktop.feature.documentdistribution.ui.PickerPurpose

/**
 * The send dialog — the web's `EmailComposer`: address rows with the
 * address book behind them, lists and templates and signatures a click
 * away, attachments from the device or the library with a per-file
 * watermark toggle, and a read-back Preview before Send.
 */
@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
fun ComposerDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val composer = state.composer
    val c = ZillitTheme.colors
    val problem = composer.problem
    val blocked = composer.to.isEmpty() || composer.overSizeLimit || composer.uploading != null

    ZillitDialogShell(
        title = composer.title,
        subtitle = composer.folder?.name,
        visible = composer.open,
        onDismiss = { onEvent(DocDistEvent.CloseComposer) },
        icon = ZillitIcons.Send,
        width = DIALOG_WIDTH.dp,
        // Taller than the default: the composer is the tool's main form, and
        // every attachment row it can keep on screen is one fewer scroll.
        maxHeight = DIALOG_MAX_HEIGHT.dp,
        actions = {
            ZillitIcon(
                icon = ZillitIcons.Paperclip,
                tint = if (composer.overSizeLimit) c.danger else c.textMuted,
                size = 14.dp,
            )
            ZillitText(
                text = "${composer.attachments.size} attachment" + (if (composer.attachments.size == 1) "" else "s") +
                    " · ${formatBytes(composer.totalBytes)}" +
                    if (composer.overSizeLimit) " · exceeds ${formatBytes(MAX_TOTAL_ATTACHMENT_BYTES)} limit" else "",
                style = ZillitTheme.typography.bodySmall,
                color = if (composer.overSizeLimit) c.danger else c.textSecondary,
            )
            Box(Modifier.weight(1f))
            if (composer.stage == ComposerStage.Compose) {
                ZillitButton(
                    text = "Cancel",
                    onClick = { onEvent(DocDistEvent.CloseComposer) },
                    variant = ButtonVariant.Tertiary,
                )
                ZillitButton(
                    text = "Preview",
                    onClick = { onEvent(DocDistEvent.ComposeStage(ComposerStage.Preview)) },
                    leadingIcon = ZillitIcons.Eye,
                    enabled = !blocked,
                )
            } else {
                ZillitButton(
                    text = "Edit",
                    onClick = { onEvent(DocDistEvent.ComposeStage(ComposerStage.Compose)) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Edit,
                )
                ZillitButton(
                    text = "Send",
                    onClick = { onEvent(DocDistEvent.Send) },
                    leadingIcon = ZillitIcons.Send,
                    enabled = !blocked && problem == null && !composer.sending,
                    loading = composer.sending,
                )
            }
        },
    ) {
        if (composer.stage == ComposerStage.Preview) {
            PreviewStage(composer)
        } else {
            AddressSection(state, onEvent)
            MessageSection(state, onEvent)
            ZillitDivider()
            AttachmentSection(state, onEvent)
            problem?.let { ZillitNotice(text = it, tone = StatusTone.Pending, icon = ZillitIcons.Info) }
        }
    }

    WatermarkWizardDialog(state, onEvent)
    OversizeChooserDialog(state, onEvent)
    ListEditorDialog(state, onEvent)
}

// -- addresses -----------------------------------------------------------------

@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
private fun ColumnScope.AddressSection(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val composer = state.composer
    val c = ZillitTheme.colors
    val names = state.contacts.associate { it.email.lowercase() to it.name }

    FieldLabel("To") {
        Box {
            ZillitButton(
                text = "Distribution lists",
                onClick = { onEvent(DocDistEvent.ComposeListMenu(true)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Users,
            )
            MenuPopup(
                expanded = composer.listMenuOpen,
                onDismiss = { onEvent(DocDistEvent.ComposeListMenu(false)) },
                entries = state.lists.map { list ->
                    MenuEntry(
                        "${list.name} (${list.recipients.size})",
                        { onEvent(DocDistEvent.AddList(list.id)) },
                        ZillitIcons.Users,
                    )
                } + MenuEntry(
                    "Create new list",
                    { onEvent(DocDistEvent.OpenListEditor) },
                    ZillitIcons.Add,
                    dividerBefore = state.lists.isNotEmpty(),
                ),
                width = 280.dp,
            )
        }
        ZillitButton(
            text = "Cc / Bcc",
            onClick = { onEvent(DocDistEvent.ShowCcBcc(!composer.showCcBcc)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            trailingIcon = if (composer.showCcBcc) ZillitIcons.ChevronUp else ZillitIcons.ChevronDown,
        )
    }
    AddressRow(AddressField.To, composer.to, composer.toInput, names, state, onEvent, "alice@studio.com")
    InvalidHint(composer.invalidTo)
    if (composer.showCcBcc) {
        FieldLabel("Cc")
        AddressRow(AddressField.Cc, composer.cc, composer.ccInput, names, state, onEvent, "cc@studio.com")
        InvalidHint(composer.invalidCc)
        FieldLabel("Bcc")
        AddressRow(AddressField.Bcc, composer.bcc, composer.bccInput, names, state, onEvent, "bcc@studio.com")
        InvalidHint(composer.invalidBcc)
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitIcon(icon = ZillitIcons.Info, tint = c.textMuted, size = 14.dp)
        ZillitText(
            text = "Recipients’ replies will be sent to " +
                "${state.viewer.userEmail.ifBlank { "the production's address" }}. " +
                "To change this, visit Settings → Profile → Edit Preferences.",
            style = ZillitTheme.typography.bodySmall,
            color = c.textSecondary,
        )
    }
}

@Composable
private fun AddressRow(
    field: AddressField,
    recipients: List<Recipient>,
    input: String,
    names: Map<String, String>,
    state: DocDistUiState,
    onEvent: (DocDistEvent) -> Unit,
    placeholder: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitTokenField(
            tokens = recipients.map { it.email },
            input = input,
            onValueChange = { tokens, text -> onEvent(DocDistEvent.ComposeAddresses(field, tokens, text)) },
            placeholder = placeholder,
            tokenLabel = { email -> names[email.lowercase()]?.takeIf { it.isNotBlank() } ?: email },
            isTokenValid = ::isValidEmail,
        )
        // The address book beneath the field as you type — pick one to add it.
        val q = input.trim().lowercase()
        if (q.length >= TYPEAHEAD_MIN) {
            val taken = recipients.map { it.email.lowercase() }.toSet()
            val matches = state.contacts
                .filter { it.email.lowercase() !in taken && isValidEmail(it.email) }
                .filter { it.email.lowercase().contains(q) || it.name.lowercase().contains(q) }
                .sortedBy { it.displayName.lowercase() }
                .take(TYPEAHEAD_LIMIT)
            if (matches.isNotEmpty()) {
                val c = ZillitTheme.colors
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = TYPEAHEAD_HEIGHT.dp)
                        .clip(ZillitTheme.shapes.medium)
                        .background(c.surfaceRaised)
                        .border(0.5.dp, c.border, ZillitTheme.shapes.medium)
                        .padding(ZillitTheme.spacing.xs),
                ) {
                    matches.forEach { contact ->
                        HoverRow(
                            onClick = { onEvent(
                                DocDistEvent.ComposeAddresses(field, recipients.map { it.email } + contact.email, ""),
                            ) },
                            padding = ZillitTheme.spacing.sm,
                        ) {
                            com.zillit.desktop.core.designsystem.component.ZillitAvatar(
                                name = contact.displayName,
                                size = 22.dp,
                            )
                            ZillitText(text = contact.displayName, style = ZillitTheme.typography.label, maxLines = 1)
                            if (contact.name.isNotBlank()) {
                                ZillitText(
                                    text = contact.email,
                                    style = ZillitTheme.typography.bodySmall,
                                    color = c.textMuted,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InvalidHint(invalid: List<Recipient>) {
    if (invalid.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs), verticalAlignment = Alignment.Top) {
        ZillitIcon(icon = ZillitIcons.Warning, tint = ZillitTheme.colors.danger, size = 14.dp)
        ZillitText(
            text = (if (invalid.size == 1) "Invalid email address: " else "${invalid.size} invalid email addresses: ") +
                invalid.joinToString(", ") { it.email } + " — please correct or remove.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.danger,
        )
    }
}

// -- message ---------------------------------------------------------------------

@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
private fun ColumnScope.MessageSection(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val composer = state.composer
    val c = ZillitTheme.colors
    FieldLabel("Subject") {
        Box {
            ZillitButton(
                text = "Templates",
                onClick = { onEvent(DocDistEvent.ComposeTemplateMenu(true)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.File,
            )
            MenuPopup(
                expanded = composer.templateMenuOpen,
                onDismiss = { onEvent(DocDistEvent.ComposeTemplateMenu(false)) },
                entries = state.templates.map { t ->
                    MenuEntry(t.name, { onEvent(DocDistEvent.ApplyTemplate(t.id)) }, ZillitIcons.File)
                } +
                    MenuEntry(
                        "Save current as template",
                        { onEvent(DocDistEvent.SaveCurrentAsTemplate) },
                        ZillitIcons.Save,
                        enabled = composer.subject.isNotBlank() || composer.body.isNotBlank(),
                        dividerBefore = state.templates.isNotEmpty(),
                    ),
                width = 280.dp,
            )
        }
    }
    ZillitTextField(
        value = composer.subject,
        onValueChange = { onEvent(DocDistEvent.ComposeSubject(it)) },
        placeholder = "Email subject",
        maxLength = SUBJECT_MAX,
    )
    FieldLabel("Message") {
        Box {
            ZillitButton(
                text = "Signature",
                onClick = { onEvent(DocDistEvent.ComposeSignatureMenu(true)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Edit,
            )
            MenuPopup(
                expanded = composer.signatureMenuOpen,
                onDismiss = { onEvent(DocDistEvent.ComposeSignatureMenu(false)) },
                entries = composer.signatures.map { s ->
                    MenuEntry(s.title, { onEvent(DocDistEvent.InsertSignature(s.id)) }, ZillitIcons.Edit)
                } +
                    MenuEntry(
                        "Sent from Desktop",
                        { onEvent(DocDistEvent.InsertSignature("system-default")) },
                        ZillitIcons.Monitor,
                        dividerBefore = composer.signatures.isNotEmpty(),
                    ) +
                    MenuEntry("No signature", { onEvent(DocDistEvent.InsertSignature("")) }, ZillitIcons.Close),
                width = 260.dp,
            )
        }
    }
    MessageBodyField(
        value = composer.body,
        onValueChange = { onEvent(DocDistEvent.ComposeBody(it)) },
        placeholder = "Write your message…",
        minHeight = BODY_MIN_HEIGHT.dp,
    )
    composer.signature?.let { signature ->
        Row(
            modifier = Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium).background(c.surfaceSunken).padding(
                ZillitTheme.spacing.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(icon = ZillitIcons.Edit, tint = c.textMuted, size = 14.dp)
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = "Signature · ${signature.title}",
                    style = ZillitTheme.typography.labelSmall,
                    color = c.textMuted,
                )
                ZillitText(
                    text = HtmlText.snippet(signature.bodyHtml, SIGNATURE_SNIPPET),
                    style = ZillitTheme.typography.bodySmall,
                    color = c.textSecondary,
                    maxLines = 2,
                )
            }
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = "Remove signature",
                onClick = { onEvent(DocDistEvent.InsertSignature("")) },
            )
        }
    }
}

// -- attachments -------------------------------------------------------------------

@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.AttachmentSection(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val composer = state.composer
    val c = ZillitTheme.colors
    FieldLabel(if (composer.attachments.isEmpty()) "Attachments" else "Attachments · ${composer.attachments.size}") {
        ZillitButton(
            text = "Upload from device",
            onClick = { onEvent(DocDistEvent.PickAndAttach) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Upload,
            enabled = composer.uploading == null,
            loading = composer.uploading != null,
        )
        ZillitButton(
            text = "Attach from library",
            onClick = { onEvent(DocDistEvent.OpenPicker(PickerPurpose.Composer)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Paperclip,
        )
    }
    composer.uploading?.let { progress ->
        ZillitNotice(
            text = "${progress.title}${progress.name.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
            tone = StatusTone.Progress,
            icon = ZillitIcons.Upload,
        )
    }
    if (composer.overSizeLimit) {
        ZillitNotice(
            text = "Attachments total ${formatBytes(composer.totalBytes)}, which exceeds the " +
                "${formatBytes(MAX_TOTAL_ATTACHMENT_BYTES)} limit. Remove some files to send this email.",
            tone = StatusTone.Rejected,
            icon = ZillitIcons.Warning,
        )
    }
    if (composer.stampedCount > 0) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .background(c.accentSoft)
                .clickable { onEvent(DocDistEvent.OpenWatermarkWizard) }
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(icon = ZillitIcons.Shield, tint = c.accentText, size = 16.dp)
            ZillitText(text = "Watermark: ", style = ZillitTheme.typography.bodySmall, color = c.accentText)
            ZillitText(
                text = composer.watermark.summary(),
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                color = c.accentText,
            )
            ZillitStatusPill(
                label = "${composer.stampedCount} file" + (if (composer.stampedCount == 1) "" else "s"),
                tone = StatusTone.Neutral,
            )
            Box(Modifier.weight(1f))
            ZillitIcon(icon = ZillitIcons.Edit, tint = c.accentText, size = 14.dp)
            ZillitText(text = "Edit", style = ZillitTheme.typography.label, color = c.accentText)
        }
    }
    if (composer.attachments.isEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            EmptyAttachCard(
                title = "Upload from your device",
                sub = "Click or drop files here · ${SupportedUploads.LABEL}",
                icon = ZillitIcons.Upload,
                modifier = Modifier.weight(1f),
            ) { onEvent(DocDistEvent.PickAndAttach) }
            EmptyAttachCard(
                title = "Attach from library",
                sub = "Pick previously-uploaded documents",
                icon = ZillitIcons.Paperclip,
                modifier = Modifier.weight(1f),
            ) { onEvent(DocDistEvent.OpenPicker(PickerPurpose.Composer)) }
        }
    } else {
        ZillitText(
            text = "Use the arrows to reorder — the email will attach them in this sequence.",
            style = ZillitTheme.typography.bodySmall,
            color = c.textMuted,
        )
        Column(
            modifier = Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium).border(
                0.5.dp,
                c.border,
                ZillitTheme.shapes.medium,
            ),
        ) {
            composer.attachments.forEachIndexed { index, document ->
                AttachmentRow(index, document, composer, onEvent)
                if (index < composer.attachments.lastIndex) ZillitDivider()
            }
        }
    }
}

@Composable
private fun EmptyAttachCard(
    title: String,
    sub: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(c.accentSoft.copy(alpha = 0.5f))
            .border(1.dp, c.accentSoft, ZillitTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(ZillitTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(icon = icon, tint = c.accent, size = 22.dp)
        ZillitText(text = title, style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold))
        ZillitText(
            text = sub,
            style = ZillitTheme.typography.bodySmall,
            color = c.textMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@Composable
private fun AttachmentRow(
    index: Int,
    document: LibraryDocument,
    composer: ComposerState,
    onEvent: (DocDistEvent) -> Unit,
) {
    val c = ZillitTheme.colors
    val stamped = document.id in composer.watermarked
    HoverRow(padding = ZillitTheme.spacing.sm) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(c.surfaceSunken), contentAlignment = Alignment.Center) {
            ZillitText(text = "${index + 1}", style = ZillitTheme.typography.labelSmall, color = c.textSecondary)
        }
        FileGlyph(document, size = 30.dp)
        Column(Modifier.weight(1f)) {
            ZillitText(text = document.name, style = ZillitTheme.typography.label, maxLines = 1)
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = formatBytes(document.sizeBytes),
                    style = ZillitTheme.typography.bodySmall,
                    color = c.textMuted,
                )
                if (document.isEphemeral) ZillitStatusPill(label = "from device", tone = StatusTone.Neutral)
                if (stamped) ZillitStatusPill(label = "watermarked", tone = StatusTone.Progress, dot = true)
            }
        }
        com.zillit.desktop.core.designsystem.component.ZillitTooltip(
            if (document.isWatermarkable) {
                "Watermark this file before sending"
            } else {
                "Watermarking supports $WATERMARK_SUPPORTED_LABEL"
            },
        ) {
            ZillitCheckbox(
                checked = stamped,
                onCheckedChange = { onEvent(DocDistEvent.ToggleWatermark(document.id)) },
                label = "Watermark",
                enabled = document.isWatermarkable,
            )
        }
        if (stamped) {
            ZillitIconButton(
                icon = ZillitIcons.Eye,
                contentDescription = "Preview watermark",
                onClick = { onEvent(DocDistEvent.OpenWatermarkPreview(document.id)) },
            )
        }
        ZillitIconButton(
            icon = ZillitIcons.ChevronUp,
            contentDescription = "Move up",
            onClick = { onEvent(DocDistEvent.MoveAttachment(document.id, -1)) },
            enabled = index > 0,
        )
        ZillitIconButton(
            icon = ZillitIcons.ChevronDown,
            contentDescription = "Move down",
            onClick = { onEvent(DocDistEvent.MoveAttachment(document.id, 1)) },
            enabled = index < composer.attachments.lastIndex,
        )
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Remove ${document.name}",
            onClick = { onEvent(DocDistEvent.RemoveAttachment(document.id)) },
        )
    }
}

// -- preview stage -----------------------------------------------------------------

@Suppress("LongMethod") // One screen section; splitting it separates each control from its state.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewStage(composer: ComposerState) {
    val c = ZillitTheme.colors
    PreviewRow("Sending type") { ZillitText(text = "Attachment (Documents sent by email)") }
    PreviewRow("To") { AddressChips(composer.to) }
    if (composer.cc.isNotEmpty()) PreviewRow("Cc") { AddressChips(composer.cc) }
    if (composer.bcc.isNotEmpty()) PreviewRow("Bcc") { AddressChips(composer.bcc) }
    PreviewRow("Subject") { ZillitText(
        text = composer.subject.ifBlank { "(no subject)" },
        color = if (composer.subject.isBlank()) c.textMuted else c.textPrimary,
    ) }
    if (composer.attachments.isNotEmpty()) {
        PreviewRow("Documents") {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitText(
                    text = "${composer.attachments.size} joined document" +
                        (if (composer.attachments.size == 1) "" else "s") + " · ${formatBytes(composer.totalBytes)}",
                    style = ZillitTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                composer.attachments.forEachIndexed { index, document ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    ) {
                        ZillitText(
                            text = "${index + 1}",
                            style = ZillitTheme.typography.labelSmall,
                            color = c.textMuted,
                            modifier = Modifier.width(16.dp),
                        )
                        FileGlyph(document, size = 22.dp)
                        ZillitText(text = document.name, maxLines = 1, modifier = Modifier.weight(1f))
                        ZillitText(
                            text = formatBytes(document.sizeBytes),
                            style = ZillitTheme.typography.bodySmall,
                            color = c.textMuted,
                        )
                        if (document.id in composer.watermarked && document.isWatermarkable) ZillitStatusPill(
                            label = "Watermarked",
                            tone = StatusTone.Progress,
                        )
                    }
                }
                if (composer.stampedCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    ) {
                        ZillitIcon(icon = ZillitIcons.Shield, tint = c.accent, size = 14.dp)
                        ZillitText(
                            text = "Watermark: ${composer.watermark.summary()}" +
                                if (composer.watermark.line1 == WatermarkLine.RecipientName) {
                                    " (recipient’s name)"
                                } else {
                                    ""
                                },
                            style = ZillitTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
    PreviewRow("Content") {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(
                text = composer.body.ifBlank { "(empty)" },
                color = if (composer.body.isBlank()) c.textMuted else c.textPrimary,
            )
            composer.signature?.let { ZillitText(text = HtmlText.toPlainText(it.bodyHtml), color = c.textSecondary) }
        }
    }
    ZillitNotice(
        text = "Each recipient’s opens of this email are recorded per person for delivery tracking.",
        tone = StatusTone.Neutral,
        icon = ZillitIcons.Shield,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddressChips(recipients: List<Recipient>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        recipients.forEach { ZillitStatusPill(label = it.email, tone = StatusTone.Progress) }
    }
}

@Composable
private fun PreviewRow(key: String, value: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitText(
            text = key,
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.width(PREVIEW_KEY_WIDTH.dp),
        )
        Box(Modifier.weight(1f)) { value() }
    }
}

// -- the wizard, the oversize chooser --------------------------------------------------

@Composable
private fun WatermarkWizardDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val draft = state.composer.wizardDraft
    ZillitDialogShell(
        title = "Configure watermark",
        subtitle = "What every stamped attachment will carry, personalised per recipient",
        visible = draft != null,
        onDismiss = { onEvent(DocDistEvent.CloseWizard) },
        icon = ZillitIcons.Shield,
        width = 880.dp,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DocDistEvent.CloseWizard) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(text = "Save", onClick = { onEvent(DocDistEvent.SaveWizard) })
        },
    ) {
        if (draft == null) return@ZillitDialogShell
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl)) {
            Column(Modifier.weight(1f)) {
                WatermarkLinesEditor(draft) { onEvent(DocDistEvent.EditWizard(it)) }
                FieldLabel("Appearance", Modifier.padding(bottom = ZillitTheme.spacing.sm))
                WatermarkStyleControls(draft) { onEvent(DocDistEvent.EditWizard(it)) }
            }
            WatermarkPreview(
                style = draft,
                text = draft.render(listOf(Recipient(email = "", name = "Recipient Name"))),
                modifier = Modifier.width(PREVIEW_PANE_WIDTH.dp),
            )
        }
    }
}

@Composable
private fun OversizeChooserDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val chooser = state.composer.oversize
    val c = ZillitTheme.colors
    ZillitDialogShell(
        title = "Choose files to attach",
        subtitle = "These files total more than the ${formatBytes(MAX_TOTAL_ATTACHMENT_BYTES)} attachment limit.",
        visible = chooser != null,
        onDismiss = { onEvent(DocDistEvent.CancelOversize) },
        icon = ZillitIcons.Paperclip,
        width = 520.dp,
        actions = {
            ZillitText(
                text = "Selected total: ${formatBytes(chooser?.selectedBytes ?: 0)} / " +
                    formatBytes(MAX_TOTAL_ATTACHMENT_BYTES) +
                    if (chooser?.over == true) " — over the limit, deselect some files" else "",
                style = ZillitTheme.typography.bodySmall,
                color = if (chooser?.over == true) c.danger else c.textSecondary,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DocDistEvent.CancelOversize) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Attach ${chooser?.selected?.size ?: 0}",
                onClick = { onEvent(DocDistEvent.ConfirmOversize) },
                enabled = chooser != null && !chooser.over && chooser.selected.isNotEmpty(),
            )
        },
    ) {
        chooser?.files?.forEachIndexed { index, file ->
            HoverRow(onClick = { onEvent(DocDistEvent.ToggleOversizeFile(index)) }, padding = ZillitTheme.spacing.sm) {
                ZillitCheckbox(
                    checked = index in chooser.selected,
                    onCheckedChange = { onEvent(DocDistEvent.ToggleOversizeFile(index)) },
                )
                FileGlyph(file.name, file.contentType, size = 26.dp)
                ZillitText(text = file.name, maxLines = 1, modifier = Modifier.weight(1f))
                ZillitText(
                    text = formatBytes(file.sizeBytes),
                    style = ZillitTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
        }
    }
}

private const val DIALOG_WIDTH = 780
private const val DIALOG_MAX_HEIGHT = 860
private const val SUBJECT_MAX = 200
private const val BODY_MIN_HEIGHT = 160
private const val SIGNATURE_SNIPPET = 90
private const val TYPEAHEAD_MIN = 1
private const val TYPEAHEAD_LIMIT = 6
private const val TYPEAHEAD_HEIGHT = 200
private const val PREVIEW_KEY_WIDTH = 110
private const val PREVIEW_PANE_WIDTH = 340
