package com.zillit.desktop.feature.boxschedule.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.boxschedule.domain.ContentFilter
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfLayout
import com.zillit.desktop.feature.boxschedule.domain.ScheduleType
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.DiaryCommand
import com.zillit.desktop.feature.boxschedule.ui.FilterDraft
import com.zillit.desktop.feature.boxschedule.ui.PageEvent
import com.zillit.desktop.feature.boxschedule.ui.PalettePanel
import com.zillit.desktop.feature.boxschedule.ui.PanelEvent
import com.zillit.desktop.feature.boxschedule.ui.PdfDestination
import com.zillit.desktop.feature.boxschedule.ui.PdfSheet
import com.zillit.desktop.feature.boxschedule.ui.PrintPrompt
import com.zillit.desktop.feature.boxschedule.ui.SharePanel
import com.zillit.desktop.feature.boxschedule.ui.TypesManager

/** "Filters" — Show, and a schedule type when schedules are shown; applied only on Apply. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterDialog(state: BoxScheduleUiState, draft: FilterDraft, onEvent: (BoxScheduleEvent) -> Unit) {
    val active = draft.typeName.isNotBlank() || draft.content != ContentFilter.All
    ZillitDialogShell(
        title = "Filters",
        icon = ZillitIcons.Filter,
        onDismiss = { onEvent(PageEvent.CloseFilters) },
        visible = true,
        width = 480.dp,
        actions = {
            if (active) {
                ZillitButton(
                    "Clear all filters",
                    onClick = { onEvent(PageEvent.ClearFilters) },
                    variant = ButtonVariant.Tertiary,
                )
            }
            Box(Modifier.weight(1f))
            ZillitButton("Apply Filters", onClick = { onEvent(PageEvent.ApplyFilters) })
        },
    ) {
        FieldLabel("Show")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ContentFilter.entries.forEach { content ->
                PillChoice(
                    content.label,
                    selected = draft.content == content,
                    onClick = { onEvent(PageEvent.DraftContent(content)) },
                )
            }
        }
        if (draft.content.showsSchedules) {
            FieldLabel("Schedule Type")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillChoice(
                    "All Types",
                    selected = draft.typeName.isBlank(),
                    onClick = { onEvent(PageEvent.DraftType("")) },
                )
                state.types.forEach { type ->
                    PillChoice(
                        label = type.title,
                        selected = draft.typeName == type.title,
                        dot = swatchColor(type.color),
                        onClick = { onEvent(PageEvent.DraftType(type.title)) },
                    )
                }
            }
        }
    }
}

/** "PDF options" — the layout, and whether the viewer's own Personal Notes go in. */
@Composable
internal fun PdfOptionsDialog(sheet: PdfSheet, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = "PDF options",
        subtitle = if (sheet.destination == PdfDestination.Publish) "Publish to Document Distribution" else "Print",
        icon = if (sheet.destination == PdfDestination.Publish) ZillitIcons.Upload else ZillitIcons.Download,
        onDismiss = { onEvent(PanelEvent.ClosePdf) },
        visible = true,
        width = 440.dp,
        actions = {
            ZillitButton(
                "Cancel",
                onClick = { onEvent(PanelEvent.ClosePdf) },
                variant = ButtonVariant.Secondary,
                enabled = !sheet.busy,
            )
            ZillitButton("Submit", onClick = { onEvent(PanelEvent.SubmitPdf) }, loading = sheet.busy)
        },
    ) {
        ZillitText(
            "Which layout do you want for the PDF?",
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DiaryPdfLayout.entries.forEach { layout ->
                ZillitButton(
                    text = layout.label,
                    onClick = { onEvent(PanelEvent.SetPdfLayout(layout)) },
                    variant = if (sheet.options.layout == layout) ButtonVariant.Primary else ButtonVariant.Secondary,
                    leadingIcon = if (layout == DiaryPdfLayout.Calendar) ZillitIcons.Calendar else ZillitIcons.Grid,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        PersonalNotesChoice(
            include = sheet.options.includePersonalNotes,
            hint = "Personal Notes are visible only to you. Uncheck to keep them out of the PDF.",
            onChange = { onEvent(PanelEvent.SetPdfPersonalNotes(it)) },
        )
    }
}

@Composable
private fun PersonalNotesChoice(include: Boolean, hint: String, onChange: (Boolean) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZillitCheckbox(checked = include, onCheckedChange = onChange, label = "Include my Personal Notes")
        ZillitText(
            hint,
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
            modifier = Modifier.padding(start = 28.dp),
        )
    }
}

/** "Print selected days" — the in-app printout's Personal Notes choice. */
@Composable
internal fun PrintSelectedDialog(prompt: PrintPrompt, count: Int, onEvent: (BoxScheduleEvent) -> Unit) {
    ZillitDialogShell(
        title = "Print selected days",
        subtitle = "$count day(s)",
        icon = ZillitIcons.Download,
        onDismiss = { onEvent(PanelEvent.ClosePrintSelected) },
        visible = true,
        width = 440.dp,
        actions = {
            ZillitButton(
                "Cancel",
                onClick = { onEvent(PanelEvent.ClosePrintSelected) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton("Print", onClick = { onEvent(PanelEvent.ConfirmPrintSelected) }, loading = prompt.printing)
        },
    ) {
        PersonalNotesChoice(
            include = prompt.includePersonalNotes,
            hint = "Personal Notes are visible only to you. Uncheck to keep them out of the printout.",
            onChange = { onEvent(PanelEvent.SetPrintPersonalNotes(it)) },
        )
    }
}

/** "Share Schedule" — a read-only link, or the schedule as text for an email. */
@Composable
internal fun ShareDialog(panel: SharePanel, onEvent: (BoxScheduleEvent) -> Unit) {
    ZillitDialogShell(
        title = "Share Schedule",
        icon = ZillitIcons.Link,
        onDismiss = { onEvent(PanelEvent.CloseShare) },
        visible = true,
        width = 480.dp,
        actions = {
            ZillitButton("Close", onClick = { onEvent(PanelEvent.CloseShare) }, variant = ButtonVariant.Secondary)
        },
    ) {
        ShareCard(
            ZillitIcons.Link,
            "Share via Link",
            "Generate a read-only link that anyone can use to view the schedule.",
        ) {
            val link = panel.link
            if (link == null) {
                ZillitButton(
                    "Generate Link",
                    onClick = { onEvent(PanelEvent.GenerateShareLink) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Link,
                    loading = panel.generating,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitTextField(value = link, onValueChange = {}, readOnly = true, modifier = Modifier.weight(1f))
                    ZillitButton(
                        text = if (panel.linkCopied) "Copied" else "Copy",
                        onClick = { onEvent(PanelEvent.CopyShareLink) },
                        variant = ButtonVariant.Secondary,
                        leadingIcon = if (panel.linkCopied) ZillitIcons.Check else ZillitIcons.Link,
                    )
                }
            }
        }
        ShareCard(
            ZillitIcons.Mail,
            "Copy as Text (for Email)",
            "Copy the schedule as plain text — paste into any email or message.",
        ) {
            ZillitButton(
                text = if (panel.textCopied) "Copied" else "Copy Schedule as Text",
                onClick = { onEvent(PanelEvent.CopyScheduleText) },
                variant = ButtonVariant.Secondary,
                leadingIcon = if (panel.textCopied) ZillitIcons.Check else ZillitIcons.Mail,
            )
        }
    }
}

@Composable
private fun ShareCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    hint: String,
    content: @Composable () -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZillitIcon(icon = icon, tint = colors.textSecondary, size = 14.dp)
            ZillitText(title, style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold))
        }
        ZillitText(hint, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        content()
    }
}

/** Cmd/Ctrl+K — type a command, Enter runs the first. */
@Composable
internal fun CommandPaletteDialog(state: BoxScheduleUiState, panel: PalettePanel, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val commands = DiaryCommand.available(state.mayEdit, panel.query)
    ZillitDialogShell(
        title = "Commands",
        icon = ZillitIcons.Search,
        onDismiss = { onEvent(PageEvent.ClosePalette) },
        visible = true,
        width = 520.dp,
    ) {
        ZillitTextField(
            value = panel.query,
            onValueChange = { onEvent(PageEvent.PaletteQuery(it)) },
            placeholder = "Type a command…",
            leadingIcon = ZillitIcons.Search,
            onImeAction = { commands.firstOrNull()?.let { onEvent(PageEvent.RunCommand(it)) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).zillitVerticalScroll(rememberScrollState())) {
            if (commands.isEmpty()) {
                ZillitText(
                    "No matching commands",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(24.dp),
                )
            }
            commands.forEach { command -> CommandRow(command) { onEvent(PageEvent.RunCommand(command)) } }
        }
        Row(Modifier.fillMaxWidth()) {
            ZillitText(
                "↑↓ navigate · ↵ run · esc close",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            ZillitText("Cmd+K to toggle", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
    }
}

@Composable
private fun CommandRow(command: DiaryCommand, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) colors.surfaceHover else Color.Transparent)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(command.label, style = ZillitTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (command.hint.isNotBlank()) {
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(colors.surfaceSunken).border(
                1.dp,
                colors.border,
                RoundedCornerShape(4.dp),
            ).padding(horizontal = 6.dp, vertical = 1.dp)) {
                ZillitText(command.hint, style = ZillitTheme.typography.labelSmall, color = colors.textSecondary)
            }
        }
    }
}

/**
 * "Schedule Types" — `ScheduleTypeManager`. A colour saves as it is picked;
 * a custom type edits in its row; system types keep their names.
 */
@Composable
internal fun TypesManagerDialog(state: BoxScheduleUiState, manager: TypesManager, onEvent: (BoxScheduleEvent) -> Unit) {
    ZillitDialogShell(
        title = "Schedule Types",
        icon = ZillitIcons.Settings,
        onDismiss = { onEvent(PanelEvent.CloseTypes) },
        visible = true,
        width = 480.dp,
        actions = {
            ZillitButton("Close", onClick = { onEvent(PanelEvent.CloseTypes) }, variant = ButtonVariant.Secondary)
        },
    ) {
        state.types.forEach { type -> TypeRow(type, manager, onEvent) }
        AddTypeRow(manager, onEvent)
    }
}

/** One type: recolour in place; a custom type also renames and deletes, a system type never does. */
@Composable
private fun TypeRow(type: ScheduleType, manager: TypesManager, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val editing = manager.editingId == type.id
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (editing) colors.accent else colors.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (editing) {
            TypeEditor(manager, onEvent)
        } else {
            ColorPickerButton(color = type.color, onPick = { onEvent(PanelEvent.RecolorType(type.id, it)) })
            ZillitText(
                type.title,
                style = ZillitTheme.typography.bodyMedium.copy(
                    fontWeight = if (type.systemDefined) FontWeight.SemiBold else FontWeight.Normal,
                ),
                modifier = Modifier.weight(1f),
            )
            if (type.systemDefined) {
                SmallBadge("SYSTEM")
            } else {
                ZillitIconButton(
                    icon = ZillitIcons.Edit,
                    contentDescription = "Edit type",
                    onClick = { onEvent(PanelEvent.StartTypeEdit(type.id)) },
                )
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Delete type",
                    onClick = { onEvent(PanelEvent.DeleteType(type.id)) },
                    tint = colors.danger,
                )
            }
        }
    }
}

/** A custom type's rename in place: colour, name, and save or cancel. */
@Composable
private fun RowScope.TypeEditor(manager: TypesManager, onEvent: (BoxScheduleEvent) -> Unit) {
    ColorPickerButton(color = manager.editColor, onPick = { onEvent(PanelEvent.SetEditColor(it)) })
    ZillitTextField(
        value = manager.editTitle,
        onValueChange = { onEvent(PanelEvent.SetEditTitle(it)) },
        placeholder = "Type name",
        onImeAction = { onEvent(PanelEvent.SaveTypeEdit) },
        modifier = Modifier.weight(1f),
    )
    ZillitIconButton(
        icon = ZillitIcons.Check,
        contentDescription = "Save",
        onClick = { onEvent(PanelEvent.SaveTypeEdit) },
        tint = ZillitTheme.colors.success,
        enabled = manager.editTitle.isNotBlank() && !manager.savingEdit,
    )
    ZillitIconButton(
        icon = ZillitIcons.Close,
        contentDescription = "Cancel",
        onClick = { onEvent(PanelEvent.CancelTypeEdit) },
    )
}

@Composable
private fun AddTypeRow(manager: TypesManager, onEvent: (BoxScheduleEvent) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FieldLabel("Add Custom Type")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorPickerButton(color = manager.newColor, onPick = { onEvent(PanelEvent.SetNewColor(it)) })
            ZillitTextField(
                value = manager.newTitle,
                onValueChange = { onEvent(PanelEvent.SetNewTitle(it)) },
                placeholder = "Type name",
                onImeAction = { onEvent(PanelEvent.AddType) },
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = "Add",
                onClick = { onEvent(PanelEvent.AddType) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
                enabled = manager.newTitle.isNotBlank(),
                loading = manager.creating,
            )
        }
    }
}
