package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.documentdistribution.domain.formatBytes
import com.zillit.desktop.feature.documentdistribution.ui.DocDistEvent
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import com.zillit.desktop.feature.documentdistribution.ui.pickerVisible

/**
 * "Attach documents" — the web's `DocumentPicker`: folders down the left,
 * a searchable file list on the right, select-all over what is visible.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // One screen section, one branch per state.
@Composable
internal fun DocumentPickerDialog(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val picker = state.picker
    val c = ZillitTheme.colors
    val visible = pickerVisible(state)
    val allVisible = visible.isNotEmpty() && visible.all { it.id in picker?.selected.orEmpty() }
    val count = picker?.selected?.size ?: 0
    ZillitDialogShell(
        title = "Attach documents",
        subtitle = "Pick files from any folder in your library",
        visible = picker != null,
        onDismiss = { onEvent(DocDistEvent.ClosePicker) },
        icon = ZillitIcons.Paperclip,
        width = 960.dp,
        scrollable = false,
        actions = {
            ZillitText(text = "$count selected", style = ZillitTheme.typography.label, color = c.textSecondary)
            if (count > 0) {
                ZillitButton(
                    text = "Clear all",
                    onClick = { onEvent(DocDistEvent.PickerClear) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
            Box(Modifier.weight(1f))
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(DocDistEvent.ClosePicker) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (count > 0) "Attach $count selected" else "Attach",
                onClick = { onEvent(DocDistEvent.PickerConfirm) },
                enabled = count > 0,
                leadingIcon = ZillitIcons.Check,
            )
        },
    ) {
        if (picker == null) return@ZillitDialogShell
        Row(
            Modifier.fillMaxWidth().height(PICKER_HEIGHT.dp),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitScrollColumn(
                modifier = Modifier
                    .width(SIDEBAR_WIDTH.dp)
                    .fillMaxHeight()
                    .clip(ZillitTheme.shapes.medium)
                    .background(c.surfaceSunken)
                    .padding(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                FolderPick(
                    "All documents",
                    depth = 0,
                    count = picker.documents.size,
                    active = picker.folderId == null,
                ) {
                    onEvent(DocDistEvent.PickerFolder(null))
                }
                if (state.folders.isNotEmpty()) {
                    FieldLabel(
                        "Folders",
                        Modifier.padding(
                            start = ZillitTheme.spacing.sm,
                            top = ZillitTheme.spacing.sm,
                            bottom = ZillitTheme.spacing.xxs,
                        ),
                    )
                }
                state.folderRows().forEach { row ->
                    val inside = state.folderWithDescendants(row.id)
                    FolderPick(
                        row.name,
                        depth = row.depth,
                        count = picker.documents.count { it.folderId in inside },
                        active = picker.folderId == row.id,
                    ) { onEvent(DocDistEvent.PickerFolder(row.id)) }
                }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitSearchField(
                        value = picker.search,
                        onValueChange = { onEvent(DocDistEvent.PickerSearch(it)) },
                        placeholder = "Search by filename",
                        modifier = Modifier.weight(1f),
                    )
                    if (visible.isNotEmpty()) {
                        ZillitButton(
                            text = if (allVisible) "Deselect all" else "Select all",
                            onClick = { onEvent(DocDistEvent.PickerToggleAllVisible) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                    }
                }
                Box(
                    Modifier.fillMaxSize().clip(ZillitTheme.shapes.medium).border(
                        0.5.dp,
                        c.border,
                        ZillitTheme.shapes.medium,
                    ),
                ) {
                    when {
                        picker.loading ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ZillitSpinner() }
                        visible.isEmpty() -> ZillitEmptyState(
                            title = if (picker.search.isNotBlank()) {
                                "No documents match your search"
                            } else {
                                "No documents in this folder"
                            },
                            icon = ZillitIcons.File,
                        )
                        else -> ZillitLazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(ZillitTheme.spacing.xs),
                        ) {
                            items(visible, key = { it.id }) { document ->
                                val checked = document.id in picker.selected
                                val path = document.folderId
                                    ?.let { id -> state.folders.firstOrNull { it.id == id }?.name }
                                    ?: "/ (root)"
                                HoverRow(
                                    selected = checked,
                                    onClick = { onEvent(DocDistEvent.PickerToggle(document.id)) },
                                ) {
                                    ZillitCheckbox(
                                        checked = checked,
                                        onCheckedChange = { onEvent(DocDistEvent.PickerToggle(document.id)) },
                                    )
                                    FileGlyph(document, size = 30.dp)
                                    Column(Modifier.weight(1f)) {
                                        ZillitText(
                                            text = document.name,
                                            style = ZillitTheme.typography.label,
                                            maxLines = 1,
                                        )
                                        ZillitText(
                                            text = "$path · ${formatBytes(document.sizeBytes)}" +
                                                prettyIsoDate(document.documentDate).takeIf { it != "—" }
                                                    ?.let { " · $it" }.orEmpty(),
                                            style = ZillitTheme.typography.bodySmall,
                                            color = c.textMuted,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderPick(name: String, depth: Int, count: Int, active: Boolean, onClick: () -> Unit) {
    val c = ZillitTheme.colors
    HoverRow(selected = active, onClick = onClick, padding = ZillitTheme.spacing.sm) {
        Box(Modifier.width((depth * INDENT).dp))
        if (depth == 0 && name == "All documents") {
            com.zillit.desktop.core.designsystem.component.ZillitIcon(
                icon = ZillitIcons.Grid,
                tint = if (active) c.accent else c.textSecondary,
                size = 14.dp,
            )
        } else {
            FolderGlyph(size = 20.dp)
        }
        ZillitText(
            text = name,
            style = ZillitTheme.typography.bodySmall.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (active) c.accentText else c.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) ZillitBadge(count = count, background = if (active) c.accent else c.borderStrong, cap = null)
    }
}

private const val PICKER_HEIGHT = 520
private const val SIDEBAR_WIDTH = 240
private const val INDENT = 12
