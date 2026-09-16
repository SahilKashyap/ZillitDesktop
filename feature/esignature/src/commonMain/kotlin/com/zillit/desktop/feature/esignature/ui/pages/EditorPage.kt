@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod", "ComplexCondition")

package com.zillit.desktop.feature.esignature.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.esignature.domain.EnvelopeRecipient
import com.zillit.desktop.feature.esignature.domain.EsignFormat
import com.zillit.desktop.feature.esignature.ui.EditorState
import com.zillit.desktop.feature.esignature.ui.EditorStep
import com.zillit.desktop.feature.esignature.ui.EsignEvent
import com.zillit.desktop.feature.esignature.ui.EsignUiState
import com.zillit.desktop.feature.esignature.ui.components.BlockTitle
import com.zillit.desktop.feature.esignature.ui.components.DocTile
import com.zillit.desktop.feature.esignature.ui.components.EsignCard
import com.zillit.desktop.feature.esignature.ui.components.Hairline
import com.zillit.desktop.feature.esignature.ui.components.hoverRow
import com.zillit.desktop.feature.esignature.ui.components.signerColor

/**
 * The envelope editor — the web's `EnvelopeEditorBody`. Step one prepares
 * the document, the message and the people; step two places the fields.
 */
@Composable
internal fun EditorPage(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val editor = state.editor ?: return
    Column(Modifier.fillMaxSize()) {
        EditorHeader(editor, onEvent)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (editor.step) {
                EditorStep.Prepare -> PrepareStep(editor, onEvent)
                EditorStep.Place -> PlaceStep(editor, onEvent)
            }
        }
        EditorFooter(editor, onEvent)
    }
    EditorDialogs(editor, onEvent)
}

@Composable
private fun EditorHeader(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ZillitIconButton(
                icon = ZillitIcons.ArrowLeft,
                contentDescription = "Back",
                onClick = { onEvent(EsignEvent.Back) },
            )
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitText(
                        text = editor.title.ifBlank { if (editor.isTemplate) "New template" else "Untitled document" },
                        style = ZillitTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = 520.dp),
                    )
                    ZillitTag(
                        label = when {
                            editor.isTemplate -> "TEMPLATE DRAFT"
                            editor.envelopeId != null -> "DRAFT"
                            else -> "NEW"
                        },
                        tone = if (editor.isTemplate) TagTone.Info else TagTone.Neutral,
                    )
                    editor.fromTemplateName?.let { ZillitTag(label = "From template · $it", tone = TagTone.Accent) }
                }
                ZillitText(
                    text = if (editor.step == EditorStep.Prepare) {
                        "Step 1 of 2 · Prepare the document and choose who signs"
                    } else {
                        "Step 2 of 2 · Place the fields each signer fills"
                    },
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            StepDots(editor.step)
        }
        Hairline()
    }
}

@Composable
private fun StepDots(step: EditorStep) {
    val colors = ZillitTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            "Prepare" to EditorStep.Prepare,
            "Place fields" to EditorStep.Place,
        ).forEachIndexed { index, (label, s) ->
            val active = s == step
            val done = step.ordinal > s.ordinal
            Box(
                Modifier.size(22.dp).clip(CircleShape)
                    .background(if (active || done) colors.accent else colors.surfaceSunken)
                    .border(1.dp, if (active || done) colors.accent else colors.border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (done) {
                    ZillitIcon(ZillitIcons.Check, tint = Color.White, size = 12.dp)
                } else {
                    ZillitText(
                        "${index + 1}",
                        style = ZillitTheme.typography.labelSmall,
                        color = if (active) Color.White else colors.textSecondary,
                    )
                }
            }
            ZillitText(
                label,
                style = ZillitTheme.typography.label,
                color = if (active) colors.textPrimary else colors.textMuted,
            )
            if (index == 0) Box(Modifier.width(28.dp).height(1.dp).background(colors.border))
        }
    }
}

@Composable
private fun EditorFooter(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (editor.step == EditorStep.Place) {
                ZillitButton(
                    "Back to prepare",
                    onClick = { onEvent(EsignEvent.GoToPrepare) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.ArrowLeft,
                )
                ZillitText(
                    "${editor.placedCount} field${if (editor.placedCount == 1) "" else "s"} placed" +
                        (if (editor.settings.initialsOnAllPages) " · initials on every page" else ""),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            } else {
                ZillitButton(
                    "Cancel",
                    onClick = { onEvent(EsignEvent.CancelCompose) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            Spacer(Modifier.weight(1f))
            when {
                editor.step == EditorStep.Prepare -> ZillitButton(
                    "Next: Place fields",
                    onClick = { onEvent(EsignEvent.GoToPlace) },
                    size = ButtonSize.Small,
                    trailingIcon = ZillitIcons.ArrowRight,
                    enabled = editor.canPlace && !editor.loadingDoc,
                )
                editor.isTemplate -> ZillitButton(
                    if (editor.templateId != null) "Update template" else "Save template",
                    onClick = { onEvent(EsignEvent.SaveTemplate) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Save,
                    loading = editor.saveAsTemplate?.saving == true,
                )
                else -> {
                    ZillitButton(
                        "Save as template",
                        onClick = { onEvent(EsignEvent.OpenSaveAsTemplate) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                    ZillitButton(
                        "Save draft",
                        onClick = { onEvent(EsignEvent.SaveDraft) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Save,
                        loading = editor.saving,
                    )
                    ZillitButton(
                        "Send envelope",
                        onClick = { onEvent(EsignEvent.RequestSend) },
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Send,
                        enabled = !editor.saving,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- prepare

@Composable
private fun PrepareStep(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitScrollColumn(Modifier.fillMaxSize().background(colors.surfaceSunken)) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DocumentCard(editor, onEvent)
                MessageCard(editor, onEvent)
                OptionsCard(editor, onEvent)
            }
            Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!editor.isTemplate) {
                    SignersCard(editor, onEvent)
                    CcCard(editor, onEvent)
                } else {
                    EsignCard {
                        BlockTitle("Signer roles")
                        Spacer(Modifier.height(6.dp))
                        ZillitText(
                            "A template carries role slots, not people. Whoever uses it picks the real signers. " +
                                "Slots are created as you place fields.",
                            style = ZillitTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentCard(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    EsignCard {
        BlockTitle("Document") {
            if (editor.hasDocument) {
                ZillitButton(
                    "Replace",
                    onClick = { onEvent(EsignEvent.PickDocument) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Upload,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (!editor.hasDocument) {
            Column(
                Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium)
                    .border(2.dp, colors.accent.copy(alpha = 0.5f), ZillitTheme.shapes.medium)
                    .background(colors.accentSoft.copy(alpha = 0.35f))
                    .clickable { onEvent(EsignEvent.PickDocument) }
                    .padding(vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(colors.accentSoft).border(
                        1.dp,
                        colors.accent,
                        CircleShape,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitIcon(ZillitIcons.Upload, tint = colors.accent, size = 24.dp)
                }
                ZillitText("Choose a PDF to send for signature", style = ZillitTheme.typography.titleSmall)
                ZillitText(
                    "PDF only · up to 100 MB",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DocTile(size = 44.dp)
                Column(Modifier.weight(1f)) {
                    ZillitText(
                        editor.fileName.ifBlank { editor.document?.name ?: "Document" },
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                    )
                    ZillitText(
                        buildString {
                            val pages = editor.pages.size.takeIf { it > 0 } ?: editor.document?.pageCount ?: 0
                            if (pages > 0) append("$pages page${if (pages == 1) "" else "s"}")
                            val size = editor.pendingBytes?.size?.toLong() ?: editor.document?.sizeBytes ?: 0
                            if (size > 0) append(" · ${EsignFormat.size(size)}")
                            if (editor.pendingBytes != null) append(" · uploads on save")
                        },
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
                if (editor.loadingDoc) ZillitSpinner()
            }
            if (editor.pages.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    editor.pages.take(4).forEach { page ->
                        Box(
                            Modifier.width(72.dp).height(96.dp).clip(ZillitTheme.shapes.small)
                                .border(1.dp, colors.border, ZillitTheme.shapes.small).background(Color.White),
                        ) {
                            com.zillit.desktop.feature.esignature.ui.components.BytesImage(
                                page.imageBytes,
                                "Page ${page.page}",
                                Modifier.fillMaxSize(),
                            )
                        }
                    }
                    if (editor.pages.size > 4) {
                        Box(
                            Modifier.width(72.dp)
                                .height(96.dp)
                                .clip(ZillitTheme.shapes.small)
                                .background(colors.surfaceSunken),
                            contentAlignment = Alignment.Center,
                        ) {
                            ZillitText(
                                "+${editor.pages.size - 4}",
                                style = ZillitTheme.typography.label,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageCard(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    EsignCard {
        BlockTitle(if (editor.isTemplate) "Template details" else "Add message")
        Spacer(Modifier.height(4.dp))
        ZillitText(
            if (editor.isTemplate) {
                "The name and description shown in the template library."
            } else {
                "The subject and message you enter here are included in the email your recipients receive."
            },
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        Spacer(Modifier.height(12.dp))
        ZillitTextField(
            value = editor.title,
            onValueChange = { v -> onEvent(EsignEvent.EditCompose { copy(title = v) }) },
            label = if (editor.isTemplate) "Name" else "Subject",
            placeholder = if (editor.isTemplate) "Crew engagement form" else "Enter subject",
        )
        Spacer(Modifier.height(10.dp))
        ZillitTextField(
            value = editor.description,
            onValueChange = { v -> onEvent(EsignEvent.EditCompose { copy(description = v) }) },
            label = if (editor.isTemplate) "Description" else "Message",
            placeholder = if (editor.isTemplate) {
                "What this template is for"
            } else {
                "Enter message — tell people what the document is and what you need from them."
            },
            singleLine = false,
            modifier = Modifier.fillMaxWidth().height(110.dp),
        )
    }
}

@Composable
private fun OptionsCard(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val settings = editor.settings
    EsignCard {
        BlockTitle("Options")
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!editor.isTemplate) {
                ZillitSwitch(
                    checked = settings.signingOrderEnabled,
                    onCheckedChange = { onEvent(EsignEvent.EditSettings(settings.copy(signingOrderEnabled = it))) },
                    label = "Signs in this order — each signer is notified after the one before completes",
                )
            }
            ZillitSwitch(
                checked = settings.initialsOnAllPages,
                onCheckedChange = { onEvent(EsignEvent.SetInitialsOnAllPages(it)) },
                label = "Initials on every page — one initial field per signer, bottom right of each page",
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitText("Remind signers", style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
                    ZillitSelect(
                        value = settings.reminderCadenceDays,
                        options = REMINDER_CHOICES,
                        onSelect = { onEvent(EsignEvent.EditSettings(settings.copy(reminderCadenceDays = it))) },
                        label = { days ->
                            when (days) {
                                null -> "Every 2 days (default)"
                                1 -> "Every day"
                                else -> "Every $days days"
                            }
                        },
                        modifier = Modifier.width(190.dp),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitText("Expires after", style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
                    ZillitSelect(
                        value = settings.expirationDays,
                        options = EXPIRY_CHOICES,
                        onSelect = { onEvent(EsignEvent.EditSettings(settings.copy(expirationDays = it))) },
                        label = { days -> if (days == null) "Never" else "$days days" },
                        modifier = Modifier.width(130.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SignersCard(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    EsignCard {
        BlockTitle("Signers") {
            ZillitText("${editor.signers.size}", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
        Spacer(Modifier.height(8.dp))
        if (editor.me != null) {
            ZillitSwitch(
                checked = editor.selfSigns,
                onCheckedChange = { onEvent(EsignEvent.ToggleSelfSign(it)) },
                label = "I need to sign this document too",
            )
            Spacer(Modifier.height(8.dp))
        }
        if (editor.signers.isEmpty()) {
            ZillitNotice(
                text = "Add at least one signer. Everyone you add gets their own colour on the document.",
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Users,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            editor.recipients.forEachIndexed { index, recipient ->
                if (!recipient.isSigner) return@forEachIndexed
                RecipientRow(
                    recipient = recipient,
                    color = signerColor(editor.signerIndexes.indexOf(index)),
                    order = recipient.routingOrder,
                    showOrder = editor.settings.signingOrderEnabled,
                    onUp = { onEvent(EsignEvent.MoveRecipient(index, up = true)) }
                        .takeIf { editor.signerIndexes.first() != index },
                    onDown = { onEvent(EsignEvent.MoveRecipient(index, up = false)) }
                        .takeIf { editor.signerIndexes.last() != index },
                    onRemove = { onEvent(EsignEvent.RemoveRecipient(index)) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZillitButton(
                "Add signer",
                onClick = { onEvent(EsignEvent.EditCompose { copy(signerPickerOpen = true, pickerSearch = "") }) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.UserPlus,
            )
            ZillitButton(
                "External signer by email",
                onClick = { onEvent(EsignEvent.OpenExternal(EnvelopeRecipient.ROLE_SIGNER)) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Tertiary,
                leadingIcon = ZillitIcons.Mail,
            )
        }
    }
}

@Composable
private fun CcCard(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    EsignCard {
        BlockTitle("Receives a copy") {
            ZillitText("${editor.ccs.size}", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
        Spacer(Modifier.height(4.dp))
        ZillitText(
            "CCs are silent until completion, then get the signed copy by email.",
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            editor.recipients.forEachIndexed { index, recipient ->
                if (!recipient.isCc) return@forEachIndexed
                RecipientRow(
                    recipient,
                    colors.info,
                    order = 0,
                    showOrder = false,
                    onUp = null,
                    onDown = null,
                    onRemove = { onEvent(EsignEvent.RemoveRecipient(index)) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZillitButton(
                "Add CC",
                onClick = { onEvent(EsignEvent.EditCompose { copy(ccPickerOpen = true, pickerSearch = "") }) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.UserPlus,
            )
            ZillitButton(
                "External CC by email",
                onClick = { onEvent(EsignEvent.OpenExternal(EnvelopeRecipient.ROLE_CC)) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Tertiary,
                leadingIcon = ZillitIcons.Mail,
            )
        }
    }
}

@Composable
private fun RecipientRow(
    recipient: EnvelopeRecipient,
    color: Color,
    order: Int,
    showOrder: Boolean,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium).border(
            1.dp,
            colors.border,
            ZillitTheme.shapes.medium,
        ).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            if (showOrder && order > 0) {
                ZillitText("$order", style = ZillitTheme.typography.labelSmall, color = Color.White)
            } else {
                ZillitText(
                    recipient.name.ifBlank { recipient.email }.trim()
                        .split(" ")
                        .take(2)
                        .joinToString("") { it.take(1).uppercase() }.ifBlank { "?" },
                    style = ZillitTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZillitText(
                    recipient.name.ifBlank { recipient.email },
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                if (recipient.isExternal) ZillitTag("External", tone = TagTone.Info)
                if (recipient.isPlaceholder) ZillitTag("placeholder", tone = TagTone.Warning)
            }
            if (recipient.email.isNotBlank()) {
                ZillitText(
                    recipient.email,
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            } else if (recipient.isPlaceholder) {
                ZillitText(
                    "Pick a real user before sending",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.warning,
                    maxLines = 1,
                )
            }
        }
        if (onUp != null || onDown != null) {
            ZillitIconButton(
                ZillitIcons.ChevronUp,
                "Move up",
                onClick = onUp ?: {},
                enabled = onUp != null,
                size = 22.dp,
            )
            ZillitIconButton(
                ZillitIcons.ChevronDown,
                "Move down",
                onClick = onDown ?: {},
                enabled = onDown != null,
                size = 22.dp,
            )
        }
        ZillitIconButton(ZillitIcons.Close, "Remove", onClick = onRemove, size = 22.dp)
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
private fun EditorDialogs(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    PeoplePicker(editor, onEvent)
    ExternalDialog(editor, onEvent)
    ConfirmSendDialog(editor, onEvent)
    SaveAsTemplateDialog(editor, onEvent)
}

@Composable
private fun PeoplePicker(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val forCc = editor.ccPickerOpen
    val open = editor.signerPickerOpen || editor.ccPickerOpen
    val taken = editor.recipients.filter { if (forCc) it.isCc else it.isSigner }.map { it.userId }.toSet()
    val matches = editor.options.filter { option ->
        option.userId !in taken &&
            (
                editor.pickerSearch.isBlank() || option.fullName.contains(editor.pickerSearch, true) ||
                    option.email.contains(editor.pickerSearch, true)
                )
    }
    ZillitDialogShell(
        title = if (forCc) "Add a CC" else "Add a signer",
        subtitle = "Project users",
        visible = open,
        onDismiss = { onEvent(EsignEvent.EditCompose { copy(signerPickerOpen = false, ccPickerOpen = false) }) },
        icon = ZillitIcons.Users,
        width = 520.dp,
        scrollable = false,
    ) {
        ZillitSearchField(
            value = editor.pickerSearch,
            onValueChange = { q -> onEvent(EsignEvent.EditCompose { copy(pickerSearch = q) }) },
            placeholder = "Search project users…",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        ZillitScrollColumn(Modifier.fillMaxWidth().height(360.dp)) {
            if (matches.isEmpty()) {
                ZillitText(
                    "Nobody matches.",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(12.dp),
                )
            }
            matches.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium)
                        .hoverRow {
                            onEvent(if (forCc) EsignEvent.AddCc(option.userId) else EsignEvent.AddSigner(option.userId))
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ZillitAvatar(name = option.fullName, userId = option.userId, size = 28.dp)
                    Column(Modifier.weight(1f)) {
                        ZillitText(
                            option.fullName.ifBlank { option.email },
                            style = ZillitTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                        ZillitText(
                            option.email.ifBlank { "No email on the crew list — add by email instead" },
                            style = ZillitTheme.typography.labelSmall,
                            color = if (option.email.isBlank()) colors.warning else colors.textMuted,
                            maxLines = 1,
                        )
                    }
                    ZillitIcon(ZillitIcons.Add, tint = colors.accent, size = 16.dp)
                }
            }
        }
    }
}

@Composable
private fun ExternalDialog(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val draft = editor.external
    ZillitDialogShell(
        title = if (draft?.role == EnvelopeRecipient.ROLE_CC) "External CC" else "External signer",
        subtitle = "Someone outside the production — they sign from an emailed link",
        visible = draft != null,
        onDismiss = { onEvent(EsignEvent.CloseExternal) },
        scrollable = false,
        icon = ZillitIcons.Mail,
        width = 460.dp,
        actions = {
            ZillitButton(
                "Cancel",
                onClick = { onEvent(EsignEvent.CloseExternal) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                "Add",
                onClick = { onEvent(EsignEvent.AddExternal) },
                size = ButtonSize.Small,
                enabled = draft?.emailValid == true,
            )
        },
    ) {
        if (draft == null) return@ZillitDialogShell
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ZillitTextField(
                value = draft.name,
                onValueChange = { onEvent(EsignEvent.EditExternal(draft.copy(name = it))) },
                label = "Name",
                placeholder = "Jane Doe",
            )
            ZillitTextField(
                value = draft.email,
                onValueChange = { onEvent(EsignEvent.EditExternal(draft.copy(email = it))) },
                label = "Email",
                placeholder = "jane@example.com",
                errorText = if (draft.email.isNotBlank() && !draft.emailValid) "Enter a valid email address" else null,
            )
        }
    }
}

@Composable
private fun ConfirmSendDialog(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = "Send this envelope?",
        subtitle = editor.title,
        visible = editor.confirmSend,
        onDismiss = { onEvent(EsignEvent.CancelSend) },
        scrollable = false,
        icon = ZillitIcons.Send,
        width = 480.dp,
        actions = {
            ZillitButton(
                "Not yet",
                onClick = { onEvent(EsignEvent.CancelSend) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                "Send now",
                onClick = { onEvent(EsignEvent.ConfirmSend) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Send,
                loading = editor.saving,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ZillitText(
                if (editor.settings.signingOrderEnabled) {
                    "Signers are notified one at a time, in this order:"
                } else {
                    "Every signer is notified at once:"
                },
                style = ZillitTheme.typography.bodyMedium,
            )
            editor.signers.forEachIndexed { i, s ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(signerColor(i)))
                    ZillitText(
                        "${s.name.ifBlank { s.email }} · " +
                            "${editor.fieldsOf(editor.recipients.indexOf(s)).size} field(s)",
                        style = ZillitTheme.typography.bodySmall,
                    )
                }
            }
            if (editor.ccs.isNotEmpty()) {
                ZillitText(
                    "CC: ${editor.ccs.joinToString { it.name.ifBlank { it.email } }}",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun SaveAsTemplateDialog(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val sheet = editor.saveAsTemplate
    ZillitDialogShell(
        title = if (editor.templateId != null) "Update template" else "Save as template",
        subtitle = "Signers are stripped to role slots; the document, fields and message are kept.",
        visible = sheet != null,
        onDismiss = { onEvent(EsignEvent.CancelSaveAsTemplate) },
        scrollable = false,
        icon = ZillitIcons.Save,
        width = 480.dp,
        actions = {
            ZillitButton(
                "Cancel",
                onClick = { onEvent(EsignEvent.CancelSaveAsTemplate) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                if (editor.templateId != null) "Update" else "Save template",
                onClick = { onEvent(EsignEvent.ConfirmSaveAsTemplate) },
                size = ButtonSize.Small,
                loading = sheet?.saving == true,
                enabled = sheet?.name?.isNotBlank() == true,
            )
        },
    ) {
        if (sheet == null) return@ZillitDialogShell
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ZillitTextField(
                value = sheet.name,
                onValueChange = { onEvent(EsignEvent.EditSaveAsTemplate(sheet.copy(name = it))) },
                label = "Name",
                placeholder = "Crew Deal Memo — Standard",
            )
            ZillitTextField(
                value = sheet.category,
                onValueChange = { onEvent(EsignEvent.EditSaveAsTemplate(sheet.copy(category = it))) },
                label = "Category",
                placeholder = "deal_memo, nda, release…",
                helperText = editor.categories.takeIf { it.isNotEmpty() }?.let { "In use: ${it.joinToString(", ")}" },
            )
            ZillitTextField(
                value = sheet.description,
                onValueChange = { onEvent(EsignEvent.EditSaveAsTemplate(sheet.copy(description = it))) },
                label = "Description",
                placeholder = "What this template is for",
                singleLine = false,
            )
        }
    }
}

private val REMINDER_CHOICES: List<Int?> = listOf(null, 1, 3, 7)
private val EXPIRY_CHOICES: List<Int?> = listOf(null, 7, 14, 30, 60, 90)
