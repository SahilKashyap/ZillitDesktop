package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.avatarHue
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.drive.domain.DriveFileVersion
import com.zillit.desktop.feature.drive.domain.DriveVersionSession
import com.zillit.desktop.feature.drive.domain.DriveVersionDay
import com.zillit.desktop.feature.drive.domain.describeChanges
import com.zillit.desktop.feature.drive.domain.groupHistory
import com.zillit.desktop.feature.drive.domain.isCurrentVersion
import com.zillit.desktop.feature.drive.domain.joinNames
import com.zillit.desktop.feature.drive.domain.saveTypeLabel
import com.zillit.desktop.feature.drive.domain.sessionTimeRange
import com.zillit.desktop.feature.drive.domain.versionDateTime
import com.zillit.desktop.feature.drive.domain.versionTime
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState
import com.zillit.desktop.feature.drive.ui.LocalDriveNow
import com.zillit.desktop.feature.drive.ui.VersionHistoryState

/**
 * The version history panel — `versionHistory/VersionHistoryPanel.jsx`, in
 * the desktop's own drawer.
 *
 * The list shows editing sessions under day headings, newest first; a
 * session's row is its newest save and its older ones unfold beneath. Opening
 * a version shows what changed in it, with Restore, Name, Download and "Open
 * read-only" on it.
 *
 * ## Opening a version does not open a window
 *
 * The web selects a version and the editor shows it read-only in a second
 * frame beside the panel. Here the editor is a window of its own, so a row
 * press opens the *detail*, and "Open read-only" is the explicit press that
 * opens a window — a window per row would bury the panel under them.
 *
 * ## The rights come from the history route
 *
 * Naming, restoring and downloading gate on `can_edit` / `can_download` from
 * the history itself. A file shared straight with someone carries no
 * permissions on the rows the tab-wide lists return, so the listing row's
 * rights are not a safe answer here.
 */
@Composable
internal fun VersionHistorySheet(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val panel = state.versionHistory
    DriveSideSheet(
        title = str(S.desktop_drive_version_history),
        subtitle = panel.history?.fileName?.ifBlank { panel.item?.name } ?: panel.item?.name,
        visible = panel.open,
        onDismiss = { onEvent(DriveEvent.CloseVersionHistory) },
        icon = ZillitIcons.Clock,
        headerTrailing = {
            ZillitIconButton(
                icon = ZillitIcons.Reload,
                contentDescription = str(S.refresh_text),
                onClick = { onEvent(DriveEvent.RefreshVersionHistory) },
            )
        },
    ) {
        if (!panel.open) return@DriveSideSheet
        val nameOf = { id: String -> state.personName(id) }
        if (panel.detail != null) {
            VersionDetail(panel, nameOf, onEvent)
        } else {
            VersionList(panel, nameOf, onEvent)
        }
    }
    NameVersionDialog(panel, onEvent)
}

// -- the list -------------------------------------------------------------

@Composable
private fun ColumnScope.VersionList(
    panel: VersionHistoryState,
    nameOf: (String) -> String,
    onEvent: (DriveEvent) -> Unit,
) {
    Toolbar(panel, onEvent)
    val days = groupHistory(panel.sessions, panel.namedOnly, LocalDriveNow.current())
    when {
        panel.loading && panel.history == null -> PanelSpinner()
        panel.failed && panel.history == null -> HistoryFailed(onEvent)
        days.isEmpty() -> EmptyHistory(panel.namedOnly)
        else -> Days(panel, days, nameOf, onEvent)
    }
}

@Composable
private fun ColumnScope.Days(
    panel: VersionHistoryState,
    days: List<DriveVersionDay>,
    nameOf: (String) -> String,
    onEvent: (DriveEvent) -> Unit,
) {
    days.forEach { day ->
        ZillitText(
            text = day.label,
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textSecondary,
        )
        day.sessions.forEach { session -> SessionRows(panel, session, nameOf, onEvent) }
        ZillitDivider()
    }
    if (panel.history?.truncated == true) {
        ZillitText(
            text = str(S.desktop_drive_showing_recent_versions),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** "Name current version", and the filter once anything has a name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Toolbar(panel: VersionHistoryState, onEvent: (DriveEvent) -> Unit) {
    val current = panel.current
    val named = panel.namedCount
    val canName = panel.canEdit && current != null && !current.synthetic
    val showsFilter = named > 0 || panel.namedOnly
    if (!canName && !showsFilter) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        if (canName && current != null) {
            ZillitButton(
                text = if (current.name.isNotBlank()) {
                    str(S.desktop_drive_rename_current_version)
                } else {
                    str(S.desktop_drive_name_current_version)
                },
                onClick = { onEvent(DriveEvent.StartNameVersion(current)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Edit,
            )
        }
        if (showsFilter) {
            ZillitTooltip(text = str(S.desktop_drive_named_versions_hint)) {
                ZillitSwitch(
                    checked = panel.namedOnly,
                    onCheckedChange = { onEvent(DriveEvent.ShowNamedVersionsOnly(it)) },
                    label = str(S.desktop_drive_only_named_versions, named),
                )
            }
        }
    }
}

/** One session: its newest save, and the older ones once unfolded. */
@Composable
private fun ColumnScope.SessionRows(
    panel: VersionHistoryState,
    session: DriveVersionSession,
    nameOf: (String) -> String,
    onEvent: (DriveEvent) -> Unit,
) {
    val latest = session.versions.firstOrNull() ?: return
    VersionRow(panel, session, latest, nested = false, nameOf = nameOf, onEvent = onEvent)
    if (panel.isExpanded(session)) {
        session.versions.drop(1).forEach { version ->
            VersionRow(panel, session, version, nested = true, nameOf = nameOf, onEvent = onEvent)
        }
    }
}

@Composable
@Suppress("LongMethod") // One row: disclosure, name, time, people, what changed, actions.
private fun VersionRow(
    panel: VersionHistoryState,
    session: DriveVersionSession,
    version: DriveFileVersion,
    nested: Boolean,
    nameOf: (String) -> String,
    onEvent: (DriveEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val current = isCurrentVersion(version, panel.current)
    val expanded = panel.isExpanded(session)
    // A save inside a session lists everyone whose edits it holds
    // (co-editing), not only the browser that happened to write it.
    val people = if (nested) version.people else session.editorIds.ifEmpty { version.people }
    val summary = describeChanges(version.changes, panel.fileKind, nameOf)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .clickable { onEvent(DriveEvent.OpenVersionDetail(version.id)) }
            .padding(
                start = if (nested) ZillitTheme.spacing.lg else ZillitTheme.spacing.xs,
                end = ZillitTheme.spacing.xs,
                top = ZillitTheme.spacing.xs,
                bottom = ZillitTheme.spacing.xs,
            ),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        if (!nested && session.size > 1) {
            ZillitIconButton(
                icon = if (expanded) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                contentDescription = if (expanded) {
                    str(S.desktop_drive_hide_saves_in_session)
                } else {
                    str(S.desktop_drive_show_saves_in_session)
                },
                onClick = { onEvent(DriveEvent.ToggleVersionSession(session.id)) },
            )
        } else {
            Box(Modifier.size(DISCLOSURE_SPACE))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (version.name.isNotBlank()) {
                ZillitText(text = version.name, style = ZillitTheme.typography.label, maxLines = 1)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = if (nested) versionTime(version.savedAt) else sessionTimeRange(session),
                    style = ZillitTheme.typography.bodySmall,
                )
                if (current && !nested) {
                    ZillitStatusPill(label = str(S.desktop_drive_current_version), tone = StatusTone.Progress)
                }
            }
            People(people, nameOf)
            ZillitText(
                text = listOf(
                    if (!nested && session.size > 1) str(S.desktop_drive_n_saves, session.size)
                    else saveTypeLabel(version),
                    summary,
                ).filter { it.isNotEmpty() }.joinToString(" · "),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
        RowActions(panel, version, current, onEvent)
    }
}

/** The people on a save, as coloured dots and names — the web's `vh-person`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun People(ids: List<String>, nameOf: (String) -> String) {
    if (ids.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ids.forEach { id ->
            val name = nameOf(id)
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(PERSON_DOT).clip(ZillitTheme.shapes.pill).background(avatarHue(name)))
                ZillitText(
                    text = name,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Name, Restore and Download on a row, each only where the server allows it. */
@Composable
private fun RowActions(
    panel: VersionHistoryState,
    version: DriveFileVersion,
    current: Boolean,
    onEvent: (DriveEvent) -> Unit,
) {
    val item = panel.item ?: return
    if (version.synthetic) return
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (panel.canEdit) {
            ZillitTooltip(
                text = if (version.name.isNotBlank()) {
                    str(S.desktop_drive_rename_version)
                } else {
                    str(S.desktop_drive_name_this_version)
                },
            ) {
                ZillitIconButton(
                    icon = ZillitIcons.Edit,
                    contentDescription = str(S.desktop_drive_name_this_version),
                    onClick = { onEvent(DriveEvent.StartNameVersion(version)) },
                )
            }
        }
        if (panel.canEdit && !current) {
            ZillitTooltip(text = str(S.desktop_drive_restore_this_version)) {
                ZillitIconButton(
                    icon = ZillitIcons.Reload,
                    contentDescription = str(S.desktop_drive_restore_version_n, version.versionNumber),
                    onClick = { onEvent(DriveEvent.RequestRestoreVersion(item.id, version.id)) },
                )
            }
        }
        if (panel.canDownload) {
            ZillitTooltip(text = str(S.desktop_drive_download_this_version)) {
                ZillitIconButton(
                    icon = ZillitIcons.Download,
                    contentDescription = str(S.desktop_drive_download_version_n, version.versionNumber),
                    onClick = { onEvent(DriveEvent.DownloadVersion(item, version.id)) },
                )
            }
        }
    }
}

// -- one version ----------------------------------------------------------

@Composable
private fun ColumnScope.VersionDetail(
    panel: VersionHistoryState,
    nameOf: (String) -> String,
    onEvent: (DriveEvent) -> Unit,
) {
    val version = panel.detail ?: return
    val now = LocalDriveNow.current()
    val current = isCurrentVersion(version, panel.current)
    ZillitButton(
        text = str(S.desktop_drive_all_versions),
        onClick = { onEvent(DriveEvent.CloseVersionDetail) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.ArrowLeft,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        if (current) ZillitStatusPill(label = str(S.desktop_drive_current_version), tone = StatusTone.Progress)
        if (version.name.isNotBlank()) {
            ZillitText(text = version.name, style = ZillitTheme.typography.titleSmall)
        }
        ZillitText(text = versionDateTime(version.savedAt, now), style = ZillitTheme.typography.bodySmall)
        ZillitText(
            text = listOf(
                saveTypeLabel(version),
                if (version.versionNumber > 0) str(S.desktop_version_number, version.versionNumber) else "",
            ).filter { it.isNotEmpty() }.joinToString(" · "),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textSecondary,
        )
        DetailPeople(version, nameOf)
        if (!version.synthetic) DetailActions(panel, version, current, onEvent)
    }
    ZillitDivider()
    ZillitText(text = str(S.desktop_drive_what_changed), style = ZillitTheme.typography.label)
    VersionChangesView(panel, nameOf, onEvent)
}

@Composable
private fun DetailPeople(version: DriveFileVersion, nameOf: (String) -> String) {
    val ids = version.people
    if (ids.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ids.take(MAX_FACES).forEach { id ->
            ZillitAvatar(name = nameOf(id), userId = id, size = FACE)
        }
        if (ids.size > MAX_FACES) {
            ZillitText(
                text = "+${ids.size - MAX_FACES}",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        ZillitText(
            text = joinNames(ids.map(nameOf)),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailActions(
    panel: VersionHistoryState,
    version: DriveFileVersion,
    current: Boolean,
    onEvent: (DriveEvent) -> Unit,
) {
    val item = panel.item ?: return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        if (panel.canEdit && !current) {
            ZillitButton(
                text = str(S.drive_restore),
                onClick = { onEvent(DriveEvent.RequestRestoreVersion(item.id, version.id)) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Reload,
            )
        }
        ZillitButton(
            text = str(S.desktop_drive_open_version_read_only),
            onClick = { onEvent(DriveEvent.PreviewVersion(version)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Eye,
        )
        if (panel.canEdit) {
            ZillitButton(
                text = if (version.name.isNotBlank()) str(S.rename) else str(S.name),
                onClick = { onEvent(DriveEvent.StartNameVersion(version)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Edit,
            )
        }
        if (panel.canDownload) {
            ZillitButton(
                text = str(S.download),
                onClick = { onEvent(DriveEvent.DownloadVersion(item, version.id)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Download,
            )
        }
    }
}

// -- naming ---------------------------------------------------------------

@Composable
private fun NameVersionDialog(panel: VersionHistoryState, onEvent: (DriveEvent) -> Unit) {
    val version = panel.naming
    ZillitDialogShell(
        title = if (version?.name?.isNotBlank() == true) {
            str(S.desktop_drive_rename_version)
        } else {
            str(S.desktop_drive_name_this_version)
        },
        subtitle = version?.let { versionDateTime(it.savedAt, LocalDriveNow.current()) },
        visible = version != null,
        onDismiss = { onEvent(DriveEvent.CancelNameVersion) },
        icon = ZillitIcons.Edit,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(DriveEvent.CancelNameVersion) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.save),
                onClick = { onEvent(DriveEvent.SaveVersionName) },
                enabled = !panel.savingName,
                leadingIcon = ZillitIcons.Check,
            )
        },
    ) {
        if (version == null) return@ZillitDialogShell
        ZillitText(
            text = str(S.desktop_drive_version_name_hint),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitTextField(
            value = panel.nameDraft,
            onValueChange = { onEvent(DriveEvent.VersionNameDraft(it.take(NAME_MAX))) },
            label = str(S.name),
            placeholder = str(S.desktop_drive_version_name_placeholder),
            imeAction = ImeAction.Done,
            onImeAction = { onEvent(DriveEvent.SaveVersionName) },
        )
    }
}

// -- states ---------------------------------------------------------------

@Composable
private fun ColumnScope.HistoryFailed(onEvent: (DriveEvent) -> Unit) {
    ZillitText(
        text = str(S.desktop_drive_history_load_failed),
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
    ZillitButton(
        text = str(S.try_again),
        onClick = { onEvent(DriveEvent.RefreshVersionHistory) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Reload,
    )
}

@Composable
private fun ColumnScope.EmptyHistory(namedOnly: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(icon = ZillitIcons.Clock, tint = ZillitTheme.colors.textMuted, size = EMPTY_ICON)
        ZillitText(
            text = if (namedOnly) {
                str(S.desktop_drive_no_named_versions_yet)
            } else {
                str(S.desktop_drive_no_versions_yet)
            },
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        if (namedOnly) {
            ZillitText(
                text = str(S.desktop_drive_name_a_version_hint),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

private val DISCLOSURE_SPACE = 28.dp
private val PERSON_DOT = 8.dp
private val FACE = 22.dp
private val EMPTY_ICON = 32.dp
private const val MAX_FACES = 4
private const val NAME_MAX = 80
