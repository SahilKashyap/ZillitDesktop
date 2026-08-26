@file:Suppress("LongMethod") // Dialogs are linear field lists; splitting hides the form.

package com.zillit.desktop.feature.boxschedule.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.locationpicker.oneLine
import com.zillit.desktop.core.locationpicker.ZillitLocationField
import com.zillit.desktop.feature.boxschedule.domain.DiaryClock
import com.zillit.desktop.feature.boxschedule.domain.DiaryDraft
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.ui.BlockEditor
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.DiaryEditor

/** Create or edit a block: type, date range, optional detail line. */
@Composable
internal fun BlockEditorDialog(
    state: BoxScheduleUiState,
    editor: BlockEditor,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    ZillitDialogShell(
        title = if (editor.blockId == null) "Add schedule" else "Edit schedule",
        onDismiss = { onEvent(BoxScheduleEvent.CloseBlock) },
        visible = true,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(BoxScheduleEvent.CloseBlock) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Save",
                onClick = { onEvent(BoxScheduleEvent.SaveBlock) },
                loading = editor.saving,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(
                text = "Type",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            ZillitSelect(
                value = state.types.firstOrNull { it.id == editor.typeId },
                options = state.types,
                onSelect = { it?.let { type -> onEvent(BoxScheduleEvent.BlockChanged(typeId = type.id)) } },
                label = { it?.title ?: "Pick a type" },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(
                    value = editor.startText,
                    onValueChange = { onEvent(BoxScheduleEvent.BlockChanged(startText = it)) },
                    label = "From",
                    placeholder = "YYYY-MM-DD",
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = editor.endText,
                    onValueChange = { onEvent(BoxScheduleEvent.BlockChanged(endText = it)) },
                    label = "To",
                    placeholder = "same day if blank",
                    modifier = Modifier.weight(1f),
                )
            }
            ZillitTextField(
                value = editor.title,
                onValueChange = { onEvent(BoxScheduleEvent.BlockChanged(title = it)) },
                label = "Detail (optional)",
            )
            if (editor.conflicts.isNotEmpty()) {
                ZillitNotice(
                    text = "These dates already carry a schedule: " +
                        editor.conflicts.joinToString { "${DiaryClock.dayLabel(it.date)} (${it.existingType})" } +
                        ". Replace it, extend the existing block, or overlap?",
                    tone = StatusTone.Pending,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    CONFLICT_CHOICES.forEach { (wire, label) ->
                        ZillitButton(
                            text = label,
                            onClick = { onEvent(BoxScheduleEvent.ResolveConflict(wire)) },
                            variant = ButtonVariant.Secondary,
                            size = ButtonSize.Small,
                            loading = editor.saving,
                        )
                    }
                }
            }
        }
    }
}

/** Create or edit an event or note. */
@Composable
internal fun DiaryEditorDialog(
    state: BoxScheduleUiState,
    editor: DiaryEditor,
    onEvent: (BoxScheduleEvent) -> Unit,
) {
    val isNote = editor.kind == DiaryKind.Note
    ZillitDialogShell(
        title = when {
            editor.eventId == null && isNote -> "Add note"
            editor.eventId == null -> "Add event"
            isNote -> "Edit note"
            else -> "Edit event"
        },
        onDismiss = { onEvent(BoxScheduleEvent.CloseDiary) },
        visible = true,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(BoxScheduleEvent.CloseDiary) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Save",
                onClick = { onEvent(BoxScheduleEvent.SaveDiary) },
                loading = editor.saving,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            if (editor.isRecurring) {
                ZillitNotice(
                    text = "This event repeats — changes apply to the whole series.",
                    tone = StatusTone.Pending,
                )
            }
            ZillitTextField(
                value = editor.title,
                onValueChange = { onEvent(BoxScheduleEvent.DiaryChanged(title = it)) },
                label = "Title",
            )
            ZillitTextField(
                value = editor.dateText,
                onValueChange = { onEvent(BoxScheduleEvent.DiaryChanged(dateText = it)) },
                label = "Date",
                placeholder = "YYYY-MM-DD",
            )
            if (!isNote) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    ZillitTextField(
                        value = editor.startText,
                        onValueChange = { onEvent(BoxScheduleEvent.DiaryChanged(startText = it)) },
                        label = "Start",
                        placeholder = "HH:mm (blank = all day)",
                        modifier = Modifier.weight(1f),
                    )
                    ZillitTextField(
                        value = editor.endText,
                        onValueChange = { onEvent(BoxScheduleEvent.DiaryChanged(endText = it)) },
                        label = "End",
                        placeholder = "HH:mm",
                        modifier = Modifier.weight(1f),
                    )
                }
                // A map pick fills the line; only the line travels. The diary
                // wire carries `location` as a string and nothing else —
                // `eventWire` in BoxScheduleRepositoryImpl, pinned field for
                // field by BoxScheduleSyncTest — so the coordinates behind the
                // pick are deliberately dropped rather than invented into keys
                // the server has never been asked for.
                ZillitLocationField(
                    text = editor.location,
                    onTextChange = { onEvent(BoxScheduleEvent.DiaryChanged(location = it)) },
                    onPicked = {
                        onEvent(
                            BoxScheduleEvent.DiaryChanged(
                                location = it.oneLine(),
                                locationLat = it.lat,
                                locationLng = it.lng,
                            ),
                        )
                    },
                    label = "Location",
                )
                ZillitText(
                    text = "Repeats",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
                ZillitSelect(
                    value = editor.repeatStatus,
                    options = DiaryDraft.REPEAT_OPTIONS,
                    onSelect = { onEvent(BoxScheduleEvent.DiaryChanged(repeatStatus = it)) },
                    label = { it.replaceFirstChar(Char::uppercase) },
                    modifier = Modifier.width(SELECT_WIDTH),
                )
            } else if (state.noteTypes.isNotEmpty()) {
                ZillitText(
                    text = "Note type",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
                ZillitSelect(
                    value = state.noteTypes.firstOrNull { it.value == editor.noteType },
                    options = state.noteTypes,
                    onSelect = { it?.let { type -> onEvent(BoxScheduleEvent.DiaryChanged(noteType = type.value)) } },
                    label = { it?.label ?: "Pick a type" },
                    modifier = Modifier.width(SELECT_WIDTH),
                )
            }
            ZillitTextField(
                value = editor.body,
                onValueChange = { onEvent(BoxScheduleEvent.DiaryChanged(body = it)) },
                label = if (isNote) "Note" else "Description",
                singleLine = false,
            )
            ColorRow(
                current = editor.color,
                options = DiaryDraft.EVENT_COLORS,
                onPick = { onEvent(BoxScheduleEvent.DiaryChanged(color = it)) },
            )
        }
    }
}

/** Manage the schedule types: list, add, recolour, rename, delete. */
@Composable
internal fun TypesDialog(state: BoxScheduleUiState, onEvent: (BoxScheduleEvent) -> Unit) {
    val editor = state.typeEditor
    ZillitDialogShell(
        title = "Schedule types",
        onDismiss = { onEvent(BoxScheduleEvent.CloseTypes) },
        visible = true,
        actions = {
            ZillitButton(
                text = "Close",
                onClick = { onEvent(BoxScheduleEvent.CloseTypes) },
                variant = ButtonVariant.Tertiary,
            )
            if (editor == null) {
                ZillitButton(text = "Add type", onClick = { onEvent(BoxScheduleEvent.NewType) })
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            state.types.forEach { type ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    Box(
                        Modifier
                            .size(SWATCH)
                            .clip(RoundedCornerShape(3.dp))
                            .background(hexColor(type.color) ?: ZillitTheme.colors.accent),
                    )
                    ZillitText(
                        text = type.title + if (type.systemDefined) "  (system)" else "",
                        style = ZillitTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitButton(
                        text = "Edit",
                        onClick = { onEvent(BoxScheduleEvent.EditType(type.id)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                    if (!type.systemDefined) {
                        ZillitButton(
                            text = "Delete",
                            onClick = { onEvent(BoxScheduleEvent.DeleteType(type.id)) },
                            variant = ButtonVariant.Danger,
                            size = ButtonSize.Small,
                        )
                    }
                }
            }
            if (editor != null) {
                val system = state.types.firstOrNull { it.id == editor.typeId }?.systemDefined == true
                ZillitTextField(
                    value = editor.title,
                    onValueChange = { onEvent(BoxScheduleEvent.TypeChanged(title = it)) },
                    label = if (editor.typeId == null) "New type" else "Name",
                    enabled = !system,
                    helperText = if (system) "System types keep their name" else null,
                )
                ColorRow(
                    current = editor.color,
                    options = TYPE_COLORS,
                    onPick = { onEvent(BoxScheduleEvent.TypeChanged(color = it)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                    ZillitButton(
                        text = "Cancel",
                        onClick = { onEvent(BoxScheduleEvent.CloseTypeEditor) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                    )
                    ZillitButton(
                        text = "Save",
                        onClick = { onEvent(BoxScheduleEvent.SaveType) },
                        size = ButtonSize.Small,
                        loading = editor.saving,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorRow(current: String, options: List<String>, onPick: (String) -> Unit) {
    val colors = ZillitTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        options.forEach { hex ->
            Box(
                Modifier
                    .size(SWATCH_PICK)
                    .clip(RoundedCornerShape(4.dp))
                    .background(hexColor(hex) ?: colors.textMuted)
                    .border(
                        if (hex.equals(current, ignoreCase = true)) 2.dp else 1.dp,
                        if (hex.equals(current, ignoreCase = true)) colors.accent else colors.border,
                        RoundedCornerShape(4.dp),
                    )
                    .clickable { onPick(hex) },
            )
        }
    }
}

/** The one line a picked place reads as: its name, then its address. */

/** `#rrggbb` to a Compose colour; null for anything else. */
internal fun hexColor(hex: String): Color? {
    val digits = hex.removePrefix("#")
    if (digits.length != HEX_DIGITS) return null
    val value = digits.toLongOrNull(HEX_RADIX) ?: return null
    return Color(OPAQUE or value)
}

private val CONFLICT_CHOICES = listOf("replace" to "Replace", "extend" to "Extend", "overlap" to "Overlap")
private val TYPE_COLORS = listOf(
    "#3498DB", "#E74C3C", "#27AE60", "#F39C12", "#8E44AD", "#95A5A6", "#1ABC9C", "#E67E22",
)
private val SELECT_WIDTH = 200.dp
private val SWATCH = 12.dp
private val SWATCH_PICK = 22.dp
private const val HEX_DIGITS = 6
private const val HEX_RADIX = 16
private const val OPAQUE = 0xFF000000L
