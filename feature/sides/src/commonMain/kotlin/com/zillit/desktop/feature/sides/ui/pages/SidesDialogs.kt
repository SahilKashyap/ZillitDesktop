@file:Suppress("LongMethod") // A form reads best as its fields in order.

package com.zillit.desktop.feature.sides.ui.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.sides.domain.SidesRules
import com.zillit.desktop.feature.sides.ui.PickedDoc
import com.zillit.desktop.feature.sides.ui.SidesDialog
import com.zillit.desktop.feature.sides.ui.SidesEvent
import com.zillit.desktop.feature.sides.ui.components.FieldLabel
import com.zillit.desktop.feature.sides.ui.components.SidesConfirmDialog
import com.zillit.desktop.feature.sides.ui.components.hand
import com.zillit.desktop.feature.sides.ui.components.hexColor
import com.zillit.desktop.feature.sides.ui.components.sidesFileDrop

/** The module's modal forms and confirms, one composable per dialog kind. */
@Composable
internal fun SidesDialogs(dialog: SidesDialog?, onEvent: (SidesEvent) -> Unit) {
    when (dialog) {
        null -> Unit
        is SidesDialog.AddScript -> AddScriptDialog(dialog, onEvent)
        is SidesDialog.PageEditor -> PageEditorDialog(dialog, onEvent)
        is SidesDialog.UploadDoc -> UploadDocDialog(dialog, onEvent)
        is SidesDialog.Confirm -> SidesConfirmDialog(
            title = dialog.title,
            message = dialog.message,
            busy = dialog.busy,
            onConfirm = { onEvent(SidesEvent.DialogSubmit) },
            onDismiss = { onEvent(SidesEvent.DialogDismiss) },
        )
    }
}

/** Add a new script: a name, and an optional PDF/.fdx — the web's `AddScriptModal`. */
@Composable
private fun AddScriptDialog(dialog: SidesDialog.AddScript, onEvent: (SidesEvent) -> Unit) {
    FormDialog(
        title = str(S.sides_add_script),
        busy = dialog.busy,
        submitLabel = str(S.sides_add_script),
        submitEnabled = dialog.title.isNotBlank(),
        onEvent = onEvent,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel(str(S.sides_script_name_hint))
            ZillitTextField(
                value = dialog.title,
                onValueChange = { onEvent(SidesEvent.DialogTitle(it)) },
                placeholder = str(S.desktop_sides_script_name_example),
                enabled = !dialog.busy,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel(str(S.desktop_sides_script_file_label), optional = true)
            DropZone(
                file = dialog.file,
                hint = str(S.desktop_sides_script_file_hint),
                enabled = !dialog.busy,
                onEvent = onEvent,
            )
        }
    }
}

/** Add or edit a page folder — the web's `PageEditorModal`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageEditorDialog(dialog: SidesDialog.PageEditor, onEvent: (SidesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    FormDialog(
        title = if (dialog.isEdit) str(S.sides_edit_page) else str(S.desktop_sides_add_page),
        busy = dialog.busy,
        submitLabel = if (dialog.isEdit) str(S.save) else str(S.desktop_sides_add_page),
        submitEnabled = true,
        onEvent = onEvent,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel(str(S.desktop_sides_scene_no_title))
            ZillitTextField(
                value = dialog.sceneNumber,
                onValueChange = { onEvent(SidesEvent.DialogSceneNumber(it)) },
                placeholder = str(S.desktop_sides_scene_no_example),
                enabled = !dialog.busy,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel(str(S.color))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SidesRules.PAGE_COLORS.forEach { hex ->
                    val on = hex == dialog.color
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(hexColor(hex) ?: colors.textMuted)
                            .border(
                                width = if (on) 3.dp else 2.dp,
                                color = if (on) colors.textPrimary else colors.border,
                                shape = RoundedCornerShape(6.dp),
                            )
                            .clickable { onEvent(SidesEvent.DialogColor(hex)) }
                            .hand(),
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel(str(S.description), optional = true)
            ZillitTextField(
                value = dialog.description,
                onValueChange = { onEvent(SidesEvent.DialogDescription(it)) },
                placeholder = str(S.desktop_short_note),
                enabled = !dialog.busy,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel(
                if (dialog.isEdit) {
                    str(S.desktop_sides_pick_file_keep_current)
                } else {
                    str(S.sides_pick_file)
                },
            )
            DropZone(file = dialog.file, hint = str(S.sides_pick_file), enabled = !dialog.busy, onEvent = onEvent)
            if (dialog.isEdit && dialog.file == null && dialog.currentFileName.isNotBlank()) {
                ZillitText(
                    text = str(S.desktop_current_file, dialog.currentFileName),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}

/** A call sheet or schedule PDF with a title — the web's two upload modals. */
@Composable
private fun UploadDocDialog(dialog: SidesDialog.UploadDoc, onEvent: (SidesEvent) -> Unit) {
    FormDialog(
        title = dialog.kind.title,
        busy = dialog.busy,
        submitLabel = dialog.kind.title,
        submitEnabled = dialog.file != null,
        onEvent = onEvent,
    ) {
        DropZone(
            file = dialog.file,
            hint = dialog.kind.dropHint,
            enabled = !dialog.busy,
            onEvent = onEvent,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel(str(S.title))
            ZillitTextField(
                value = dialog.title,
                onValueChange = { onEvent(SidesEvent.DialogTitle(it)) },
                placeholder = dialog.kind.titlePlaceholder,
                enabled = !dialog.busy,
            )
        }
    }
}

/** The shared frame: title, Cancel / submit, closable only while idle. */
@Composable
private fun FormDialog(
    title: String,
    busy: Boolean,
    submitLabel: String,
    submitEnabled: Boolean,
    onEvent: (SidesEvent) -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    ZillitDialogShell(
        title = title,
        visible = true,
        onDismiss = { if (!busy) onEvent(SidesEvent.DialogDismiss) },
        width = 500.dp,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(SidesEvent.DialogDismiss) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !busy,
            )
            ZillitButton(
                text = submitLabel,
                onClick = { onEvent(SidesEvent.DialogSubmit) },
                size = ButtonSize.Small,
                enabled = submitEnabled && !busy,
                loading = busy,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

/**
 * The dashed drop target with the chosen file beneath it — the web's
 * `Upload.Dragger` plus its selected-file row. Click to browse, or drop.
 */
@Composable
internal fun DropZone(file: PickedDoc?, hint: String, enabled: Boolean, onEvent: (SidesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    var hover by remember { mutableStateOf(false) }
    val border by animateColorAsState(if (hover) colors.accent else colors.borderStrong, label = "drop")
    val fill by animateColorAsState(if (hover) colors.accentSoft else colors.surfaceSunken, label = "dropFill")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .background(fill)
                .border(1.dp, border, ZillitTheme.shapes.medium)
                .sidesFileDrop(enabled = enabled, onHover = { hover = it }) {
                    onEvent(SidesEvent.DialogFileDropped(it))
                }
                .then(if (enabled) Modifier.clickable { onEvent(SidesEvent.DialogPickFile) }.hand() else Modifier)
                .padding(vertical = 22.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZillitIcon(icon = ZillitIcons.Inbox, tint = colors.accent, size = 28.dp)
            ZillitText(str(S.desktop_drop_or_browse), style = ZillitTheme.typography.bodyMedium)
            ZillitText(hint, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        }
        if (file != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.small)
                    .background(colors.surfaceSunken)
                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZillitIcon(icon = ZillitIcons.Paperclip, tint = colors.accentText, size = 14.dp)
                ZillitText(
                    text = file.name,
                    style = ZillitTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ZillitText(
                    text = SidesRules.formatBytes(file.bytes.size.toLong()).orEmpty(),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = str(S.desktop_remove_selected_file),
                    onClick = { onEvent(SidesEvent.DialogClearFile) },
                    enabled = enabled,
                    tint = colors.textMuted,
                )
            }
        }
    }
}
