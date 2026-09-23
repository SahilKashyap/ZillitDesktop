package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.authoring.BuilderSeeds
import com.zillit.desktop.feature.dealmemo.domain.authoring.IssuePreview
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoCard
import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.builder.NameIntent
import com.zillit.desktop.feature.dealmemo.ui.builder.payloadContext
import com.zillit.desktop.feature.dealmemo.ui.components.DmButton
import com.zillit.desktop.feature.dealmemo.ui.components.DmButtonStyle
import com.zillit.desktop.feature.dealmemo.ui.components.DmConfirm
import com.zillit.desktop.feature.dealmemo.ui.components.DmConfirmKind
import com.zillit.desktop.feature.dealmemo.ui.components.DmModal
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.FileViewerModal
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.MemoCardView
import com.zillit.desktop.feature.dealmemo.ui.preview.DealPreviewActions
import kotlin.time.Clock

/** Every overlay the builder raises, over the whole page. */
@Composable
internal fun BuilderDialogs(state: DealMemoUiState, builder: BuilderState, onEvent: (DealMemoEvent) -> Unit) {
    IssuePreviewOverlay(state, builder, onEvent)
    FileViewerModal(
        viewer = builder.viewer,
        onClose = { onEvent(BuilderEvent.CloseViewer) },
        onDownload = { onEvent(BuilderEvent.DownloadViewer) },
    )
    ValidationModal(builder, onEvent)
    NominalPrompt(builder, onEvent)
    NameModal(builder, onEvent)
    SetupNameModal(builder, onEvent)
    LeaveGuard(builder, onEvent)
    if (builder.mode.setup) CompanyDialogs(state, builder, onEvent)
    // Reverting discards rule edits never sent anywhere, so it asks; dismissing keeps them.
    DmConfirm(
        visible = builder.confirmResetRules,
        title = str(S.desktop_dm_reset_pay_rules),
        message = str(S.desktop_dm_discard_the_rule_changes_made_since_this),
        confirmLabel = str(S.desktop_dm_reset_rules),
        cancelLabel = str(S.desktop_dm_keep_changes),
        kind = DmConfirmKind.Warning,
        onConfirm = { onEvent(BuilderEvent.ResetRules) },
        onCancel = { onEvent(BuilderEvent.ConfirmResetRules(open = false)) },
    )
}

/** The shared validation popup: its message, then what is missing on a red panel. */
@Composable
private fun ValidationModal(builder: BuilderState, onEvent: (DealMemoEvent) -> Unit) {
    val validation = builder.validation
    DmModal(
        visible = validation != null,
        title = validation?.title.orEmpty(),
        onDismiss = { onEvent(BuilderEvent.CloseValidation) },
        maxWidth = 480.dp,
        footer = { DmButton("OK", { onEvent(BuilderEvent.CloseValidation) }, DmButtonStyle.ModalPrimary) },
    ) {
        validation ?: return@DmModal
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            ZillitText(
                text = validation.message,
                style = DmType.sans(12.sp).copy(lineHeight = 19.sp),
                color = Color(0xFF6B7280),
            )
            if (validation.fields.isNotEmpty()) {
                val shape = RoundedCornerShape(8.dp)
                Column(
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth()
                        .clip(shape)
                        .background(bp.redSoft)
                        .border(1.dp, bp.redBorder, shape)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZillitText(
                        text = "MISSING",
                        style = DmType.display(10.sp, FontWeight.SemiBold, 0.1.em),
                        color = Color(0xFFDC2626),
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    validation.fields.forEach { field ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.size(4.dp).clip(CircleShape).background(bp.red))
                            ZillitText(
                                text = field,
                                style = DmType.sans(12.sp),
                                color = bp.ink,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Nominal codes not added" — Now opens Nominal Coding, Later issues without them. */
@Composable
private fun NominalPrompt(builder: BuilderState, onEvent: (DealMemoEvent) -> Unit) {
    DmModal(
        visible = builder.nominalPrompt,
        title = str(S.desktop_dm_nominal_codes_not_added),
        onDismiss = { onEvent(BuilderEvent.CloseNominalPrompt) },
        maxWidth = 480.dp,
        footer = {
            DmButton(str(S.dm_nominals_now), { onEvent(BuilderEvent.NominalsNow) }, DmButtonStyle.ModalPrimary)
            DmButton(
                str(S.dm_nominals_later),
                { onEvent(BuilderEvent.NominalsLater) },
                DmButtonStyle.ModalNeutral,
                enabled = !builder.saving && !builder.submitting,
            )
        },
    ) {
        ZillitText(
            text = str(S.desktop_dm_the_nominals_havent_been_added_to_the),
            style = DmType.sans(12.sp).copy(lineHeight = 19.sp),
            color = Color(0xFF6B7280),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
        )
    }
}

/** "Name this Deal Memo": the crew member's name before a nameless deal is saved or issued. */
@Composable
private fun NameModal(builder: BuilderState, onEvent: (DealMemoEvent) -> Unit) {
    val capture = builder.nameCapture
    val issue = capture?.intent == NameIntent.Issue
    val busy = if (issue) builder.submitting else builder.saving
    DmModal(
        visible = capture != null,
        title = str(S.desktop_dm_name_this_deal_memo),
        onDismiss = { onEvent(BuilderEvent.CancelName) },
        maxWidth = 420.dp,
        closeOnBackdrop = false,
        footer = {
            DmButton(str(S.dm_cancel), { onEvent(BuilderEvent.CancelName) }, DmButtonStyle.ModalNeutral)
            BrandButton(
                text = when {
                    issue && busy -> str(S.dm_quick_issuing)
                    issue -> str(S.dm_quick_issue)
                    busy -> str(S.dm_nda_saving)
                    else -> str(S.dm_wizard_save_draft)
                },
                enabled = capture?.name?.isNotBlank() == true && !busy,
                onClick = { onEvent(BuilderEvent.ConfirmName) },
            )
        },
    ) {
        capture ?: return@DmModal
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            ZillitText(
                text = if (issue) str(S.desktop_dm_name_prompt_issue) else str(S.desktop_dm_name_prompt_draft),
                style = DmType.sans(12.sp).copy(lineHeight = 18.sp),
                color = Color(0xFF6B7280),
                modifier = Modifier.padding(bottom = 16.dp),
            )
            AutofocusInput(
                value = capture.name,
                placeholder = str(S.desktop_dm_e_g_sarah_mitchell),
                onValueChange = { onEvent(BuilderEvent.EditName(it)) },
                onEnter = { if (capture.name.isNotBlank()) onEvent(BuilderEvent.ConfirmName) },
            )
        }
    }
}

/** Save / Update {kind}: the setup's name, then the save. */
@Composable
private fun SetupNameModal(builder: BuilderState, onEvent: (DealMemoEvent) -> Unit) {
    val editing = builder.mode.templateId != null
    val kind = BuilderSeeds.setupKindLabel(builder.form)
    DmModal(
        visible = builder.setupNamePrompt,
        title = if (editing) str(S.desktop_dm_update_kind, kind) else str(S.desktop_dm_save_kind, kind),
        onDismiss = { onEvent(BuilderEvent.CloseSetupName) },
        maxWidth = 460.dp,
        dismissible = !builder.savingTemplate,
        footer = {
            BrandButton(
                text = when {
                    editing && builder.savingTemplate -> str(S.desktop_dm_updating)
                    editing -> str(S.update)
                    builder.savingTemplate -> str(S.dm_nda_saving)
                    else -> str(S.dm_save)
                },
                enabled = builder.templateName.isNotBlank() && !builder.savingTemplate,
                onClick = { onEvent(BuilderEvent.ConfirmSetupName) },
            )
        },
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            ZillitText(
                text = str(S.desktop_dm_please_fill_in_the_name),
                style = DmType.sans(15.sp, FontWeight.SemiBold),
                color = bp.title,
            )
            if (editing) {
                ZillitText(
                    text = str(S.desktop_dm_your_changes_are_already_saved_confirming_the),
                    style = DmType.sans(13.sp),
                    color = Color(0xFF4B5563),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            AutofocusInput(
                value = builder.templateName,
                placeholder = str(S.desktop_dm_e_g_uk_scripted_camera_hod),
                onValueChange = { onEvent(BuilderEvent.EditSetupName(it)) },
                onEnter = { if (builder.templateName.isNotBlank()) onEvent(BuilderEvent.ConfirmSetupName) },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** "Save as draft?" and its three siblings — the copy follows what the buttons really do. */
@Composable
private fun LeaveGuard(builder: BuilderState, onEvent: (DealMemoEvent) -> Unit) {
    val mode = builder.mode
    val (title, body, primary) = when {
        mode.setup && mode.templateId != null -> Triple(
            str(S.dm_quick_exit_title_save),
            str(S.desktop_dm_you_have_unsaved_changes_to_this_setup_2),
            str(S.dm_builder_update),
        )
        mode.setup -> Triple(
            str(S.desktop_dm_save_this_setup),
            str(S.desktop_dm_you_have_unsaved_changes_to_this_setup),
            str(S.dm_builder_save),
        )
        builder.dealId != null -> Triple(
            str(S.dm_quick_exit_title_save),
            str(S.dm_quick_exit_body_save),
            if (builder.saving) str(S.dm_nda_saving) else str(S.dm_wizard_save_submit),
        )
        else -> Triple(
            str(S.dm_quick_exit_title_draft),
            str(S.dm_quick_exit_body_draft),
            if (builder.saving) str(S.dm_nda_saving) else str(S.dm_quick_save_draft),
        )
    }
    DmModal(
        visible = builder.leaveGuard,
        title = title,
        onDismiss = { onEvent(BuilderEvent.KeepEditing) },
        maxWidth = 460.dp,
        footer = {
            DmButton(str(S.dm_quick_exit_keep), { onEvent(BuilderEvent.KeepEditing) }, DmButtonStyle.ModalNeutral)
            DmButton(
                str(S.dm_quick_exit_leave),
                { onEvent(BuilderEvent.LeaveWithoutSaving) },
                DmButtonStyle.ModalDangerText,
            )
            DmButton(
                primary,
                { onEvent(BuilderEvent.LeaveAndSave) },
                DmButtonStyle.ModalPrimary,
                enabled = !builder.saving,
            )
        },
    ) {
        ZillitText(
            text = body,
            style = DmType.sans(12.sp).copy(lineHeight = 19.sp),
            color = Color(0xFF6B7280),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
        )
    }
}

/**
 * "Review before issuing": the memo card as it would issue, over the page.
 * No backdrop close — a stray click would cost the whole read-through.
 */
@Suppress("LongMethod")
@Composable
private fun IssuePreviewOverlay(state: DealMemoUiState, builder: BuilderState, onEvent: (DealMemoEvent) -> Unit) {
    if (builder.issuePreview == null) return
    val p = bp
    val card = remember(builder.form, builder.reference, state.catalogue, state.crewDirectory, state.production) {
        val deal = IssuePreview.deal(
            builder.form,
            payloadContext(state, builder),
            now = Clock.System.now().toEpochMilliseconds(),
        )
        MemoCard.build(deal, DealPreviewActions.memoContext(state))
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = BACKDROP_ALPHA))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(16.dp)
        Column(
            modifier = Modifier
                .widthIn(max = 1024.dp)
                .fillMaxWidth()
                .fillMaxHeight(PREVIEW_HEIGHT)
                .shadow(24.dp, shape)
                .clip(shape)
                .background(if (ZillitTheme.colors.isDark) Color(0xFF0B0D11) else p.page)
                .border(1.dp, Color.Black.copy(alpha = 0.08f), shape),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().background(p.card).padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    ZillitText(
                        text = str(S.desktop_dm_review_before_issuing),
                        style = DmType.sans(10.sp, FontWeight.Bold, 0.09.em),
                        color = Color(0xFFEA7A0E),
                    )
                    ZillitText(
                        text = builder.form.text("fullLegalName")
                            .trim()
                            .ifEmpty { builder.form.text("crewName").trim() }
                            .ifEmpty { str(S.dm_title) },
                        style = DmType.sans(16.sp, FontWeight.Bold, (-0.02).em),
                        color = p.title,
                        maxLines = 1,
                    )
                }
                OutlinedButton(str(S.desktop_dm_back_to_deal), enabled = !builder.submitting) {
                    onEvent(BuilderEvent.CloseIssuePreview)
                }
                GreenButton(
                    if (builder.submitting) str(S.dm_quick_issuing) else str(S.dm_quick_issue),
                    enabled = !builder.submitting,
                ) {
                    onEvent(BuilderEvent.ConfirmIssuePreview)
                }
            }
            Rule(p.hairline)
            ZillitScrollColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.widthIn(max = 920.dp).fillMaxWidth()) { MemoCardView(card, onEvent) }
            }
        }
    }
}

@Composable
private fun AutofocusInput(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BuilderInput(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        onEnter = onEnter,
        modifier = modifier.focusRequester(focus),
    )
}

/** The amber modal button with white text — Save Draft, Save, Update. */
@Composable
private fun BrandButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val p = bp
    val (source, hovered) = rememberHover()
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered && enabled) p.brandHover else p.brand)
            .hoverable(source)
            .then(
                if (enabled) {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { ZillitText(text = text, style = DmType.sans(12.sp, FontWeight.SemiBold), color = Color.White, maxLines = 1) }
}

@Composable
private fun OutlinedButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val p = bp
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .height(36.dp)
            .clip(shape)
            .background(if (hovered && enabled) p.chipHover else p.chipBg)
            .border(1.dp, p.hairline, shape)
            .hoverable(source)
            .then(
                if (enabled) {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { ZillitText(text = text, style = DmType.sans(13.sp, FontWeight.SemiBold), color = p.ink2, maxLines = 1) }
}

@Composable
private fun GreenButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val p = bp
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .height(36.dp)
            .clip(shape)
            .background(if (hovered && enabled) p.greenHover else p.green)
            .hoverable(source)
            .then(
                if (enabled) {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { ZillitText(text = text, style = DmType.sans(13.sp, FontWeight.Bold), color = Color.White, maxLines = 1) }
}

private const val DISABLED_ALPHA = 0.5f
private const val BACKDROP_ALPHA = 0.4f
private const val PREVIEW_HEIGHT = 0.92f
