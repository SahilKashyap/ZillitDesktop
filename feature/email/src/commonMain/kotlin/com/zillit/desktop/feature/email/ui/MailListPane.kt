package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuSurface
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.email.domain.EmailFilters
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.ReadStatus

/**
 * The middle column: the search row, the controls row and the rows
 * themselves — the web's `EmailListHeader` over `NewEmailList`.
 */
@Composable
internal fun MailListPane(
    state: EmailUiState,
    onEvent: (EmailEvent) -> Unit,
    loadAvatar: suspend (String) -> ImageBitmap?,
    drag: DragToFolder,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(ZillitTheme.colors.surface)) {
        MailListHeader(state, onEvent)
        Box(Modifier.fillMaxSize()) {
            when {
                state.isLoadingMessages && state.rows.isEmpty() && state.error == null -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { ZillitSpinner() }

                state.error != null && state.rows.isEmpty() -> Centred(state.error)

                state.rows.isEmpty() -> Centred(
                    if (state.searchTerm.isNotBlank() || state.filtersActive) str(S.desktop_email_no_emails_found)
                    else state.selectedFolder?.displayName?.let { str(S.desktop_email_folder_is_empty, it) }
                        ?: str(S.drive_empty_folder_title),
                )

                else -> ListWithNotice(state.error) { MailList(state, onEvent, loadAvatar, drag) }
            }
        }
    }
}

/**
 * The header's two rows: the search pill, then the select-all box with its
 * menu, refresh, and — with rows ticked — delete and move; Empty Trash
 * behind three dots in Trash; the Filters button on the right.
 */
@Composable
private fun MailListHeader(state: EmailUiState, onEvent: (EmailEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = ZillitTheme.spacing.md)
            .padding(top = ZillitTheme.spacing.md, bottom = ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitSearchField(
            value = state.searchTerm,
            onValueChange = { onEvent(EmailEvent.QueryChanged(it)) },
            placeholder = str(S.desktop_email_search_mail),
            modifier = Modifier.fillMaxWidth().testTag(SEARCH_TAG),
        )
        if (state.searchTerm.isNotBlank()) {
            SearchScopeRow(state, onEvent)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            if (state.rows.isNotEmpty()) SelectAllControl(state, onEvent)

            ZillitTooltip(str(S.refresh_text)) {
                ZillitIconButton(
                    icon = ZillitIcons.Reload,
                    contentDescription = str(S.refresh_text),
                    onClick = { onEvent(EmailEvent.Refresh) },
                    enabled = !state.isRefreshing,
                    modifier = Modifier.testTag(REFRESH_TAG),
                )
            }

            if (state.hasSelection) {
                ZillitTooltip(str(S.delete)) {
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = str(S.delete),
                        tint = colors.danger,
                        onClick = { onEvent(EmailEvent.DeleteSelected) },
                        modifier = Modifier.testTag(DELETE_SELECTED_TAG),
                    )
                }
                if (!state.isViewingDrafts) {
                    MoveButton(state.moveTargets, onCreateFolder = { onEvent(EmailEvent.EditFolder()) }) { folder ->
                        onEvent(EmailEvent.MoveSelected(folder))
                    }
                }
            }

            if (state.isViewingTrash && state.rows.isNotEmpty()) TrashMenu(onEvent)

            Spacer(Modifier.weight(1f))

            if (!state.isViewingDrafts) FiltersButton(state.filters, onEvent)
        }
    }
}

/** "in Inbox · Search all folders" under the box while a search is typed. */
@Composable
private fun SearchScopeRow(state: EmailUiState, onEvent: (EmailEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = if (state.searchAllFolders) {
                str(S.desktop_email_searching_every_folder)
            } else {
                state.selectedFolder?.displayName?.let { str(S.desktop_email_searching_in_folder, it) }
                    ?: str(S.desktop_email_searching_in_this_folder)
            },
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )
        ZillitText(
            text = str(
                if (state.searchAllFolders) S.desktop_email_only_this_folder else S.desktop_email_search_all_folders,
            ),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = colors.accentText,
            modifier = Modifier
                .clip(ZillitTheme.shapes.small)
                .clickable { onEvent(EmailEvent.ToggleSearchAllFolders) }
                .padding(horizontal = ZillitTheme.spacing.xs),
        )
    }
}

/** The bordered checkbox-plus-caret the web draws, with its All/None/Read/Unread menu. */
@Composable
private fun SelectAllControl(state: EmailUiState, onEvent: (EmailEvent) -> Unit) {
    val colors = ZillitTheme.colors
    var open by remember { mutableStateOf(false) }
    val total = minOf(state.rows.size, MAX_SELECTION)
    val allTicked = state.selectedIds.isNotEmpty() && state.selectedIds.size >= total

    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .height(CONTROL_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(horizontal = ZillitTheme.spacing.xs)) {
            ZillitCheckbox(
                checked = allTicked,
                onCheckedChange = { checked ->
                    onEvent(EmailEvent.SelectRows(if (checked) SelectionChoice.All else SelectionChoice.None))
                },
                modifier = Modifier.testTag(SELECT_ALL_TAG),
            )
        }
        Box(Modifier.width(1.dp).height(CONTROL_HEIGHT).background(colors.border))
        Box {
            Box(
                modifier = Modifier
                    .clickable { open = true }
                    .height(CONTROL_HEIGHT)
                    .padding(horizontal = ZillitTheme.spacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(
                    ZillitIcons.ChevronDown,
                    contentDescription = str(S.select),
                    tint = colors.textMuted,
                    size = CARET,
                )
            }
            ZillitActionMenu(
                expanded = open,
                onDismissRequest = { open = false },
                entries = listOf(
                    S.all to SelectionChoice.All,
                    S.none to SelectionChoice.None,
                    S.read to SelectionChoice.Read,
                    S.unread_txt to SelectionChoice.Unread,
                ).map { (label, choice) ->
                    ZillitMenuEntry.Action(str(label)) { onEvent(EmailEvent.SelectRows(choice)) }
                },
            )
        }
    }
}

/** Empty Trash, behind the three dots the web keeps it under. */
@Composable
private fun TrashMenu(onEvent: (EmailEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ZillitIconButton(
            icon = ZillitIcons.MoreVertical,
            contentDescription = str(S.more),
            onClick = { open = true },
            modifier = Modifier.testTag(TRASH_MENU_TAG),
        )
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = listOf(
                ZillitMenuEntry.Action(str(S.empty_trash), ZillitIcons.Trash, ZillitMenuTone.Danger) {
                    onEvent(EmailEvent.EmptyTrash)
                },
            ),
        )
    }
}

/**
 * The Move popover — the web's `NewEmailMove`: a folder search, "+ Add new",
 * the folders (never the one you are in, never Drafts), a tick on the one
 * picked and a Move button.
 */
@Composable
internal fun MoveButton(
    targets: List<EmailFolder>,
    onCreateFolder: () -> Unit,
    icon: ImageVector = ZillitIcons.FolderPlus,
    onMove: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ZillitTooltip(str(S.move)) {
            ZillitIconButton(
                icon = icon,
                contentDescription = str(S.move),
                onClick = { open = true },
                modifier = Modifier.testTag(MOVE_TAG),
            )
        }
        ZillitMenuSurface(expanded = open, onDismissRequest = { open = false }) {
            MovePicker(
                targets = targets,
                onCreateFolder = {
                    open = false
                    onCreateFolder()
                },
                onMove = { folder ->
                    open = false
                    onMove(folder)
                },
            )
        }
    }
}

@Composable
internal fun MovePicker(targets: List<EmailFolder>, onCreateFolder: () -> Unit, onMove: (String) -> Unit) {
    val colors = ZillitTheme.colors
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    val shown = targets.filter { it.displayName.contains(query.trim(), ignoreCase = true) }

    Column(
        modifier = Modifier.widthIn(min = MOVE_WIDTH, max = MOVE_WIDTH).padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = str(S.desktop_email_search_folder),
                modifier = Modifier.weight(1f),
            )
            ZillitButton(text = str(S.desktop_email_add_new_folder), size = ButtonSize.Small, onClick = onCreateFolder)
        }
        if (shown.isEmpty()) {
            ZillitText(
                text = str(S.desktop_email_no_folders_to_move_to),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(ZillitTheme.spacing.sm),
            )
        }
        Column {
            shown.forEach { folder ->
                MoveTargetRow(folder, isPicked = picked == folder.name) { picked = folder.name }
            }
        }
        picked?.let { target ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ZillitButton(
                    text = str(S.move),
                    size = ButtonSize.Small,
                    onClick = { onMove(target) },
                    modifier = Modifier.testTag(MOVE_CONFIRM_TAG),
                )
            }
        }
    }
}

/** One folder in the Move popover, ticked once picked. */
@Composable
private fun MoveTargetRow(folder: EmailFolder, isPicked: Boolean, onPick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(if (isPicked) colors.accentSoft else Color.Transparent)
            .clickable(onClick = onPick)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.sm)
            .testTag("move-to-${folder.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(folder.icon(), contentDescription = null, tint = colors.textMuted, size = CARET_LARGE)
        ZillitText(
            text = folder.displayName,
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (isPicked) {
            ZillitIcon(ZillitIcons.Check, contentDescription = null, tint = colors.accentText, size = CARET_LARGE)
        }
    }
}

/** The Filters button with its dot, and the popover behind it. */
@Composable
private fun FiltersButton(filters: EmailFilters, onEvent: (EmailEvent) -> Unit) {
    val colors = ZillitTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(ZillitTheme.shapes.medium)
                .clickable { open = true }
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs)
                .testTag(FILTERS_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            Box {
                ZillitIcon(
                    ZillitIcons.Filter,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    size = CARET_LARGE,
                )
                if (filters.isActive) {
                    Box(
                        Modifier.align(Alignment.TopEnd).size(FILTER_DOT).clip(CircleShape).background(colors.accent),
                    )
                }
            }
            ZillitText(
                text = str(S.asset_filters),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
        ZillitMenuSurface(expanded = open, onDismissRequest = { open = false }) {
            FiltersPopover(
                filters = filters,
                onApply = { applied ->
                    onEvent(EmailEvent.FiltersChanged(applied))
                    open = false
                },
                onClear = {
                    onEvent(EmailEvent.ClearFilters)
                    open = false
                },
            )
        }
    }
}

/**
 * The web's `AdvancedEmailFilters`: a read-status segmented row, the
 * attachments chip, and the four address fields, applied together.
 */
@Composable
internal fun FiltersPopover(filters: EmailFilters, onApply: (EmailFilters) -> Unit, onClear: () -> Unit) {
    val colors = ZillitTheme.colors
    var draft by remember(filters) { mutableStateOf(filters) }

    Column(
        modifier = Modifier.widthIn(min = FILTERS_WIDTH, max = FILTERS_WIDTH).padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = str(S.asset_filters),
                style = ZillitTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (draft.isActive) {
                ZillitText(
                    text = str(S.dd_empty_clear_filters),
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.accentText,
                    modifier = Modifier
                        .clip(ZillitTheme.shapes.small)
                        .clickable(onClick = onClear)
                        .padding(ZillitTheme.spacing.xs),
                )
            }
        }

        FilterHeading(str(S.desktop_email_read_status))
        ReadStatusRow(draft.readStatus) { draft = draft.copy(readStatus = it) }
        AttachmentsChip(draft.hasAttachments) { draft = draft.copy(hasAttachments = !draft.hasAttachments) }

        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

        FilterHeading(str(S.address))
        AddressFilters(draft) { draft = it }

        ZillitButton(
            text = str(S.bs_filter_apply),
            onClick = { onApply(draft) },
            modifier = Modifier.fillMaxWidth().testTag(FILTERS_APPLY_TAG),
        )
    }
}

/** All · Read · Unread, one segment lit. */
@Composable
private fun ReadStatusRow(current: ReadStatus, onPick: (ReadStatus) -> Unit) {
    val colors = ZillitTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ReadStatus.entries.forEach { status ->
            val selected = current == status
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(ZillitTheme.shapes.medium)
                    .background(if (selected) colors.accent else colors.surface)
                    .border(1.dp, if (selected) colors.accent else colors.border, ZillitTheme.shapes.medium)
                    .clickable { onPick(status) }
                    .padding(vertical = ZillitTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val glyph = when (status) {
                    ReadStatus.All -> null
                    ReadStatus.Read -> ZillitIcons.MailOpen
                    ReadStatus.Unread -> ZillitIcons.Mail
                }
                glyph?.let {
                    ZillitIcon(
                        it,
                        contentDescription = null,
                        tint = if (selected) colors.textOnAccent else colors.textSecondary,
                        size = CARET_LARGE,
                    )
                }
                ZillitText(
                    text = when (status) {
                        ReadStatus.All -> str(S.all)
                        ReadStatus.Read -> str(S.read)
                        ReadStatus.Unread -> str(S.unread_txt)
                    },
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (selected) colors.textOnAccent else colors.textSecondary,
                )
            }
        }
    }
}

/** The "Has Attachments" pill, lit while on. */
@Composable
private fun AttachmentsChip(on: Boolean, onToggle: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.pill)
            .background(if (on) colors.accent else colors.surface)
            .border(1.dp, if (on) colors.accent else colors.border, ZillitTheme.shapes.pill)
            .clickable(onClick = onToggle)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs)
            .testTag(FILTER_ATTACHMENTS_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(
            ZillitIcons.Paperclip,
            contentDescription = null,
            tint = if (on) colors.textOnAccent else colors.textSecondary,
            size = CARET_LARGE,
        )
        ZillitText(
            text = str(S.has_attachments_txt),
            style = ZillitTheme.typography.labelSmall,
            color = if (on) colors.textOnAccent else colors.textSecondary,
        )
    }
}

/** From, To, and Cc beside Bcc — free text matched against each address list. */
@Composable
private fun AddressFilters(draft: EmailFilters, onChange: (EmailFilters) -> Unit) {
    ZillitTextField(
        value = draft.from,
        onValueChange = { onChange(draft.copy(from = it)) },
        placeholder = str(S.fromText),
        modifier = Modifier.fillMaxWidth(),
    )
    ZillitTextField(
        value = draft.to,
        onValueChange = { onChange(draft.copy(to = it)) },
        placeholder = str(S.toText),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitTextField(
            value = draft.cc,
            onValueChange = { onChange(draft.copy(cc = it)) },
            placeholder = str(S.dd_label_cc),
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = draft.bcc,
            onValueChange = { onChange(draft.copy(bcc = it)) },
            placeholder = str(S.dd_label_bcc),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FilterHeading(text: String) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = ZillitTheme.colors.textMuted,
    )
}

// -- the rows ----------------------------------------------------------------

@Composable
private fun MailList(
    state: EmailUiState,
    onEvent: (EmailEvent) -> Unit,
    loadAvatar: suspend (String) -> ImageBitmap?,
    drag: DragToFolder,
) {
    // One clock reading per list composition: the labels ("14:05", "4 Aug")
    // only need to be right to the minute, and a ticking clock would
    // recompose every row for nothing.
    val nowMillis = remember { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    val listState = rememberLazyListState()
    FollowNewestMail(listState, itemCount = state.rows.size, folder = state.selectedFolderName)

    ZillitLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(LIST_TAG),
        contentPadding = PaddingValues(vertical = ZillitTheme.spacing.xs),
    ) {
        // Folder + uid + id, not id alone: a search spans folders, and the
        // same message legitimately sits in two of them (the cache's own
        // primary key is composite for this reason — EmailCache.sq). Keying
        // by id alone made the first inbox search throw "key was already
        // used" and take the whole window down.
        items(state.rows, key = { "${it.message.folderName}/${it.message.uid}/${it.id}" }) { row ->
            if (state.isViewingDrafts) {
                DraftRow(
                    row = row,
                    isTicked = row.id in state.selectedIds,
                    nowMillis = nowMillis,
                    onClick = { onEvent(EmailEvent.SelectMessage(row.id)) },
                    onTick = { onEvent(EmailEvent.ToggleSelection(row.id)) },
                )
            } else {
                MessageRow(
                    row = row,
                    isActive = row.id == state.openRowId,
                    isTicked = row.id in state.selectedIds,
                    nowMillis = nowMillis,
                    // Sent rows lead with who the mail went to, as on the web
                    // (`NewEmailCard.jsx` `renderSenderName`).
                    showRecipients = state.isViewingSent,
                    onClick = { onEvent(EmailEvent.SelectMessage(row.id)) },
                    onTick = { onEvent(EmailEvent.ToggleSelection(row.id)) },
                    loadAvatar = loadAvatar,
                    dragModifier = drag.source(row.id),
                )
            }
        }
    }
}

/**
 * New mail lands at the top: a reader who IS at the top stays pinned there,
 * one who scrolled down into last week is never moved, and a folder just
 * chosen starts on its newest row — `FollowLatest`'s rules for a top-edge
 * list, with the folder it anchored on kept in `rememberSaveable`.
 *
 * Not `FollowLatest` itself: its anchor is a plain `remember`. A workspace tab
 * switch disposes this list, the saved [listState] brings the reader back to
 * the row they were on, and a fresh anchor then took that for a first fill
 * and threw them back to the top of the folder.
 */
@Composable
private fun FollowNewestMail(listState: LazyListState, itemCount: Int, folder: String?) {
    // The folder the list last anchored on; null until it has.
    var anchoredTo by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(itemCount, folder) {
        if (itemCount == 0) return@LaunchedEffect
        // The index, not the layout: a restored state knows its row before
        // the list is laid out again.
        val atTop = listState.firstVisibleItemIndex < AT_TOP_SLACK
        if (anchoredTo == folder && !atTop) return@LaunchedEffect
        listState.scrollToItem(0)
        anchoredTo = folder
    }
}

/** The list, with a failed refresh said above it rather than instead of it. */
@Composable
private fun ListWithNotice(error: String?, list: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        error?.let { message ->
            ZillitNotice(
                text = message,
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = ZillitTheme.spacing.md,
                    vertical = ZillitTheme.spacing.xs,
                ),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { list() }
    }
}

@Composable
private fun Centred(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(
                ZillitIcons.Inbox,
                contentDescription = null,
                tint = ZillitTheme.colors.textMuted,
                size = EMPTY_ICON,
            )
            ZillitText(
                text = text,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(ZillitTheme.spacing.lg),
            )
        }
    }
}

internal const val SEARCH_TAG = "email-search"
internal const val LIST_TAG = "email-list"
internal const val REFRESH_TAG = "email-refresh"
internal const val SELECT_ALL_TAG = "email-select-all"
internal const val DELETE_SELECTED_TAG = "email-delete-selected"
internal const val MOVE_TAG = "email-move"
internal const val MOVE_CONFIRM_TAG = "email-move-confirm"
internal const val TRASH_MENU_TAG = "email-trash-menu"
internal const val FILTERS_TAG = "email-filters"
internal const val FILTERS_APPLY_TAG = "email-filters-apply"
internal const val FILTER_ATTACHMENTS_TAG = "email-filter-attachments"

private val CONTROL_HEIGHT = 28.dp
private val CARET = 10.dp
private val CARET_LARGE = 16.dp
private val FILTER_DOT = 6.dp
private val MOVE_WIDTH = 300.dp
private val FILTERS_WIDTH = 320.dp
private val EMPTY_ICON = 40.dp

/** Rows of slack in "at the top" — `FollowLatest`'s own two. */
private const val AT_TOP_SLACK = 2
