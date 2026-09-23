@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod", "ComplexCondition")

package com.zillit.desktop.feature.esignature.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.esignature.domain.EnvelopeField
import com.zillit.desktop.feature.esignature.domain.EsignFormat
import com.zillit.desktop.feature.esignature.domain.FieldAnswer
import com.zillit.desktop.feature.esignature.domain.FieldOption
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.FieldValue
import com.zillit.desktop.feature.esignature.ui.EsignEvent
import com.zillit.desktop.feature.esignature.ui.EsignUiState
import com.zillit.desktop.feature.esignature.ui.PadMode
import com.zillit.desktop.feature.esignature.ui.SigningMode
import com.zillit.desktop.feature.esignature.ui.SigningState
import com.zillit.desktop.feature.esignature.ui.components.BytesImage
import com.zillit.desktop.feature.esignature.ui.components.EnvelopeStatusPill
import com.zillit.desktop.feature.esignature.ui.components.FieldRect
import com.zillit.desktop.feature.esignature.ui.components.Hairline
import com.zillit.desktop.feature.esignature.ui.components.PageCanvas
import com.zillit.desktop.feature.esignature.ui.components.PageScale
import com.zillit.desktop.feature.esignature.ui.components.SignaturePad
import com.zillit.desktop.feature.esignature.ui.components.signerColor
import com.zillit.desktop.feature.esignature.ui.flows.SigningFlow

/**
 * The signing surface — the web's `SigningView`: the consent gate, the
 * document with this signer's fields, a panel that walks them in order,
 * and Finish & Submit once every required field is done. The same page
 * shows a completed envelope's stamped values read-only.
 */
@Composable
internal fun SigningPage(state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val signing = state.signing ?: return
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxSize()) {
        SigningHeader(signing, onEvent)
        if (signing.finished) {
            FinishedScreen(signing, onEvent)
            return@Column
        }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().background(colors.surfaceSunken)) {
                when {
                    signing.notPdf -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ZillitText(
                            str(S.desktop_ds_this_envelope_s_document_could_not_be_previewed),
                            color = colors.textMuted,
                        )
                    }
                    signing.loadingPages -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { ZillitSpinner() }
                    else -> ZillitScrollColumn(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Spacer(Modifier.height(4.dp))
                        signing.pages.forEach { page ->
                            PageCanvas(page = page, zoom = PAGE_ZOOM) { scale ->
                                PageOverlays(signing, page.page, scale, onEvent)
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
            SidePanel(signing, state, onEvent)
        }
    }
    ConsentGate(signing, onEvent)
    PadDialog(signing, state, onEvent)
    DeclineDialog(signing, onEvent)
}

@Composable
private fun SigningHeader(signing: SigningState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ZillitIconButton(ZillitIcons.ArrowLeft, str(S.docusign_back), onClick = { onEvent(EsignEvent.Back) })
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitText(
                        signing.envelope.title.ifBlank { str(S.docusign_section_document) },
                        style = ZillitTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier.widthIn(max = 520.dp),
                    )
                    EnvelopeStatusPill(signing.envelope.status)
                }
                ZillitText(
                    when {
                        signing.mode == SigningMode.Plain -> str(S.desktop_ds_the_original_document_as_uploaded)
                        signing.mode == SigningMode.ViewSigned -> if (signing.envelope.status.wire == "completed") {
                            str(S.desktop_ds_signed_copy)
                        } else {
                            str(S.desktop_ds_read_only_values_submitted_so_far)
                        }
                        signing.me != null -> "Signing as ${signing.me.name.ifBlank { signing.me.email }}"
                        else -> str(S.preview)
                    },
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            if (!signing.readOnly && !signing.needsConsent) {
                val current = signing.current
                Row(
                    Modifier.clip(ZillitTheme.shapes.pill).background(colors.surfaceSunken).border(
                        1.dp,
                        colors.border,
                        ZillitTheme.shapes.pill,
                    ).padding(
                        horizontal = 4.dp,
                        vertical = 2.dp,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitIconButton(
                        ZillitIcons.ChevronLeft,
                        str(S.desktop_ds_previous_field),
                        onClick = { onEvent(EsignEvent.PrevField) },
                        size = 24.dp,
                    )
                    ZillitText(
                        if (current != null) {
                            "${current.type.label} · ${signing.currentIndex + 1} / ${signing.visibleFields.size}"
                        } else {
                            str(S.desktop_no_fields)
                        },
                        style = ZillitTheme.typography.label,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    ZillitIconButton(
                        ZillitIcons.ChevronRight,
                        str(S.desktop_ds_next_field),
                        onClick = { onEvent(EsignEvent.NextField) },
                        size = 24.dp,
                    )
                }
                ZillitButton(
                    str(S.docusign_signing_decline),
                    onClick = { onEvent(EsignEvent.StartDecline) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    str(S.docusign_signing_finish_submit),
                    onClick = { onEvent(EsignEvent.FinishSigning) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Check,
                    enabled = signing.canFinish,
                    loading = signing.submitting,
                )
            }
        }
        Hairline()
    }
}

// ---------------------------------------------------------------- overlays

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PageOverlays(
    signing: SigningState,
    page: Int,
    scale: PageScale,
    onEvent: (EsignEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    when (signing.mode) {
        SigningMode.Plain -> Unit
        SigningMode.ViewSigned -> signing.envelope.fields.filter { it.page == page }.forEach { field ->
            val owner = signing.envelope.signers.indexOfFirst { it.id == field.recipientId }.takeIf { it >= 0 }
                ?: signing.envelope.recipients.getOrNull(field.recipientIndex)
                    ?.let { signing.envelope.signers.indexOf(it) }
                ?: 0
            FieldRect(
                field,
                scale,
                Modifier.border(1.dp, signerColor(owner).copy(alpha = 0.35f), RoundedCornerShape(2.dp)),
            ) {
                StampedValue(field, field.value, signing.images, scale)
            }
        }
        SigningMode.Sign -> {
            if (signing.needsConsent) return
            signing.visibleFields.forEachIndexed { index, field ->
                if (field.page != page) return@forEachIndexed
                val current = index == signing.currentIndex
                val answer = signing.answers[field.id]
                val done = signing.isDone(field)
                FieldRect(
                    field,
                    scale,
                    Modifier
                        .background(
                            when {
                                done && answer != null -> Color.Transparent
                                current -> colors.accent.copy(alpha = 0.22f)
                                else -> colors.accent.copy(alpha = 0.10f)
                            },
                            RoundedCornerShape(3.dp),
                        )
                        .border(
                            if (current) 2.dp else 1.dp,
                            if (current) {
                                colors.accent
                            } else {
                                if (done) colors.success else colors.accent.copy(alpha = 0.6f)
                            },
                            RoundedCornerShape(3.dp),
                        )
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable {
                            onEvent(EsignEvent.FocusField(index))
                            if (field.type.isMark && answer == null) onEvent(EsignEvent.OpenPad)
                        },
                ) {
                    if (answer != null) {
                        AnsweredValue(field, answer, signing.images, scale)
                    } else {
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (!field.required) {
                                Box(
                                    Modifier.clip(ZillitTheme.shapes.pill).background(colors.surface)
                                        .padding(horizontal = 4.dp),
                                ) {
                                    ZillitText(
                                        str(S.docusign_signing_optional),
                                        style = ZillitTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                        color = colors.textMuted,
                                    )
                                }
                            }
                            ZillitText(
                                when {
                                    field.type == FieldType.SignHere -> str(S.docusign_signing_sign_here)
                                    field.type == FieldType.InitialHere -> str(S.docusign_place_field_initial)
                                    else -> field.label.ifBlank { field.type.label }
                                },
                                style = ZillitTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = colors.accentText,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StampedValue(field: EnvelopeField, value: FieldValue, images: Map<String, ByteArray>, scale: PageScale) {
    when (value) {
        is FieldValue.File -> BytesImage(
            images[value.file.media],
            field.type.label,
            Modifier.fillMaxSize().padding(1.dp),
        )
        is FieldValue.Text -> StampedText(field, textFor(field, value.text), scale)
        FieldValue.None -> Unit
    }
}

@Composable
private fun AnsweredValue(field: EnvelopeField, answer: FieldAnswer, images: Map<String, ByteArray>, scale: PageScale) {
    when (answer) {
        is FieldAnswer.Mark -> BytesImage(
            images[answer.image.media],
            field.type.label,
            Modifier.fillMaxSize().padding(1.dp),
        )
        is FieldAnswer.Typed -> StampedText(field, answer.text, scale)
        is FieldAnswer.Ticked -> StampedText(field, if (answer.checked) "☑" else "☐", scale)
        is FieldAnswer.Chosen -> StampedText(
            field,
            field.options.firstOrNull { it.id == answer.optionId }?.label ?: answer.optionId,
            scale,
        )
    }
}

/** The web's `SIGNED_TEXT_TYPOGRAPHY`: 0.38 × box height, clamped 9–12pt, then scaled. */
@Composable
private fun StampedText(field: EnvelopeField, text: String, scale: PageScale) {
    val pt = (field.height * TEXT_RATIO).coerceIn(TEXT_MIN_PT, TEXT_MAX_PT)
    Box(Modifier.fillMaxSize().padding(horizontal = 3.dp), contentAlignment = Alignment.CenterStart) {
        ZillitText(
            text,
            style = ZillitTheme.typography.bodySmall.copy(
                fontSize = (pt * scale.dpPerPoint).sp,
                lineHeight = (pt * scale.dpPerPoint * 1.15).sp,
            ),
            color = Color(0xFF111827),
            maxLines = 3,
        )
    }
}

private fun textFor(field: EnvelopeField, raw: String): String = when {
    field.type == FieldType.Checkbox -> if (raw == "true") "☑" else "☐"
    field.type.hasOptions -> field.options.firstOrNull { it.id == raw }?.label ?: raw
    else -> raw
}

// ---------------------------------------------------------------- side panel

@Composable
private fun SidePanel(signing: SigningState, state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(Modifier.width(360.dp).fillMaxHeight().background(colors.surface)) {
        if (signing.readOnly || signing.needsConsent) {
            ReadOnlyPanel(signing, state)
        } else {
            SignPanel(signing, state, onEvent)
        }
    }
}

@Composable
private fun ReadOnlyPanel(signing: SigningState, state: EsignUiState) {
    val colors = ZillitTheme.colors
    val envelope = signing.envelope
    ZillitScrollColumn(
        Modifier.fillMaxSize().padding(RAIL_PADDING),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitText(envelope.title, style = ZillitTheme.typography.titleSmall)
        if (envelope.description.isNotBlank()) ZillitText(
            envelope.description,
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
        Hairline()
        ZillitText(str(S.signers), style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        envelope.signers.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(signerColor(i)))
                Column(Modifier.weight(1f)) {
                    ZillitText(s.name.ifBlank { s.email }, style = ZillitTheme.typography.bodySmall, maxLines = 1)
                    s.signedOn?.let {
                        ZillitText(
                            "Signed ${EsignFormat.dateTime(it)}",
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.textMuted,
                        )
                    }
                }
                ZillitStatusPill(
                    label = s.statusLabel,
                    tone = if (s.signed) {
                        StatusTone.Done
                    } else {
                        if (s.declined) StatusTone.Rejected else StatusTone.Pending
                    },
                )
            }
        }
        if (signing.mode == SigningMode.Sign && signing.me?.signed == true) {
            Hairline()
            ZillitText(
                str(S.desktop_ds_you_have_already_signed_this_document),
                style = ZillitTheme.typography.bodySmall,
                color = colors.success,
            )
        }
        if (signing.mode == SigningMode.Sign && signing.me == null && !signing.needsConsent) {
            Hairline()
            ZillitText(
                str(S.desktop_ds_you_are_not_a_signer_on_this_envelope),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        Spacer(Modifier.weight(1f))
        ZillitText(
            "Signed as ${state.currentUserEmail.ifBlank { "you" }}",
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )
    }
}

@Composable
private fun SignPanel(signing: SigningState, state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val fields = signing.visibleFields
    ZillitScrollColumn(
        Modifier.fillMaxSize().padding(RAIL_PADDING),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                if (signing.hasOptional) str(S.desktop_ds_fields_to_sign) else str(S.desktop_ds_required_fields),
                style = ZillitTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                "${signing.completedRequired}/${signing.requiredCount}",
                style = ZillitTheme.typography.label,
                color = colors.accentText,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            fields.forEachIndexed { index, field ->
                val done = signing.isDone(field)
                val current = index == signing.currentIndex
                Box(
                    Modifier.size(28.dp).clip(CircleShape)
                        .background(
                            when {
                                current -> colors.accent
                                done -> colors.successSoft
                                else -> colors.surfaceSunken
                            },
                        )
                        .border(
                            1.dp,
                            if (current) {
                                colors.accent
                            } else {
                                if (done) colors.success else if (field.required) colors.border else colors.textMuted
                            },
                            CircleShape,
                        )
                        .clickable { onEvent(EsignEvent.FocusField(index)) }
                        .pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done && !current) {
                        ZillitIcon(ZillitIcons.Check, tint = colors.success, size = 13.dp)
                    } else {
                        ZillitText(
                            "${index + 1}",
                            style = ZillitTheme.typography.labelSmall,
                            color = if (current) Color.White else colors.textSecondary,
                        )
                    }
                }
            }
        }
        val autoInitials = signing.myFields.count { it.autoInitial }
        if (autoInitials > 1) {
            ZillitSwitch(
                checked = signing.signOnce,
                onCheckedChange = { onEvent(EsignEvent.SetSignOnce(it)) },
                label = "Sign once for every page ($autoInitials initials)",
            )
        }
        val current = signing.current
        if (current == null) {
            ZillitText(
                str(S.desktop_ds_nothing_to_fill_everything_on_this_document_is),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        } else {
            CurrentFieldCard(signing, current, state, onEvent)
        }
        Spacer(Modifier.weight(1f))
        Hairline()
        ZillitText(
            if (signing.allRequiredDone) {
                str(S.desktop_ds_everything_required_is_done_click_finish_submit_to)
            } else {
                str(S.desktop_ds_complete_every_required_field_to_unlock_finish_submit)
            },
            style = ZillitTheme.typography.bodySmall,
            color = if (signing.allRequiredDone) colors.success else colors.textMuted,
        )
        ZillitText(
            str(S.desktop_ds_by_clicking_finish_submit_you_consent_to_sign),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )
        ZillitButton(
            str(S.docusign_signing_finish_submit),
            onClick = { onEvent(EsignEvent.FinishSigning) },
            leadingIcon = ZillitIcons.Check,
            enabled = signing.canFinish,
            loading = signing.submitting,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CurrentFieldCard(
    signing: SigningState,
    field: EnvelopeField,
    state: EsignUiState,
    onEvent: (EsignEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val answer = signing.answers[field.id]
    Column(
        Modifier.fillMaxWidth().clip(ZillitTheme.shapes.large).background(colors.surfaceSunken).border(
            1.dp,
            colors.accent.copy(alpha = 0.5f),
            ZillitTheme.shapes.large,
        ).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                ZillitText(field.label.ifBlank { field.type.label }, style = ZillitTheme.typography.titleSmall)
                ZillitText(
                    "${field.type.label} · page ${field.page}",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            when {
                field.locked -> ZillitTag(str(S.desktop_ds_locked_pre_filled), tone = TagTone.Neutral)
                field.required -> ZillitTag(str(S.docusign_signing_required), tone = TagTone.Accent)
                else -> ZillitTag(str(S.docusign_signing_optional), tone = TagTone.Neutral)
            }
        }
        FieldInput(signing, field, answer, state, onEvent)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZillitButton(
                str(S.docusign_prev),
                onClick = { onEvent(EsignEvent.PrevField) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = signing.currentIndex > 0,
            )
            Spacer(Modifier.weight(1f))
            if (answer != null && !field.locked) {
                ZillitButton(
                    str(S.txt_clear),
                    onClick = { onEvent(EsignEvent.Answer(field.id, null)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            ZillitButton(
                str(S.docusign_signing_done_next),
                onClick = { onEvent(EsignEvent.NextField) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                trailingIcon = ZillitIcons.ArrowRight,
            )
        }
    }
}

@Composable
private fun FieldInput(
    signing: SigningState,
    field: EnvelopeField,
    answer: FieldAnswer?,
    state: EsignUiState,
    onEvent: (EsignEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    if (field.locked) {
        ZillitText(
            field.defaultValue.ifBlank { "—" },
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        return
    }
    when {
        field.type.isMark -> {
            val mark = answer as? FieldAnswer.Mark
            Box(
                Modifier.fillMaxWidth().height(96.dp).clip(ZillitTheme.shapes.medium).background(Color.White).border(
                    1.dp,
                    colors.border,
                    ZillitTheme.shapes.medium,
                )
                    .clickable { onEvent(EsignEvent.OpenPad) },
                contentAlignment = Alignment.Center,
            ) {
                if (mark != null) {
                    BytesImage(signing.images[mark.image.media], field.type.label, Modifier.fillMaxSize().padding(6.dp))
                } else {
                    ZillitText(
                        if (field.type == FieldType.SignHere) {
                            str(S.desktop_ds_click_to_sign)
                        } else {
                            str(S.desktop_ds_click_to_initial)
                        },
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
            val sameType = signing.visibleFields.count {
                it.type == field.type && it.id != field.id && !signing.answers.containsKey(it.id)
            }
            if (sameType > 0) {
                ZillitSwitch(
                    checked = signing.applyToAll,
                    onCheckedChange = { onEvent(EsignEvent.SetApplyToAll(it)) },
                    label = "Apply to all $sameType matching field${if (sameType == 1) "" else "s"}",
                )
            }
            ZillitButton(
                when {
                    mark != null -> str(S.docusign_change)
                    field.type == FieldType.SignHere -> str(S.docusign_signing_sign_here)
                    else -> str(S.txt_add_initials)
                },
                onClick = { onEvent(EsignEvent.OpenPad) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Edit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        field.type.isUpload -> {
            val mark = answer as? FieldAnswer.Mark
            if (mark != null) {
                Box(
                    Modifier.fillMaxWidth()
                        .height(120.dp)
                        .clip(ZillitTheme.shapes.medium)
                        .background(Color.White)
                        .border(
                        1.dp,
                        colors.border,
                        ZillitTheme.shapes.medium,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    val bytes = signing.images[mark.image.media]
                    if (bytes != null) {
                        BytesImage(bytes, field.type.label, Modifier.fillMaxSize().padding(6.dp))
                    } else ZillitText(
                        mark.image.name,
                        style = ZillitTheme.typography.bodySmall,
                    )
                }
            }
            ZillitButton(
                if (mark == null) str(S.desktop_choose_file) else str(S.replace),
                onClick = { onEvent(EsignEvent.PickUploadForField) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Upload,
                modifier = Modifier.fillMaxWidth(),
            )
            ZillitText(
                if (field.type == FieldType.Image) {
                    "PNG or JPG, up to 8 MB — a headshot, an ID, a licence."
                } else {
                    "PDF, PNG or JPG, up to 8 MB."
                },
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
        field.type == FieldType.Checkbox -> ZillitCheckbox(
            checked = (answer as? FieldAnswer.Ticked)?.checked == true,
            onCheckedChange = { onEvent(EsignEvent.Answer(field.id, FieldAnswer.Ticked(it))) },
            label = field.label.ifBlank { "I confirm" },
        )
        field.type == FieldType.Radio -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            field.options.forEach { option ->
                RadioRow(option, selected = (answer as? FieldAnswer.Chosen)?.optionId == option.id) {
                    onEvent(EsignEvent.Answer(field.id, FieldAnswer.Chosen(option.id)))
                }
            }
        }
        field.type == FieldType.Dropdown -> ZillitSelect(
            value = field.options.firstOrNull { it.id == (answer as? FieldAnswer.Chosen)?.optionId },
            options = listOf<FieldOption?>(null) + field.options,
            onSelect = { opt -> onEvent(EsignEvent.Answer(field.id, opt?.let { FieldAnswer.Chosen(it.id) })) },
            label = { it?.label ?: str(S.docusign_field_select_option) },
            modifier = Modifier.fillMaxWidth(),
        )
        field.type == FieldType.Date -> ZillitDateField(
            value = (answer as? FieldAnswer.Typed)?.text.orEmpty(),
            onValueChange = { onEvent(EsignEvent.Answer(field.id, if (it.isBlank()) null else FieldAnswer.Typed(it))) },
            label = field.label.ifBlank { str(S.date) },
        )
        field.type.isAutoStamped -> ZillitText(
            str(S.desktop_ds_stamped_with_the_date_when_you_submit_nothing),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
        field.type == FieldType.FullName -> ZillitTextField(
            value = (answer as? FieldAnswer.Typed)?.text ?: state.currentUserEmail.let { signing.me?.name.orEmpty() },
            onValueChange = { onEvent(EsignEvent.Answer(field.id, FieldAnswer.Typed(it))) },
            label = str(S.full_name),
        )
        else -> {
            val text = (answer as? FieldAnswer.Typed)?.text.orEmpty()
            ZillitTextField(
                value = text,
                onValueChange = {
                    onEvent(EsignEvent.Answer(field.id, if (it.isEmpty()) null else FieldAnswer.Typed(it)))
                },
                label = field.label.ifBlank { field.type.label },
                placeholder = when (field.type) {
                    FieldType.Email -> "name@example.com"
                    FieldType.Phone -> "+44 20 7946 0000"
                    FieldType.Number -> "1,500.00"
                    FieldType.Url -> "https://"
                    else -> str(S.desktop_ds_type_here)
                },
                keyboardType = when (field.type) {
                    FieldType.Email -> KeyboardType.Email
                    FieldType.Phone -> KeyboardType.Phone
                    FieldType.Number -> KeyboardType.Decimal
                    FieldType.Url -> KeyboardType.Uri
                    else -> KeyboardType.Text
                },
                errorText = SigningFlow.validateText(field.type, text),
                maxLength = capacityFor(field),
            )
        }
    }
}

/** The web's `capacityFor`: what the box can show at the smallest legible size. */
private fun capacityFor(field: EnvelopeField): Int? {
    if (field.width <= 0 || field.height <= 0) return null
    val perLine = maxOf(1, ((field.width - PAD_X_PT * 2) / (MIN_RENDER_PT * AVG_CHAR_EM)).toInt())
    val lines = maxOf(1, ((field.height - PAD_Y_PT * 2) / (MIN_RENDER_PT * LINE_HEIGHT)).toInt())
    return perLine * lines
}

@Composable
private fun RadioRow(option: FieldOption, selected: Boolean, onSelect: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium).clickable(onClick = onSelect).padding(
            vertical = 4.dp,
            horizontal = 2.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(16.dp).clip(CircleShape).border(
                2.dp,
                if (selected) colors.accent else colors.borderStrong,
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
        }
        ZillitText(option.label.ifBlank { "(unlabelled)" }, style = ZillitTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------- gate, pad, decline, finished

@Composable
private fun ConsentGate(signing: SigningState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = str(S.desktop_ds_digital_signature_request),
        subtitle = str(S.desktop_ds_please_confirm_before_you_begin_signing),
        visible = signing.needsConsent && !signing.declining && !signing.finished,
        onDismiss = { onEvent(EsignEvent.Back) },
        scrollable = false,
        icon = ZillitIcons.Shield,
        width = 460.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(
                Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium).background(colors.surfaceSunken)
                    .border(1.dp, colors.border, ZillitTheme.shapes.medium).padding(12.dp),
            ) {
                ZillitText(str(S.document), style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
                ZillitText(
                    signing.envelope.title.ifBlank { str(S.docusign_send_confirm_untitled) },
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                signing.me?.let {
                    ZillitText(
                        "Signing as ${it.name.ifBlank { it.email }}",
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
            listOf(
                str(S.desktop_ds_consent_legally_binding),
                str(S.desktop_ds_your_acceptance_is_recorded_with_the_date_and),
                str(S.desktop_ds_you_can_decline_instead_the_sender_is_notified),
            ).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(16.dp).clip(CircleShape).background(colors.surfaceSunken),
                        contentAlignment = Alignment.Center,
                    ) {
                        ZillitIcon(ZillitIcons.Check, tint = colors.textSecondary, size = 9.dp)
                    }
                    ZillitText(
                        line,
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Hairline()
            ZillitButton(
                str(S.desktop_ds_accept_continue),
                onClick = { onEvent(EsignEvent.Consent) },
                leadingIcon = ZillitIcons.Check,
                loading = signing.consenting,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZillitButton(
                    str(S.dm_nda_go_back),
                    onClick = { onEvent(EsignEvent.Back) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.ChevronLeft,
                    modifier = Modifier.weight(1f),
                )
                ZillitButton(
                    str(S.docusign_signing_decline),
                    onClick = { onEvent(EsignEvent.StartDecline) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PadDialog(signing: SigningState, state: EsignUiState, onEvent: (EsignEvent) -> Unit) {
    val pad = signing.pad
    val field = signing.current
    val forSignature = field?.type != FieldType.InitialHere
    ZillitDialogShell(
        title = if (forSignature) str(S.docusign_picker_your_signature) else str(S.docusign_picker_your_initials),
        subtitle = field?.let { "${it.label.ifBlank { it.type.label }} · page ${it.page}" },
        visible = pad != null && field != null,
        onDismiss = { onEvent(EsignEvent.ClosePad) },
        scrollable = false,
        icon = ZillitIcons.Edit,
        width = 560.dp,
        actions = {
            ZillitButton(
                str(S.cancel),
                onClick = { onEvent(EsignEvent.ClosePad) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            if (pad?.mode != PadMode.Saved) {
                ZillitButton(
                    str(S.dm_filter_apply),
                    onClick = { onEvent(EsignEvent.ApplyPad) },
                    size = ButtonSize.Small,
                    enabled = pad?.canApply == true,
                    loading = pad?.busy == true,
                    leadingIcon = ZillitIcons.Check,
                )
            }
        },
    ) {
        if (pad == null) return@ZillitDialogShell
        SignaturePad(
            pad = pad,
            saved = signing.savedMarks,
            savedImages = state.marks.images,
            forSignature = forSignature,
            showSaved = true,
            onMode = { onEvent(EsignEvent.SetPadMode(it)) },
            onStroke = { onEvent(EsignEvent.AddPadStroke(it)) },
            onClear = { onEvent(EsignEvent.ClearPad) },
            onTyped = { onEvent(EsignEvent.EditTypedName(it)) },
            onFont = { onEvent(EsignEvent.SetPadFont(it)) },
            onPickImage = { onEvent(EsignEvent.PickPadImage) },
            onUseSaved = { onEvent(EsignEvent.UseSavedMark(it)) },
            onSaveForLater = { onEvent(EsignEvent.SetSaveForLater(it)) },
        )
    }
}

@Composable
private fun DeclineDialog(signing: SigningState, onEvent: (EsignEvent) -> Unit) {
    ZillitDialogShell(
        title = str(S.docusign_decline_title),
        subtitle = str(S.desktop_ds_the_sender_is_notified_and_the_envelope_is),
        visible = signing.declining,
        onDismiss = { onEvent(EsignEvent.CancelDecline) },
        scrollable = false,
        icon = ZillitIcons.Warning,
        width = 460.dp,
        actions = {
            ZillitButton(
                str(S.desktop_ds_keep_signing),
                onClick = { onEvent(EsignEvent.CancelDecline) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                str(S.docusign_signing_decline),
                onClick = { onEvent(EsignEvent.ConfirmDecline) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
            )
        },
    ) {
        ZillitTextField(
            value = signing.declineReason,
            onValueChange = { onEvent(EsignEvent.EditDeclineReason(it)) },
            label = str(S.av_reason_optional),
            placeholder = str(S.docusign_decline_reason_hint),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FinishedScreen(signing: SigningState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val remaining = signing.envelope.signers.count { !it.signed && it !== signing.me }
    Box(Modifier.fillMaxSize().background(colors.surfaceSunken), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).clip(ZillitTheme.shapes.large).background(colors.surface).border(
                1.dp,
                colors.border,
                ZillitTheme.shapes.large,
            ).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(colors.successSoft),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(ZillitIcons.Check, tint = colors.success, size = 30.dp)
            }
            ZillitText(str(S.desktop_ds_signing_complete_2), style = ZillitTheme.typography.titleLarge)
            ZillitText(
                if (remaining > 0) {
                    str(S.desktop_ds_signed_awaiting_others, remaining)
                } else {
                    str(S.desktop_ds_signed_awaiting_service)
                },
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            ZillitButton(
                str(S.desktop_ds_back_to_documents),
                onClick = { onEvent(EsignEvent.Back) },
                leadingIcon = ZillitIcons.ArrowLeft,
            )
        }
    }
}

/** Room on the right for the scroll rail, so nothing sits under it. */
private val RAIL_PADDING = PaddingValues(start = 16.dp, top = 16.dp, end = 26.dp, bottom = 16.dp)
private const val PAGE_ZOOM = 0.9f
private const val TEXT_RATIO = 0.38
private const val TEXT_MIN_PT = 9.0
private const val TEXT_MAX_PT = 12.0
private const val MIN_RENDER_PT = 8.0
private const val AVG_CHAR_EM = 0.48
private const val LINE_HEIGHT = 1.15
private const val PAD_X_PT = 4.0
private const val PAD_Y_PT = 2.0
