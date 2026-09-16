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
        title = "Upload Document",
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
                    text = "Back",
                    onClick = { onEvent(FormSignatureEvent.SendBack) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !send.sending,
                )
                ZillitButton(
                    text = "Send Document",
                    onClick = { onEvent(FormSignatureEvent.SubmitSend) },
                    size = ButtonSize.Small,
                    loading = send.sending,
                    enabled = !send.sending && !send.busy,
                )
            } else {
                ZillitButton(
                    text = "Next",
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
            label = "Name",
            placeholder = "Document Name",
        )
        AttachDropZone(
            label = "Attach Document",
            hint = "PDF format only",
            onClick = { onEvent(FormSignatureEvent.SendPickFile) },
        )
        if (send.fileName.isNotBlank()) FileChip(name = send.fileName, extension = "PDF")

        ZillitDivider()
        ZillitText("Select the users whose signatures are required.", style = ZillitTheme.typography.bodyMedium)
        ZillitText("Select Zillit Members", style = ZillitTheme.typography.titleSmall)
        if (send.loadingPeople) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZillitSpinner(size = 16.dp)
                ZillitText("Loading the crew…", style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
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
                placeholder = "Select Zillit Members",
                emptyText = "Nobody on this production has access to the tool",
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
            label = "Tick here if there are External Users (either on Zillit or outside)",
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
                    placeholder = "Select External Users",
                    emptyText = "No external users on this production yet",
                )
            }
        }
        ZillitCheckbox(
            checked = send.senderSigns,
            onCheckedChange = { onEvent(FormSignatureEvent.EditSend(send.copy(senderSigns = it))) },
            label = "Tick here if your signature is required as well.",
        )

        ZillitDivider()
        ZillitText("Tick one of the below", style = ZillitTheme.typography.titleSmall)
        ChoiceRow(
            label = "Does the document require initials and signature",
            selected = !send.onlySignature,
            onClick = { onEvent(FormSignatureEvent.EditSend(send.copy(onlySignature = false))) },
        )
        ChoiceRow(
            label = "Does the document require signature only",
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
                "Select user to place their placeholders:",
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
                text = "Add Signature Placeholder",
                onClick = { onEvent(FormSignatureEvent.SendAddPlaceholder(SignSpotKind.Signature)) },
                size = ButtonSize.Small,
                enabled = send.draft == null && send.senderMark == null,
            )
            if (!send.onlySignature) {
                ZillitButton(
                    text = "Add Initials Placeholder",
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
                    "Your signature:",
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                )
                if (send.senderMark != null) {
                    ZillitButton(
                        text = "Confirm Placement",
                        onClick = { onEvent(FormSignatureEvent.SendConfirmOwnMark) },
                        size = ButtonSize.Small,
                        enabled = !send.busy,
                    )
                    ZillitButton(
                        text = "Cancel",
                        onClick = { onEvent(FormSignatureEvent.SendCancelOwnMark) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                } else {
                    ZillitButton(
                        text = if (send.senderSignaturePlaced) "Add your signature again" else "Add your signature",
                        onClick = { onEvent(FormSignatureEvent.SendAddOwnMark(SignSpotKind.Signature)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        enabled = send.draft == null,
                    )
                    if (!send.onlySignature) {
                        ZillitButton(
                            text = if (send.senderInitialsPlaced) "Add your initials again" else "Add your initials",
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
                    "Page ${send.page + 1} / ${send.pages.size}",
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                )
                Spacer(Modifier.weight(1f))
                ZillitButton(
                    text = "← Previous",
                    onClick = { onEvent(FormSignatureEvent.SendTurnPage(-1)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    enabled = paging && send.page > 0,
                )
                ZillitButton(
                    text = "Next →",
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
                                !hasSig -> "No signature placeholder"
                                hasInit && !send.onlySignature -> "Signature + Initials"
                                send.onlySignature -> "Signature"
                                else -> "Signature (initials missing)"
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
