package com.zillit.desktop.feature.settings.admin.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.cabinet.CabinetEntry
import com.zillit.desktop.core.cabinet.CabinetViewer
import com.zillit.desktop.core.cabinet.CabinetViewers
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.admin.domain.CabinetModule
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.admin.ui.AdminEvent
import com.zillit.desktop.feature.settings.admin.ui.AdminUiState
import com.zillit.desktop.feature.settings.admin.ui.FileCabinetState

/** A viewer window that is open: which row, and which of its entries (null for a row that opens directly). */
private data class OpenViewer(val module: CabinetModule, val viewer: CabinetViewer, val entry: CabinetEntry?) {
    val title: String get() = entry?.name ?: module.name
}

/**
 * File Cabinet Documents — the web's `FileCabinateModal`.
 *
 * Two modes, as on the web. **Browse** is the default: a row opens a read-only
 * viewer of that module's archived records (or expands into its units, rooms or
 * tabs first). **Select** turns the rows into tick boxes, to build a request
 * from: ask for a ZIP, and download it once the server has prepared it.
 *
 * One request at a time. While it is being prepared the only action is
 * cancelling it, and once it is ready the only actions are the steps to unzip
 * it and downloading it.
 *
 * The viewers are [viewers]: each feature contributes the one for the tool it
 * owns. A row with none — Account Hub, Document Distribution, Transportation
 * and Recce on the web, whose rows do nothing on click — is select-and-download
 * only.
 */
@Composable
fun FileCabinetPage(
    state: AdminUiState,
    onEvent: (AdminEvent) -> Unit,
    onBack: () -> Unit,
    viewers: CabinetViewers = CabinetViewers.None,
) {
    val cabinet = state.fileCabinet
    val uri = LocalUriHandler.current
    var showSteps by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<OpenViewer?>(null) }
    // The window keeps its last content while it fades out.
    var lastOpen by remember { mutableStateOf<OpenViewer?>(null) }
    open?.let { lastOpen = it }

    // The ZIP's address goes to the browser, which downloads it — the web's
    // `window.open(url, '_self')` — and the page forgets it.
    LaunchedEffect(cabinet.downloadUrl) {
        cabinet.downloadUrl?.let { url ->
            uri.openUri(url)
            onEvent(AdminEvent.CabinetUrlOpened)
        }
    }

    AdminPage(
        title = AdminDestination.FileCabinet.title,
        description = str(S.file_cabinet_info),
        state = state,
        onEvent = onEvent,
        onBack = onBack,
        search = str(S.search),
        action = { Actions(cabinet, onEvent, onSteps = { showSteps = true }) },
    ) {
        val rows = state.cabinetMatching
        RowCard {
            if (rows.isEmpty() && state.hasLoaded) EmptyRow(str(S.desktop_fc_no_documents))
            rows.forEachIndexed { index, module ->
                if (index > 0) RowRule()
                if (cabinet.selecting) {
                    SelectRow(module, cabinet, onEvent)
                } else {
                    BrowseRow(module, viewers[module.identifier]) { viewer, entry ->
                        open = OpenViewer(module, viewer, entry)
                    }
                }
            }
        }
    }

    ZillitDialogShell(
        title = AdminDestination.FileCabinet.title,
        visible = cabinet.showNotice,
        onDismiss = { onEvent(AdminEvent.CabinetNoticeDismissed) },
        actions = {
            ZillitButton(text = str(S.ok), onClick = { onEvent(AdminEvent.CabinetNoticeDismissed) })
        },
    ) {
        ZillitText(
            text = str(S.desktop_fc_ready_message),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }

    StepsDialog(visible = showSteps, onDismiss = { showSteps = false })

    ViewerWindow(shown = lastOpen, visible = open != null, onDismiss = { open = null })
}

/** The window a viewer appears in; the viewer fills it, settings own the frame. */
@Composable
private fun ViewerWindow(shown: OpenViewer?, visible: Boolean, onDismiss: () -> Unit) {
    ZillitDialogShell(
        title = shown?.title.orEmpty(),
        visible = visible,
        onDismiss = onDismiss,
        icon = shown?.let { cabinetIcon(it.module.identifier) },
        width = VIEWER_WIDTH,
        maxHeight = VIEWER_HEIGHT,
        scrollable = false,
    ) {
        // Keyed on the entry so opening another one starts the viewer afresh
        // rather than handing it the last one's scroll position and selection.
        shown?.let { open ->
            key(open.module.identifier, open.entry?.key) {
                open.viewer.Content(open.entry, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

/** The corner: what can be done about the request as it stands. */
@Composable
private fun RowScope.Actions(cabinet: FileCabinetState, onEvent: (AdminEvent) -> Unit, onSteps: () -> Unit) {
    val request = cabinet.request
    when {
        request == null -> ChooseActions(cabinet, onEvent)
        request.isPreparing -> PreparingActions(cabinet, onEvent)
        else -> ReadyActions(cabinet, onEvent, onSteps)
    }
}

/** No request yet: Select / Deselect, and once something is ticked, Request Download. */
@Composable
private fun RowScope.ChooseActions(cabinet: FileCabinetState, onEvent: (AdminEvent) -> Unit) {
    if (cabinet.selecting && cabinet.selected.isNotEmpty()) {
        ZillitText(
            text = "${cabinet.selected.size} ${str(S.selected)}",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
    if (cabinet.selecting) {
        ZillitButton(
            text = if (cabinet.allSelected) str(S.dd_deselect_all) else str(S.select_all),
            onClick = { onEvent(AdminEvent.CabinetSelectAllToggled) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = cabinet.modules.isNotEmpty() && !cabinet.isWorking,
        )
    }
    ZillitButton(
        text = if (cabinet.selecting) str(S.deselect) else str(S.select),
        onClick = { onEvent(AdminEvent.CabinetSelectModeToggled) },
        variant = if (cabinet.selecting) ButtonVariant.Secondary else ButtonVariant.Primary,
        size = ButtonSize.Small,
        enabled = cabinet.modules.isNotEmpty() && !cabinet.isWorking,
    )
    if (cabinet.selected.isNotEmpty()) {
        ZillitButton(
            text = str(S.desktop_fc_request_download),
            onClick = { onEvent(AdminEvent.CabinetRequestDownload) },
            size = ButtonSize.Small,
            enabled = !cabinet.isWorking,
            loading = cabinet.isWorking,
        )
    }
}

/** The server is building the ZIP: the only thing to do is cancel. */
@Composable
private fun RowScope.PreparingActions(cabinet: FileCabinetState, onEvent: (AdminEvent) -> Unit) {
    ZillitButton(
        text = str(S.desktop_fc_cancel_request),
        onClick = { onEvent(AdminEvent.CabinetCancelRequest) },
        variant = ButtonVariant.Danger,
        size = ButtonSize.Small,
        enabled = !cabinet.isWorking,
    )
    ZillitButton(
        text = "${str(S.desktop_fc_download_in_progress)}...",
        onClick = {},
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        enabled = false,
    )
}

/** The ZIP is ready: how to unzip it, and the download. */
@Composable
private fun RowScope.ReadyActions(cabinet: FileCabinetState, onEvent: (AdminEvent) -> Unit, onSteps: () -> Unit) {
    ZillitButton(
        text = str(S.desktop_fc_steps_to_download),
        onClick = onSteps,
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
    )
    ZillitButton(
        text = str(S.download),
        onClick = { onEvent(AdminEvent.CabinetDownload) },
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Download,
        enabled = !cabinet.isWorking,
        loading = cabinet.isWorking,
    )
}

/** Select mode: a tick box, the module's icon and its name. */
@Composable
private fun SelectRow(module: CabinetModule, cabinet: FileCabinetState, onEvent: (AdminEvent) -> Unit) {
    val locked = cabinet.request != null
    // Account Hub, Document Distribution, Transportation and Recce are ticked by
    // their box only — the body of the row does nothing, as on the web.
    val rowClickable = !locked && !module.rowClickDisabled
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = rowClickable) { onEvent(AdminEvent.CabinetToggled(module.identifier)) }
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitCheckbox(
            checked = module.identifier in cabinet.selected,
            onCheckedChange = { onEvent(AdminEvent.CabinetToggled(module.identifier)) },
            enabled = !locked,
        )
        IconChip(module.identifier)
        ZillitText(
            text = module.name,
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
    }
}

/**
 * Browse mode: a row that opens the module's viewer, or expands into the
 * viewer's entries first. A row with no viewer — or one whose click is switched
 * off — is a plain label.
 */
@Composable
private fun BrowseRow(
    module: CabinetModule,
    viewer: CabinetViewer?,
    onOpen: (CabinetViewer, CabinetEntry?) -> Unit,
) {
    val interactive = viewer != null && !module.rowClickDisabled
    // The viewer, when this row may use it at all.
    val live = viewer?.takeIf { interactive }
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = interactive) {
                    if (viewer?.expands == true) expanded = !expanded else if (viewer != null) onOpen(viewer, null)
                }
                .padding(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            IconChip(module.identifier)
            ZillitText(
                text = module.name,
                style = ZillitTheme.typography.bodyMedium,
                color = if (interactive) ZillitTheme.colors.textPrimary else ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (live != null) {
                ZillitIcon(
                    icon = if (live.expands && expanded) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                    contentDescription = null,
                    tint = ZillitTheme.colors.textMuted,
                    size = CHEVRON,
                )
            }
        }
        if (live != null && live.expands && expanded) EntryRows(live, onOpen)
    }
}

/** The entries under an expanded row; loaded when it first opens. */
@Composable
private fun EntryRows(viewer: CabinetViewer, onOpen: (CabinetViewer, CabinetEntry?) -> Unit) {
    val entries by produceState<List<CabinetEntry>?>(initialValue = null, viewer) { value = viewer.entries() }
    val loaded = entries
    when {
        loaded == null -> Unit
        loaded.isEmpty() -> ZillitText(
            text = str(S.desktop_fc_no_documents),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.padding(start = ENTRY_INDENT, bottom = ZillitTheme.spacing.md),
        )

        else -> loaded.forEach { entry ->
            RowRule(inset = ENTRY_INDENT)
            ZillitText(
                text = entry.name,
                style = ZillitTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(viewer, entry) }
                    .padding(
                        start = ENTRY_INDENT,
                        end = ZillitTheme.spacing.md,
                        top = ZillitTheme.spacing.sm,
                        bottom = ZillitTheme.spacing.sm,
                    ),
                maxLines = 1,
            )
        }
    }
}

/** The module's icon on an accent chip — the web's `fc-icon-chip`. */
@Composable
private fun IconChip(identifier: String) {
    Box(
        modifier = Modifier
            .size(CHIP)
            .clip(RoundedCornerShape(CHIP_RADIUS))
            .background(ZillitTheme.colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(
            icon = cabinetIcon(identifier),
            contentDescription = null,
            tint = ZillitTheme.colors.accent,
            size = CHIP_GLYPH,
        )
    }
}

/** "Steps to download": how to unzip what the browser fetched, as the web words it. */
@Composable
private fun StepsDialog(visible: Boolean, onDismiss: () -> Unit) {
    ZillitDialogShell(
        title = str(S.desktop_fc_steps_title),
        visible = visible,
        onDismiss = onDismiss,
        width = STEPS_WIDTH,
        actions = { ZillitButton(text = str(S.ok), onClick = onDismiss) },
    ) {
        Steps(
            heading = str(S.desktop_fc_steps_pc),
            steps = listOf(
                S.desktop_fc_steps_pc_1,
                S.desktop_fc_steps_pc_2,
                S.desktop_fc_steps_pc_3,
                S.desktop_fc_steps_pc_4,
                S.desktop_fc_steps_pc_5,
                S.desktop_fc_steps_pc_6,
            ),
        )
        Steps(
            heading = str(S.desktop_fc_steps_mac),
            steps = listOf(
                S.desktop_fc_steps_pc_1,
                S.desktop_fc_steps_mac_2,
                S.desktop_fc_steps_mac_3,
                S.desktop_fc_steps_mac_4,
                S.desktop_fc_steps_mac_5,
            ),
        )
        listOf(S.desktop_fc_steps_note_folder, S.desktop_fc_steps_note_keep).forEach { note ->
            ZillitText(
                text = str(note),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun Steps(heading: String, steps: List<String>) {
    ZillitText(text = heading, style = ZillitTheme.typography.titleSmall)
    steps.forEachIndexed { index, key ->
        ZillitText(
            text = "${index + 1}. ${str(key)}",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

private val CHIP = 28.dp
private val CHIP_RADIUS = 8.dp
private val CHIP_GLYPH = 16.dp
private val CHEVRON = 16.dp
private val ENTRY_INDENT = 56.dp
private val VIEWER_WIDTH = 1100.dp
private val VIEWER_HEIGHT = 760.dp
private val STEPS_WIDTH = 720.dp
