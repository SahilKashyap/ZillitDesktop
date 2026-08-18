package com.zillit.desktop.feature.draft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.draft.domain.ElementType
import com.zillit.desktop.feature.draft.domain.ScriptSummary

/** The tool: the production's scripts, or the one that is open. */
@Composable
fun DraftScreen(state: DraftUiState, onEvent: (DraftEvent) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        val open = state.open
        if (open == null) ScriptsPage(state, onEvent) else EditorPage(state, open, onEvent)
        ZillitToast(message = state.notice, onDismiss = { onEvent(DraftEvent.ClearNotice) },
            tone = ZillitToastTone.Success)
    }
}

// -- the list ---------------------------------------------------------------

@Composable
private fun ScriptsPage(state: DraftUiState, onEvent: (DraftEvent) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().background(ZillitTheme.colors.surface)
                .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.lg),
        ) {
            ZillitPageHeader(
                eyebrow = "Writing",
                title = "Zillit Draft",
                description = "Write and format screenplays — scene headings, action, dialogue — the way " +
                    "Final Draft does, saved on this production and exported to PDF, Final Draft or Fountain.",
                actions = {
                    ZillitButton(
                        text = "Import",
                        onClick = { onEvent(DraftEvent.Import) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Upload,
                    )
                    ZillitButton(
                        text = "New script",
                        onClick = { onEvent(DraftEvent.StartNew) },
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Add,
                    )
                },
            )
        }
        ZillitDivider()
        Box(Modifier.fillMaxSize().padding(ZillitTheme.spacing.xl)) {
            ZillitDataTable(
                rows = state.scripts,
                key = { it.id },
                loading = state.loading && state.scripts.isEmpty(),
                columns = scriptColumns(onEvent),
                onRowClick = { onEvent(DraftEvent.Open(it.id)) },
                emptyTitle = "No scripts on this production yet",
                emptyMessage = "Start a new script, or import a .fountain or .fdx file.",
            )
        }
    }
    ScriptDialogs(state, onEvent)
}

private fun scriptColumns(onEvent: (DraftEvent) -> Unit): List<TableColumn<ScriptSummary>> = listOf(
    TableColumn(header = "Title", width = ColumnWidth.Weight(TITLE_WEIGHT)) { script ->
        ZillitText(text = script.title, style = ZillitTheme.typography.bodyMedium, maxLines = 1)
    },
    textColumn("Pages", ColumnWidth.Fixed(PAGES_COLUMN), numeric = true) { it.pageCount.toString() },
    textColumn("Scenes", ColumnWidth.Fixed(PAGES_COLUMN), numeric = true) { it.sceneCount.toString() },
    textColumn("Updated", ColumnWidth.Fixed(DATE_COLUMN), muted = true) { EpochDate.date(it.updatedAtMillis) },
    TableColumn(header = "", width = ColumnWidth.Fixed(ACTIONS_COLUMN)) { script ->
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitIconButton(
                icon = ZillitIcons.Edit,
                contentDescription = "Rename",
                onClick = { onEvent(DraftEvent.StartRename(script)) },
            )
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = "Delete",
                onClick = { onEvent(DraftEvent.RequestDelete(script)) },
                tint = ZillitTheme.colors.danger,
            )
        }
    },
)

@Composable
private fun ScriptDialogs(state: DraftUiState, onEvent: (DraftEvent) -> Unit) {
    if (state.creating) {
        ZillitDialogShell(
            title = "New script",
            onDismiss = { onEvent(DraftEvent.CancelNew) },
            visible = true,
            actions = {
                ZillitButton(text = "Cancel", onClick = { onEvent(DraftEvent.CancelNew) },
                    variant = ButtonVariant.Tertiary)
                ZillitButton(text = "Create", onClick = { onEvent(DraftEvent.CreateNew) })
            },
        ) {
            ZillitTextField(
                value = state.newTitle,
                onValueChange = { onEvent(DraftEvent.NewTitleChanged(it)) },
                label = "Title",
                placeholder = "Untitled",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    state.renaming?.let {
        ZillitDialogShell(
            title = "Rename script",
            onDismiss = { onEvent(DraftEvent.CancelRename) },
            visible = true,
            actions = {
                ZillitButton(text = "Cancel", onClick = { onEvent(DraftEvent.CancelRename) },
                    variant = ButtonVariant.Tertiary)
                ZillitButton(text = "Rename", onClick = { onEvent(DraftEvent.ConfirmRename) })
            },
        ) {
            ZillitTextField(
                value = state.renameTitle,
                onValueChange = { onEvent(DraftEvent.RenameTitleChanged(it)) },
                label = "Title",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    state.confirmDelete?.let { script ->
        ZillitDialogShell(
            title = "Delete script",
            onDismiss = { onEvent(DraftEvent.CancelDelete) },
            visible = true,
            actions = {
                ZillitButton(text = "Cancel", onClick = { onEvent(DraftEvent.CancelDelete) },
                    variant = ButtonVariant.Tertiary)
                ZillitButton(text = "Delete", onClick = { onEvent(DraftEvent.ConfirmDelete) },
                    variant = ButtonVariant.Danger)
            },
        ) {
            ZillitText(
                text = "Delete \"${script.title}\"? This cannot be undone.",
                style = ZillitTheme.typography.bodyMedium,
            )
        }
    }
}

// -- the editor -------------------------------------------------------------

@Composable
private fun EditorPage(state: DraftUiState, open: OpenScript, onEvent: (DraftEvent) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        EditorToolbar(state, open, onEvent)
        ZillitDivider()
        Row(Modifier.fillMaxSize()) {
            if (open.navigatorOpen) {
                NavigatorPane(open, onEvent, Modifier.width(NAVIGATOR_WIDTH).fillMaxHeight())
                ZillitVerticalDivider()
            }
            ScriptEditor(open, onEvent, Modifier.weight(1f).fillMaxHeight())
        }
    }
    if (open.titlePageOpen) TitlePageDialog(open, onEvent)
}

@Composable
@Suppress("LongMethod") // The toolbar's buttons, one after another.
private fun EditorToolbar(state: DraftUiState, open: OpenScript, onEvent: (DraftEvent) -> Unit) {
    val colors = ZillitTheme.colors
    var exportOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(colors.surface)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIconButton(
            icon = ZillitIcons.ArrowLeft,
            contentDescription = "Back to scripts",
            onClick = { onEvent(DraftEvent.CloseScript) },
        )
        if (open.editingTitle) {
            ZillitTextField(
                value = open.screenplay.title,
                onValueChange = { onEvent(DraftEvent.ScriptTitleChanged(it)) },
                singleLine = true,
                modifier = Modifier.width(TITLE_FIELD),
            )
            ZillitButton(text = "Done", onClick = { onEvent(DraftEvent.FinishEditTitle) }, size = ButtonSize.Small)
        } else {
            ZillitText(
                text = open.screenplay.title.ifBlank { "Untitled" },
                style = ZillitTheme.typography.titleMedium,
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.clickable { onEvent(DraftEvent.StartEditTitle) },
            )
        }
        val focused = open.focused
        ZillitSelect(
            value = focused?.type ?: ElementType.Action,
            options = ElementType.entries,
            onSelect = { type -> focused?.let { onEvent(DraftEvent.SetType(it.id, type)) } },
            label = { "${it.label}  ⌘${it.shortcut}" },
            modifier = Modifier.width(TYPE_SELECT),
            enabled = focused != null,
        )
        Box(Modifier.weight(1f))
        ZillitText(
            text = when {
                open.dirty -> "Saving…"
                open.savedAtMillis != null -> "Saved"
                else -> ""
            },
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )
        ZillitButton(
            text = "Title page",
            onClick = { onEvent(DraftEvent.OpenTitlePage) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        ZillitButton(
            text = if (open.navigatorOpen) "Hide navigator" else "Navigator",
            onClick = { onEvent(DraftEvent.ToggleNavigator) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
        Box {
            ZillitButton(
                text = "Export",
                onClick = { exportOpen = true },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Download,
                loading = state.busy,
            )
            androidx.compose.material3.DropdownMenu(expanded = exportOpen, onDismissRequest = { exportOpen = false }) {
                ExportFormat.entries.forEach { format ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { ZillitText(text = format.label, style = ZillitTheme.typography.bodyMedium) },
                        onClick = {
                            exportOpen = false
                            onEvent(DraftEvent.Export(format))
                        },
                    )
                }
                androidx.compose.material3.DropdownMenuItem(
                    text = { ZillitText(text = "Send PDF to Drive", style = ZillitTheme.typography.bodyMedium) },
                    onClick = {
                        exportOpen = false
                        onEvent(DraftEvent.SendToDrive)
                    },
                )
            }
        }
    }
}

/** Scenes and cast down the side, with the script's numbers at the foot. */
@Composable
@Suppress("LongMethod") // Two tabs and a footer; one composable reads better than three that share state.
private fun NavigatorPane(open: OpenScript, onEvent: (DraftEvent) -> Unit, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    var tab by remember { mutableStateOf("scenes") }
    val pages = open.pagination.pageOfElement
    Column(modifier.background(colors.surface)) {
        Box(Modifier.padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs)) {
            ZillitTabStrip(
                tabs = listOf(ZillitTab("scenes", "Scenes"), ZillitTab("cast", "Cast")),
                activeId = tab,
                onSelect = { tab = it },
            )
        }
        ZillitDivider()
        LazyColumn(Modifier.weight(1f)) {
            if (tab == "scenes") {
                items(open.screenplay.scenes, key = { it.value.id }) { (index, scene) ->
                    val number = open.screenplay.scenes.indexOfFirst { it.index == index } + 1
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onEvent(DraftEvent.JumpTo(scene.id)) }
                            .background(if (scene.id == open.focusedId) colors.surfaceSelected else colors.surface)
                            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    ) {
                        ZillitText(text = "$number", style = ZillitTheme.typography.labelSmall,
                            color = colors.textMuted)
                        ZillitText(
                            text = scene.text.ifBlank { "(untitled scene)" },
                            style = ZillitTheme.typography.bodySmall,
                            color = colors.textPrimary,
                            maxLines = 2,
                            modifier = Modifier.weight(1f),
                        )
                        ZillitText(
                            text = "p${pages.getOrNull(index) ?: 1}",
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.textMuted,
                        )
                    }
                }
            } else {
                items(open.castLines, key = { it.first }) { (name, lines) ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.md,
                            vertical = ZillitTheme.spacing.xs),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        ZillitText(text = name, style = ZillitTheme.typography.bodySmall, color = colors.textPrimary)
                        ZillitText(
                            text = "$lines line${if (lines == 1) "" else "s"}",
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }
        ZillitDivider()
        Column(Modifier.padding(ZillitTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Stat("Pages", open.pageCount.toString())
            Stat("Scenes", open.sceneCount.toString())
            Stat("Words", open.screenplay.wordCount.toString())
            Stat("Cast", open.characters.size.toString())
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        ZillitText(text = label, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
        ZillitText(text = value, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textPrimary)
    }
}

@Composable
private fun TitlePageDialog(open: OpenScript, onEvent: (DraftEvent) -> Unit) {
    val page = open.screenplay.titlePage
    val change = { updated: com.zillit.desktop.feature.draft.domain.TitlePage -> onEvent(DraftEvent
        .TitlePageChanged(updated)) }
    ZillitDialogShell(
        title = "Title page",
        subtitle = "What prints before page one.",
        onDismiss = { onEvent(DraftEvent.CloseTitlePage) },
        visible = true,
        actions = { ZillitButton(text = "Done", onClick = { onEvent(DraftEvent.CloseTitlePage) }) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(value = page.title, onValueChange = { change(page.copy(title = it)) }, label = "Title",
                modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(value = page.credit, onValueChange = { change(page.copy(credit = it)) },
                    label = "Credit",
                    modifier = Modifier.weight(1f))
                ZillitTextField(value = page.author, onValueChange = { change(page.copy(author = it)) },
                    label = "Author",
                    modifier = Modifier.weight(2f))
            }
            ZillitTextField(value = page.source, onValueChange = { change(page.copy(source = it)) }, label = "Source",
                placeholder = "Based on…", modifier = Modifier.fillMaxWidth())
            ZillitTextField(value = page.draftDate, onValueChange = { change(page.copy(draftDate = it)) },
                label = "Draft date", modifier = Modifier.fillMaxWidth())
            ZillitTextField(value = page.contact, onValueChange = { change(page.copy(contact = it)) },
                label = "Contact",
                singleLine = false, modifier = Modifier.fillMaxWidth())
            ZillitTextField(value = page.notes, onValueChange = { change(page.copy(notes = it)) }, label = "Notes",
                singleLine = false, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ZillitSpinner() }
}

private const val TITLE_WEIGHT = 3f
private val NAVIGATOR_WIDTH = 260.dp
private val TITLE_FIELD = 320.dp
private val TYPE_SELECT = 200.dp
private val PAGES_COLUMN = 80.dp
private val DATE_COLUMN = 130.dp
private val ACTIONS_COLUMN = 90.dp
