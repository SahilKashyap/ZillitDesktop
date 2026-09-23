// Drawer geometry, and the placement step is one screen.
@file:Suppress("MagicNumber", "LongMethod", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.formsignature.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitMultiSelect
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.formsignature.domain.SignSpotKind
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.FormSignatureUiState
import com.zillit.desktop.feature.formsignature.ui.SendState
import com.zillit.desktop.feature.formsignature.ui.SendStep
import com.zillit.desktop.feature.formsignature.ui.components.DraggableBox
import com.zillit.desktop.feature.formsignature.ui.components.FileChip
import com.zillit.desktop.feature.formsignature.ui.components.MarkImage
import com.zillit.desktop.feature.formsignature.ui.components.PageCanvas
import com.zillit.desktop.feature.formsignature.ui.components.PlaceholderLabel
import com.zillit.desktop.feature.formsignature.ui.components.spotEdge
import com.zillit.desktop.feature.formsignature.ui.components.spotFill

/**
 * Upload Document (for signature) — the web's two-step drawer. Step one
 * is 520 wide; step two grows to most of the window for the pages.
 */
@Composable
internal fun SendDocumentDialog(state: FormSignatureUiState, onEvent: (FormSignatureEvent) -> Unit) {
    val send = state.send
    val placing = send?.step == SendStep.Place
    ZillitDialogShell(
        title = str(S.txt_document_add),
        subtitle = send?.fileName?.takeIf { it.isNotBlank() },
        visible = send != null,
        onDismiss = { onEvent(FormSignatureEvent.CancelSend) },
        icon = ZillitIcons.Send,
        width = if (placing) PLACE_WIDTH.dp else DETAILS_WIDTH.dp,
        maxHeight = if (placing) PLACE_HEIGHT.dp else DETAILS_HEIGHT.dp,
        scrollable = false,
        actions = {
            if (send == null) return@ZillitDialogShell
            if (placing) {
                ZillitButton(
                    text = str(S.back),
                    onClick = { onEvent(FormSignatureEvent.SendBack) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !send.sending,
                )
                ZillitButton(
                    text = str(S.send_document),
                    onClick = { onEvent(FormSignatureEvent.SubmitSend) },
                    size = ButtonSize.Small,
                    loading = send.sending,
                    enabled = !send.sending && !send.busy,
                )
            } else {
                ZillitButton(
                    text = str(S.next),
                    onClick = { onEvent(FormSignatureEvent.SendNext) },
                    size = ButtonSize.Small,
                    loading = send.busy,
                    enabled = !send.busy,
                )
            }
        },
    ) {
        if (send == null) return@ZillitDialogShell
        if (placing) PlaceStep(send, onEvent) else DetailsStep(send, onEvent)
    }
}

@Composable
private fun DetailsStep(send: SendState, onEvent: (FormSignatureEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth().height(DETAILS_BODY_HEIGHT.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 24.dp),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitTextField(
            value = send.title,
            onValueChange = { onEvent(FormSignatureEvent.EditSend(send.copy(title = it))) },
            label = str(S.name),
            placeholder = str(S.dm_nda_document_name_label),
        )
        AttachDropZone(
            label = str(S.ah_attach_document),
            hint = str(S.desktop_fs_pdf_format_only),
            onClick = { onEvent(FormSignatureEvent.SendPickFile) },
        )
        if (send.fileName.isNotBlank()) FileChip(name = send.fileName, extension = "PDF")

        ZillitDivider()
        ZillitText(str(S.select_users_sign_req_txt), style = ZillitTheme.typography.bodyMedium)
        ZillitText(str(S.desktop_fs_select_zillit_members), style = ZillitTheme.typography.titleSmall)
        if (send.loadingPeople) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZillitSpinner(size = 16.dp)
                ZillitText(
                    str(S.desktop_fs_loading_crew),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        } else {
            ZillitMultiSelect(
                selected = send.chosen,
                options = send.options.map { it.userId },
                label = { id ->
                    send.options.firstOrNull { it.userId == id }?.let { option ->
                        option.designation.takeIf { d -> d.isNotBlank() }
                            ?.let { d -> "${option.label} · $d" } ?: option.label
                    } ?: id
                },
                onChange = { onEvent(FormSignatureEvent.EditSend(send.copy(chosen = it))) },
                placeholder = str(S.desktop_fs_select_zillit_members),
                emptyText = str(S.desktop_fs_nobody_has_tool_access),
            )
        }
        ZillitCheckbox(
            checked = send.hasExternal,
            onCheckedChange = { on ->
                onEvent(
                    FormSignatureEvent.EditSend(
                        send.copy(hasExternal = on, chosenExternal = if (on) send.chosenExternal else emptyList()),
                    ),
                )
            },
            label = str(S.tick_here_external_users_txt),
        )
        if (send.hasExternal) {
            Box(Modifier.padding(start = 24.dp)) {
                ZillitMultiSelect(
                    selected = send.chosenExternal,
                    options = send.externals.map { it.id },
                    label = { id ->
                        send.externals.firstOrNull { it.id == id }?.let { outsider ->
                            if (outsider.fullName.isNotBlank() && outsider.email.isNotBlank()) {
                                "${outsider.fullName} · ${outsider.email}"
                            } else {
                                outsider.label
                            }
                        } ?: id
                    },
                    onChange = { onEvent(FormSignatureEvent.EditSend(send.copy(chosenExternal = it))) },
                    placeholder = str(S.select_external_users_txt),
                    emptyText = str(S.desktop_fs_no_external_users_yet),
                )
            }
        }
        ZillitCheckbox(
            checked = send.senderSigns,
            onCheckedChange = { onEvent(FormSignatureEvent.EditSend(send.copy(senderSigns = it))) },
            label = str(S.tick_here_self_sign_req_txt),
        )

        ZillitDivider()
        ZillitText(str(S.tick_one_of_bel_txt), style = ZillitTheme.typography.titleSmall)
        ChoiceRow(
            label = str(S.doc_required_init_or_sig_txt),
            selected = !send.onlySignature,
            onClick = { onEvent(FormSignatureEvent.EditSend(send.copy(onlySignature = false))) },
        )
        ChoiceRow(
            label = str(S.doc_required_sig_txt),
            selected = send.onlySignature,
            onClick = { onEvent(FormSignatureEvent.EditSend(send.copy(onlySignature = true))) },
        )
    }
}

@Composable
private fun PlaceStep(send: SendState, onEvent: (FormSignatureEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val page = send.current
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        if (send.people.size > 1) {
            ZillitText(
                str(S.desktop_fs_select_user_for_placeholders),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                send.people.forEach { person ->
                    val active = person.id == send.activeSigner
                    Column(
                        modifier = Modifier
                            .clip(ZillitTheme.shapes.medium)
                            .background(if (active) Color(0xFF2563EB) else colors.surface)
                            .border(1.dp, if (active) Color(0xFF2563EB) else colors.border, ZillitTheme.shapes.medium)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { onEvent(FormSignatureEvent.SendSelectSigner(person.id)) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        ZillitText(
                            person.name,
                            style = ZillitTheme.typography.bodySmall,
                            color = if (active) Color.White else colors.textPrimary,
                        )
                        if (person.subtitle.isNotBlank()) {
                            ZillitText(
                                person.subtitle,
                                style = ZillitTheme.typography.labelSmall,
                                color = if (active) Color(0xFFBFDBFE) else colors.textMuted,
                            )
                        }
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = str(S.desktop_fs_add_signature_placeholder),
                onClick = { onEvent(FormSignatureEvent.SendAddPlaceholder(SignSpotKind.Signature)) },
                size = ButtonSize.Small,
                enabled = send.draft == null && send.senderMark == null,
            )
            if (!send.onlySignature) {
                ZillitButton(
                    text = str(S.desktop_fs_add_initials_placeholder),
                    onClick = { onEvent(FormSignatureEvent.SendAddPlaceholder(SignSpotKind.Initials)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = send.draft == null && send.senderMark == null,
                )
            }
        }
        if (send.senderSigns) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    str(S.desktop_fs_your_signature_colon),
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                )
                if (send.senderMark != null) {
                    ZillitButton(
                        text = str(S.desktop_fs_confirm_placement_button),
                        onClick = { onEvent(FormSignatureEvent.SendConfirmOwnMark) },
                        size = ButtonSize.Small,
                        enabled = !send.busy,
                    )
                    ZillitButton(
                        text = str(S.cancel),
                        onClick = { onEvent(FormSignatureEvent.SendCancelOwnMark) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                } else {
                    ZillitButton(
                        text = if (send.senderSignaturePlaced) {
                            str(S.desktop_fs_add_your_signature_again)
                        } else {
                            str(S.desktop_fs_add_your_signature)
                        },
                        onClick = { onEvent(FormSignatureEvent.SendAddOwnMark(SignSpotKind.Signature)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        enabled = send.draft == null,
                    )
                    if (!send.onlySignature) {
                        ZillitButton(
                            text = if (send.senderInitialsPlaced) {
                                str(S.desktop_fs_add_your_initials_again)
                            } else {
                                str(S.desktop_fs_add_your_initials)
                            },
                            onClick = { onEvent(FormSignatureEvent.SendAddOwnMark(SignSpotKind.Initials)) },
                            variant = ButtonVariant.Secondary,
                            size = ButtonSize.Small,
                            enabled = send.draft == null,
                        )
                    }
                }
            }
        }

        // The page, in its own scrolling well with the pager on top.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(PAGE_WELL_HEIGHT.dp)
                .clip(ZillitTheme.shapes.medium)
                .background(colors.surfaceSunken)
                .border(1.dp, colors.border, ZillitTheme.shapes.medium),
        ) {
            val paging = send.draft == null && send.senderMark == null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    str(S.desktop_page_x_of_y, send.page + 1, send.pages.size),
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                )
                Spacer(Modifier.weight(1f))
                ZillitButton(
                    text = str(S.desktop_previous_arrow),
                    onClick = { onEvent(FormSignatureEvent.SendTurnPage(-1)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = paging && send.page > 0,
                )
                ZillitButton(
                    text = str(S.desktop_next_arrow),
                    onClick = { onEvent(FormSignatureEvent.SendTurnPage(1)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = paging && send.page < send.pages.size - 1,
                )
            }
            ZillitScrollColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(ZillitTheme.spacing.lg),
            ) {
                if (page != null) {
                    PageCanvas(page = page) {
                        val signer = send.activeSigner
                        // Placed boxes of the selected signer on this page — draggable, deletable.
                        if (signer != null && send.draft == null) {
                            send.spots[signer].orEmpty().forEachIndexed { index, spot ->
                                if (spot.page != page.page) return@forEachIndexed
                                val rect = page.pixelRect(spot)
                                val edge = spotEdge(spot.kind, placing = true)
                                DraggableBox(
                                    x = rect[0],
                                    y = rect[1],
                                    width = rect[2],
                                    height = rect[3],
                                    edge = edge,
                                    aspect = rect[3] / rect[2],
                                    onMove = { x, y, _, _ ->
                                        onEvent(FormSignatureEvent.SendMoveSpot(signer, index, x, y))
                                    },
                                    onDelete = { onEvent(FormSignatureEvent.SendRemoveSpot(signer, index)) },
                                ) {
                                    PlaceholderLabel(spot.kind, spotFill(spot.kind, placing = true), edge)
                                }
                            }
                        }
                        send.draft?.takeIf { it.page == page.page }?.let { draft ->
                            val edge = spotEdge(draft.kind, placing = true)
                            DraggableBox(
                                x = draft.x,
                                y = draft.y,
                                width = draft.width,
                                height = draft.height,
                                edge = edge,
                                onMove = { x, y, w, h -> onEvent(FormSignatureEvent.SendMoveDraft(x, y, w, h)) },
                                onConfirm = { onEvent(FormSignatureEvent.SendConfirmDraft) },
                                onCancel = { onEvent(FormSignatureEvent.SendCancelDraft) },
                            ) {
                                PlaceholderLabel(draft.kind, spotFill(draft.kind, placing = true), edge)
                            }
                        }
                        send.senderMark?.takeIf { it.page == page.page }?.let { mark ->
                            DraggableBox(
                                x = mark.x,
                                y = mark.y,
                                width = mark.width,
                                height = mark.height,
                                edge = Color(0xFFFC9404),
                                aspect = mark.aspect,
                                onMove = { x, y, w, _ -> onEvent(FormSignatureEvent.SendMoveOwnMark(x, y, w)) },
                                onConfirm = { onEvent(FormSignatureEvent.SendConfirmOwnMark) },
                                onCancel = { onEvent(FormSignatureEvent.SendCancelOwnMark) },
                            ) {
                                MarkImage(mark.png)
                            }
                        }
                    }
                }
            }
        }

        // Per-user status, the web's ✓ / ⚠ lines.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            send.people.forEach { person ->
                val hasSig = send.spotsOf(person.id, SignSpotKind.Signature).isNotEmpty()
                val hasInit = send.spotsOf(person.id, SignSpotKind.Initials).isNotEmpty()
                val ok = send.covered(person.id)
                ZillitText(
                    text = buildString {
                        append(if (ok) "✓ " else "⚠ ")
                        append(person.name).append(": ")
                        append(
                            when {
                                !hasSig -> str(S.desktop_fs_no_signature_placeholder)
                                hasInit && !send.onlySignature -> str(S.desktop_fs_signature_plus_initials)
                                send.onlySignature -> str(S.signature_txt)
                                else -> str(S.desktop_fs_signature_initials_missing)
                            },
                        )
                    },
                    style = ZillitTheme.typography.bodySmall,
                    color = if (ok) colors.textSecondary else Color(0xFFD97706),
                )
            }
        }
    }
}

private const val DETAILS_WIDTH = 520
private const val DETAILS_HEIGHT = 740
private const val DETAILS_BODY_HEIGHT = 540
private const val PLACE_WIDTH = 1100
private const val PLACE_HEIGHT = 800
private const val PAGE_WELL_HEIGHT = 430
