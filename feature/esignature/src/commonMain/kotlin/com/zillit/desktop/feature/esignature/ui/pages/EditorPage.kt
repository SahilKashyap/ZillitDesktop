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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
                contentDescription = str(S.docusign_back),
                onClick = { onEvent(EsignEvent.Back) },
            )
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitText(
                        text = editor.title.ifBlank {
                            if (editor.isTemplate) {
                                str(S.dm_template_new)
                            } else {
                                str(S.docusign_send_confirm_untitled)
                            }
                        },
                        style = ZillitTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = 520.dp),
                    )
                    ZillitTag(
                        label = when {
                            editor.isTemplate -> str(S.docusign_template_draft_pill)
                            editor.envelopeId != null -> str(S.desktop_draft_upper)
                            else -> str(S.mtg_new)
                        },
                        tone = if (editor.isTemplate) TagTone.Info else TagTone.Neutral,
                    )
                    editor.fromTemplateName?.let { ZillitTag(
                        label = str(S.desktop_ds_from_template, it),
                        tone = TagTone.Accent,
                    ) }
                }
                ZillitText(
                    text = if (editor.step == EditorStep.Prepare) {
                        str(S.desktop_ds_step_1_of_2_prepare_the_document_and)
                    } else {
                        str(S.desktop_ds_step_2_of_2_place_the_fields_each)
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
            str(S.desktop_ds_prepare) to EditorStep.Prepare,
            str(S.desktop_ds_place_fields) to EditorStep.Place,
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
                    str(S.desktop_ds_back_to_prepare),
                    onClick = { onEvent(EsignEvent.GoToPrepare) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.ArrowLeft,
                )
                ZillitText(
                    str(S.docusign_fields_placed, editor.placedCount) +
                        (if (editor.settings.initialsOnAllPages) {
                            str(S.desktop_ds_initials_on_every_page_suffix)
                        } else {
                            ""
                        }),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            } else {
                ZillitButton(
                    str(S.cancel),
                    onClick = { onEvent(EsignEvent.CancelCompose) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            Spacer(Modifier.weight(1f))
            when {
                editor.step == EditorStep.Prepare -> ZillitButton(
                    str(S.docusign_next_place_fields),
                    onClick = { onEvent(EsignEvent.GoToPlace) },
                    size = ButtonSize.Small,
                    trailingIcon = ZillitIcons.ArrowRight,
                    enabled = editor.canPlace && !editor.loadingDoc,
                )
                editor.isTemplate -> ZillitButton(
                    if (editor.templateId != null) str(S.update_template) else str(S.docusign_save_template),
                    onClick = { onEvent(EsignEvent.SaveTemplate) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Save,
                    loading = editor.saveAsTemplate?.saving == true,
                )
                else -> {
                    ZillitButton(
                        str(S.docusign_save_as_template),
                        onClick = { onEvent(EsignEvent.OpenSaveAsTemplate) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                    ZillitButton(
                        str(S.ah_save_draft),
                        onClick = { onEvent(EsignEvent.SaveDraft) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Save,
                        loading = editor.saving,
                    )
                    ZillitButton(
                        str(S.desktop_ds_send_envelope),
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
                        BlockTitle(str(S.desktop_ds_signer_roles))
                        Spacer(Modifier.height(6.dp))
                        ZillitText(
                            str(S.desktop_ds_template_role_slots_hint),
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
        BlockTitle(str(S.docusign_section_document)) {
            if (editor.hasDocument) {
                ZillitButton(
                    str(S.replace),
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
                ZillitText(
                    str(S.desktop_ds_choose_a_pdf_to_send_for_signature),
                    style = ZillitTheme.typography.titleSmall,
                )
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
                        editor.fileName.ifBlank { editor.document?.name ?: str(S.docusign_section_document) },
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
        BlockTitle(if (editor.isTemplate) str(S.dm_nda_sheet_title_template) else str(S.docusign_section_add_message))
        Spacer(Modifier.height(4.dp))
        ZillitText(
            if (editor.isTemplate) {
                str(S.desktop_ds_the_name_and_description_shown_in_the_template)
            } else {
                str(S.desktop_ds_the_subject_and_message_you_enter_here_are)
            },
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        Spacer(Modifier.height(12.dp))
        ZillitTextField(
            value = editor.title,
            onValueChange = { v -> onEvent(EsignEvent.EditCompose { copy(title = v) }) },
            label = if (editor.isTemplate) str(S.name) else str(S.subject),
            placeholder = if (editor.isTemplate) {
                str(S.desktop_ds_crew_engagement_form)
            } else {
                str(S.desktop_ds_enter_subject)
            },
        )
        Spacer(Modifier.height(10.dp))
        ZillitTextField(
            value = editor.description,
            onValueChange = { v -> onEvent(EsignEvent.EditCompose { copy(description = v) }) },
            label = if (editor.isTemplate) str(S.description) else str(S.message),
            placeholder = if (editor.isTemplate) {
                str(S.desktop_ds_what_this_template_is_for)
            } else {
                str(S.desktop_ds_enter_message_tell_people_what_the_document_is)
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
        BlockTitle(str(S.docusign_prop_options))
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!editor.isTemplate) {
                ZillitSwitch(
                    checked = settings.signingOrderEnabled,
                    onCheckedChange = { onEvent(EsignEvent.EditSettings(settings.copy(signingOrderEnabled = it))) },
                    label = str(S.desktop_ds_signing_order_label),
                )
            }
            ZillitSwitch(
                checked = settings.initialsOnAllPages,
                onCheckedChange = { onEvent(EsignEvent.SetInitialsOnAllPages(it)) },
                label = str(S.desktop_ds_initials_all_pages_label),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitText(
                        str(S.desktop_ds_remind_signers),
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    ZillitSelect(
                        value = settings.reminderCadenceDays,
                        options = REMINDER_CHOICES,
                        onSelect = { onEvent(EsignEvent.EditSettings(settings.copy(reminderCadenceDays = it))) },
                        label = { days ->
                            when (days) {
                                null -> str(S.desktop_ds_every_2_days_default)
                                1 -> str(S.every_day)
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
                    ZillitText(
                        str(S.desktop_ds_expires_after),
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    ZillitSelect(
                        value = settings.expirationDays,
                        options = EXPIRY_CHOICES,
                        onSelect = { onEvent(EsignEvent.EditSettings(settings.copy(expirationDays = it))) },
                        label = { days -> if (days == null) str(S.never) else "$days days" },
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
        BlockTitle(str(S.docusign_section_signers)) {
            ZillitText("${editor.signers.size}", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
        Spacer(Modifier.height(8.dp))
        if (editor.me != null) {
            ZillitSwitch(
                checked = editor.selfSigns,
                onCheckedChange = { onEvent(EsignEvent.ToggleSelfSign(it)) },
                label = str(S.desktop_ds_i_need_to_sign_too),
            )
            Spacer(Modifier.height(8.dp))
        }
        if (editor.signers.isEmpty()) {
            ZillitNotice(
                text = str(S.desktop_ds_add_at_least_one_signer_everyone_you_add),
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
                str(S.desktop_ds_add_signer),
                onClick = { onEvent(EsignEvent.EditCompose { copy(signerPickerOpen = true, pickerSearch = "") }) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.UserPlus,
            )
            ZillitButton(
                str(S.desktop_ds_external_signer_by_email),
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
        BlockTitle(str(S.desktop_ds_receives_a_copy)) {
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
                str(S.desktop_ds_add_cc),
                onClick = { onEvent(EsignEvent.EditCompose { copy(ccPickerOpen = true, pickerSearch = "") }) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.UserPlus,
            )
            ZillitButton(
                str(S.desktop_ds_external_cc_by_email),
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
                if (recipient.isExternal) ZillitTag(str(S.docusign_row_external_chip), tone = TagTone.Info)
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
                    str(S.desktop_ds_pick_a_real_user_before_sending),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.warning,
                    maxLines = 1,
                )
            }
        }
        if (onUp != null || onDown != null) {
            ZillitIconButton(
                ZillitIcons.ChevronUp,
                str(S.docusign_send_confirm_move_up),
                onClick = onUp ?: {},
                enabled = onUp != null,
                size = 22.dp,
            )
            ZillitIconButton(
                ZillitIcons.ChevronDown,
                str(S.docusign_send_confirm_move_down),
                onClick = onDown ?: {},
                enabled = onDown != null,
                size = 22.dp,
            )
        }
        ZillitIconButton(ZillitIcons.Close, str(S.remove), onClick = onRemove, size = 22.dp)
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
        title = if (forCc) str(S.desktop_ds_add_a_cc) else str(S.desktop_ds_add_a_signer),
        subtitle = str(S.project_users),
        visible = open,
        onDismiss = { onEvent(EsignEvent.EditCompose { copy(signerPickerOpen = false, ccPickerOpen = false) }) },
        icon = ZillitIcons.Users,
        width = 520.dp,
        scrollable = false,
    ) {
        ZillitSearchField(
            value = editor.pickerSearch,
            onValueChange = { q -> onEvent(EsignEvent.EditCompose { copy(pickerSearch = q) }) },
            placeholder = str(S.av_search_project_users),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        ZillitScrollColumn(Modifier.fillMaxWidth().height(360.dp)) {
            if (matches.isEmpty()) {
                ZillitText(
                    str(S.desktop_nobody_matches),
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
                            option.email.ifBlank { str(S.desktop_ds_no_email_on_the_crew_list_add_by) },
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
        title = if (draft?.role == EnvelopeRecipient.ROLE_CC) {
            str(S.desktop_ds_external_cc)
        } else {
            str(S.desktop_ds_external_signer)
        },
        subtitle = str(S.desktop_ds_someone_outside_the_production_they_sign_from_an),
        visible = draft != null,
        onDismiss = { onEvent(EsignEvent.CloseExternal) },
        scrollable = false,
        icon = ZillitIcons.Mail,
        width = 460.dp,
        actions = {
            ZillitButton(
                str(S.cancel),
                onClick = { onEvent(EsignEvent.CloseExternal) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                str(S.add),
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
                label = str(S.name),
                placeholder = str(S.dd_preview_sample_name),
            )
            ZillitTextField(
                value = draft.email,
                onValueChange = { onEvent(EsignEvent.EditExternal(draft.copy(email = it))) },
                label = str(S.email),
                placeholder = "jane@example.com",
                errorText = if (draft.email.isNotBlank() && !draft.emailValid) {
                    str(S.docusign_role_email_invalid)
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun ConfirmSendDialog(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = str(S.desktop_ds_send_this_envelope),
        subtitle = editor.title,
        visible = editor.confirmSend,
        onDismiss = { onEvent(EsignEvent.CancelSend) },
        scrollable = false,
        icon = ZillitIcons.Send,
        width = 480.dp,
        actions = {
            ZillitButton(
                str(S.desktop_ad_not_yet),
                onClick = { onEvent(EsignEvent.CancelSend) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                str(S.desktop_ds_send_now),
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
                    str(S.desktop_ds_signers_are_notified_one_at_a_time_in)
                } else {
                    str(S.desktop_ds_every_signer_is_notified_at_once)
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
                        str(
                            S.desktop_ds_signer_field_count,
                            s.name.ifBlank { s.email },
                            editor.fieldsOf(editor.recipients.indexOf(s)).size,
                        ),
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
        title = if (editor.templateId != null) str(S.update_template) else str(S.docusign_save_as_template),
        subtitle = str(S.desktop_ds_signers_are_stripped_to_role_slots_the_document),
        visible = sheet != null,
        onDismiss = { onEvent(EsignEvent.CancelSaveAsTemplate) },
        scrollable = false,
        icon = ZillitIcons.Save,
        width = 480.dp,
        actions = {
            ZillitButton(
                str(S.cancel),
                onClick = { onEvent(EsignEvent.CancelSaveAsTemplate) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                if (editor.templateId != null) str(S.update) else str(S.docusign_save_template),
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
                label = str(S.name),
                placeholder = str(S.desktop_ds_crew_deal_memo_standard),
            )
            ZillitTextField(
                value = sheet.category,
                onValueChange = { onEvent(EsignEvent.EditSaveAsTemplate(sheet.copy(category = it))) },
                label = str(S.av_category),
                placeholder = str(S.desktop_ds_category_hint),
                helperText = editor.categories.takeIf { it.isNotEmpty() }?.let { "In use: ${it.joinToString(", ")}" },
            )
            ZillitTextField(
                value = sheet.description,
                onValueChange = { onEvent(EsignEvent.EditSaveAsTemplate(sheet.copy(description = it))) },
                label = str(S.description),
                placeholder = str(S.desktop_ds_what_this_template_is_for),
                singleLine = false,
            )
        }
    }
}

private val REMINDER_CHOICES: List<Int?> = listOf(null, 1, 3, 7)
private val EXPIRY_CHOICES: List<Int?> = listOf(null, 7, 14, 30, 60, 90)
