@file:Suppress("MagicNumber") // Drawer geometry, the web's 450px.

package com.zillit.desktop.feature.formsignature.ui.dialogs

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.formsignature.domain.StandardFormType
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.ui.FormSignatureUiState
import com.zillit.desktop.feature.formsignature.ui.components.FileChip

/** Upload Document — the web's `UploadFormModal` drawer for the standard library. */
@Composable
internal fun UploadFormDialog(state: FormSignatureUiState, onEvent: (FormSignatureEvent) -> Unit) {
    val upload = state.uploadForm
    ZillitDialogShell(
        title = str(S.txt_document_add),
        visible = upload != null,
        onDismiss = { onEvent(FormSignatureEvent.CancelUploadForm) },
        icon = ZillitIcons.Upload,
        width = DRAWER_WIDTH.dp,
        scrollable = false,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(FormSignatureEvent.CancelUploadForm) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                enabled = upload?.uploading != true,
            )
            ZillitButton(
                text = str(S.upload),
                onClick = { onEvent(FormSignatureEvent.SubmitUploadForm) },
                size = ButtonSize.Small,
                loading = upload?.uploading == true,
            )
        },
    ) {
        if (upload == null) return@ZillitDialogShell
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitTextField(
                value = upload.name,
                onValueChange = { onEvent(FormSignatureEvent.EditUploadForm(upload.copy(name = it))) },
                placeholder = str(S.dm_nda_document_name_label),
                label = str(S.dm_nda_document_name_label),
            )
            AttachDropZone(
                label = str(S.ah_attach_document),
                hint = str(S.desktop_fs_pdf_doc_format_only),
                onClick = { onEvent(FormSignatureEvent.PickUploadFile) },
            )
            if (upload.fileName.isNotBlank()) FileChip(name = upload.fileName, extension = upload.extension)

            ZillitText(str(S.select_type_of_document), style = ZillitTheme.typography.titleSmall)
            StandardFormType.entries.forEach { type ->
                ChoiceRow(
                    label = type.label,
                    selected = upload.type == type,
                    onClick = { onEvent(FormSignatureEvent.EditUploadForm(upload.copy(type = type))) },
                )
            }
        }
    }
}

/** The web's dotted "Attach Document" button. */
@Composable
internal fun AttachDropZone(label: String, hint: String, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .background(colors.surfaceSunken)
                .dashedBorder(colors.borderStrong)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onClick)
                .padding(vertical = ZillitTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(ZillitIcons.Upload, tint = colors.accent, size = 28.dp)
            ZillitText(label, style = ZillitTheme.typography.bodyMedium)
        }
        ZillitText(
            hint,
            style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = colors.textMuted,
        )
    }
}

private fun Modifier.dashedBorder(color: Color): Modifier = drawBehind {
    val stroke = Stroke(
        width = 1.5f * density,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f * density, 6f * density)),
    )
    drawRoundRect(color = color, style = stroke, cornerRadius = CornerRadius(6f * density))
}

/** A bordered radio row — the web's `<Radio className="border …">`. */
@Composable
internal fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val border by animateColorAsState(if (selected) colors.accent else colors.border, label = "choiceBorder")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(if (selected) colors.accentSoft else colors.surface)
            .border(1.dp, border, ZillitTheme.shapes.medium)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) colors.accent else colors.borderStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
        }
        ZillitText(label, style = ZillitTheme.typography.bodyMedium)
    }
}

private const val DRAWER_WIDTH = 460
