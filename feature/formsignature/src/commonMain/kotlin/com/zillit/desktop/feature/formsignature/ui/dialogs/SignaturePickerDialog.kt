@file:Suppress("MagicNumber") // Modal geometry, the web's 600px.

package com.zillit.desktop.feature.formsignature.ui.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.formsignature.domain.SignSpotKind
import com.zillit.desktop.feature.formsignature.domain.SignatureBlock
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.FormSignatureUiState
import com.zillit.desktop.feature.formsignature.ui.SignaturesState
import com.zillit.desktop.feature.formsignature.ui.decodeImageBitmap
import com.zillit.desktop.feature.formsignature.ui.pages.DrawingPad

/**
 * Select Signature — the web's `SignaturesModal`: the saved marks of the
 * asked-for kind (both, for free placement), a click picks one, and the
 * Add buttons appear only for a kind that does not exist yet.
 */
@Composable
internal fun SignaturePickerDialog(state: FormSignatureUiState, onEvent: (FormSignatureEvent) -> Unit) {
    val picker = state.picker
    val signatures = state.signatures
    val shown = signatures.blocks.filter { picker?.kind == null || it.kind == picker.kind }
    val showAddSignature = (picker?.kind == null || picker.kind == SignSpotKind.Signature) &&
        signatures.signature == null
    val showAddInitials = (picker?.kind == null || picker.kind == SignSpotKind.Initials) &&
        signatures.initials == null

    ZillitDialogShell(
        title = str(S.txt_select_sign),
        visible = picker != null && state.draw?.asPage != false,
        onDismiss = { onEvent(FormSignatureEvent.ClosePicker) },
        icon = ZillitIcons.Signature,
        width = PICKER_WIDTH.dp,
        scrollable = false,
        actions = {
            if (showAddSignature) {
                ZillitButton(
                    text = "+ Add Signature",
                    onClick = { onEvent(FormSignatureEvent.StartDraw(isSignature = true, asPage = false)) },
                    size = ButtonSize.Small,
                )
            }
            if (showAddInitials) {
                ZillitButton(
                    text = "+ Add Initials",
                    onClick = { onEvent(FormSignatureEvent.StartDraw(isSignature = false, asPage = false)) },
                    size = ButtonSize.Small,
                )
            }
        },
    ) {
        if (picker != null) PickerBody(signatures, shown, onEvent)
    }

    DrawSignatureDialog(state, onEvent)
}

@Composable
private fun PickerBody(
    signatures: SignaturesState,
    shown: List<SignatureBlock>,
    onEvent: (FormSignatureEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        run {
            if (signatures.blocks.isNotEmpty()) {
                ZillitText(
                    str(S.desktop_fs_signature_info),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            when {
                signatures.loading && signatures.blocks.isEmpty() ->
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { ZillitSpinner() }
                shown.isEmpty() -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitEmptyState(title = str(S.desktop_fs_no_signatures), icon = ZillitIcons.Signature)
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                    shown.forEach { block ->
                        MarkTile(
                            block = block,
                            image = signatures.images[block.id],
                            modifier = Modifier.weight(1f),
                            onClick = { onEvent(FormSignatureEvent.PickSignature(block)) },
                        )
                    }
                    if (shown.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MarkTile(block: SignatureBlock, image: ByteArray?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bitmap = remember(block.id, image?.size) { image?.let(::decodeImageBitmap) }
    Box(
        modifier = modifier
            .height(TILE_HEIGHT.dp)
            .clip(ZillitTheme.shapes.medium)
            .background(Color.White)
            .border(2.dp, if (hovered) colors.accent else colors.border, ZillitTheme.shapes.medium)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(ZillitTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        when {
            image == null -> ZillitSpinner()
            bitmap == null -> ZillitText(str(S.desktop_fs_could_not_show_mark), color = colors.textSecondary)
            else -> Image(
                bitmap = bitmap,
                contentDescription = block.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            ZillitTag(
                label = if (block.isSignature) str(S.signature_txt) else str(S.docusign_saved_sig_initials),
                tone = if (block.isSignature) TagTone.Info else TagTone.Success,
            )
        }
    }
}

/** The web's `AddSignaturesModal`: the pad over the picker, name + canvas + Clear/Save. */
@Composable
internal fun DrawSignatureDialog(state: FormSignatureUiState, onEvent: (FormSignatureEvent) -> Unit) {
    val draw = state.draw?.takeIf { !it.asPage }
    ZillitDialogShell(
        title = if (draw?.isSignature != false) str(S.add_signature) else str(S.txt_add_initials),
        visible = draw != null,
        onDismiss = { onEvent(FormSignatureEvent.CancelDraw) },
        icon = ZillitIcons.Edit,
        width = DRAW_WIDTH.dp,
        scrollable = false,
        actions = {
            ZillitButton(
                text = str(S.clear_label),
                onClick = { onEvent(FormSignatureEvent.ClearDraw) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = draw?.saving != true,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(FormSignatureEvent.SubmitDraw) },
                size = ButtonSize.Small,
                loading = draw?.saving == true,
            )
        },
    ) {
        if (draw == null) return@ZillitDialogShell
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitTextField(
                value = draw.name,
                onValueChange = { name ->
                    if (name.length <= NAME_MAX) onEvent(FormSignatureEvent.EditDraw(draw.copy(name = name)))
                },
                label = if (draw.isSignature) str(S.signature_name) else str(S.desktop_fs_initials_name),
                placeholder = if (draw.isSignature) str(S.signature_name) else str(S.desktop_fs_initials_name),
            )
            ZillitText(
                if (draw.isSignature) str(S.draw_signature_here) else str(S.desktop_fs_draw_initials_here),
                style = ZillitTheme.typography.titleSmall,
            )
            DrawingPad(draw, onEvent)
        }
    }
}

private const val PICKER_WIDTH = 620
private const val TILE_HEIGHT = 150
private const val DRAW_WIDTH = 640
private const val NAME_MAX = 50
