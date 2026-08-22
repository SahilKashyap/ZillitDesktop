package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.rememberWheelScroll
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPaneSplitter
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.EmailAttachment
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.ReadFilter
import com.zillit.desktop.feature.email.domain.SearchField
import com.zillit.desktop.feature.email.domain.isRenameable
import com.zillit.desktop.feature.email.domain.EmailSummary

/**
 * The mailbox: folders left, message list right.
 *
 * The web's layout, minus the reading pane — that arrives with the detail slice.
 * A desktop window is wide enough to show folders permanently rather than behind
 * a drawer, which is the main thing the phone client cannot do.
 */
@Composable
fun EmailScreen(
    state: EmailUiState,
    onEvent: (EmailEvent) -> Unit,
    modifier: Modifier = Modifier,
    /** Download progress, which lives outside the mailbox's own state. */
    downloads: Map<String, AttachmentDownload> = emptyMap(),
    /** The folder dialog, likewise — see [FolderEditor]. */
    folderEdit: FolderEdit? = null,
    /** The live search, which spans folders — see [MailSearch]. */
    search: SearchState = SearchState(),
    /** A sender's photo when they are crew on this production; null otherwise. */
    loadAvatar: suspend (String) -> ImageBitmap? = { null },
    /** An image attachment's bytes, decoded, for the inline preview. */
    loadThumbnail: suspend (EmailAttachment, String) -> ImageBitmap? = { _, _ -> null },
    /**
     * Opens the signature manager. A callback because it is a window of its
     * own and windows are the host's business — the sidebar only asks.
     */
    onOpenSignatures: () -> Unit = {},
    /** The mail drawer's other three destinations; see `FolderSidebar`. */
    onOpenContacts: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /**
     * Opens a link clicked in a message body. Null uses the platform's own
     * URI handler; the host passes its guarded browser launcher when it has
     * one, for the same reason as [onOpenSignatures].
     */
    onOpenLink: ((String) -> Unit)? = null,
    /**
     * Anything standing in front of the mailbox — the composers.
     *
     * A slot rather than a parameter of its own, because what stands here owns
     * view models the mailbox has no business holding, and it has to sit inside
     * this Box to stack above the panes rather than beside them.
     */
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    // What share of the mailbox the listing keeps while a message is open.
    // `rememberSaveable`, so a window put away and brought back is still split
    // where its reader left it — WorkspaceHost disposes an inactive window's
    // composition and only saveable state survives that.
    var listFraction by rememberSaveable { mutableStateOf(DEFAULT_LIST_FRACTION) }

    Box(modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        FolderSidebar(state, onEvent, onOpenSignatures, onOpenContacts, onOpenCalendar, onOpenSettings)

        // The reading pane always stands beside the listing, empty-state and
        // all — the web's arrangement (`NewEmailComponent.jsx:314-402`): the
        // listing keeps its share, the pane owns the rest, and the bar
        // between them is draggable.
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val available = maxWidth
            val listWidth = listPaneWidth(available, listFraction)
            val density = LocalDensity.current

            Row(Modifier.fillMaxSize()) {
                MessagePane(
                    state,
                    search,
                    onEvent,
                    loadAvatar,
                    Modifier.width(listWidth).fillMaxHeight().testTag(LIST_PANE_TAG),
                )

                // Measured from where the bar actually is rather than from
                // the stored fraction: dragging past a pane's minimum then
                // has nothing to unwind, so the bar comes straight back
                // when the pointer does.
                ZillitPaneSplitter(
                    onDrag = { delta ->
                        val moved = listWidth + with(density) { delta.toDp() }
                        listFraction = moved / available
                    },
                    modifier = Modifier.testTag(SPLITTER_TAG),
                )

                Box(Modifier.weight(1f).fillMaxHeight().testTag(READING_PANE_TAG)) {
                    ReadingPane(
                        state,
                        downloads,
                        onEvent,
                        loadAvatar = loadAvatar,
                        loadThumbnail = loadThumbnail,
                        onOpenLink = onOpenLink,
                    )
                }
            }
        }
    }

        folderEdit?.let { edit ->
            FolderDialog(edit = edit, onEvent = onEvent)
        }

        state.pendingConfirm?.let { pending ->
            ConfirmDialog(
                pending = pending,
                onConfirm = { onEvent(EmailEvent.ConfirmPending) },
                onDismiss = { onEvent(EmailEvent.DismissConfirm) },
            )
        }

        overlay()
    }
}

/**
 * The listing: its toolbar, the search filters when a search is running, and
 * the rows themselves — or what stands in for them when there are none.
 */
@Composable
private fun MessagePane(
    state: EmailUiState,
    search: SearchState,
    onEvent: (EmailEvent) -> Unit,
    loadAvatar: suspend (String) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        ListToolbar(state, search, onEvent)
        if (search.isActive) SearchFilters(search, onEvent)

        Box(Modifier.fillMaxSize()) {
            when {
                // A failed refresh with nothing to show behind it. With cached
                // mail on screen the error is a strip above the list, not a
                // replacement for it — the rows this machine already has are
                // still true.
                state.error != null && state.messages.isEmpty() -> Centred(state.error)

                state.isLoadingMessages && state.messages.isEmpty() -> Centred("Loading…")

                state.messages.isEmpty() ->
                    Centred("${state.selectedFolder?.displayName ?: "This folder"} is empty.")

                search.isActive && search.results.isEmpty() ->
                    Centred("Nothing matches \"${search.query.term.trim()}\".\n${search.scope}")

                else -> ListWithNotice(state.error) { MessageList(state, search, onEvent, loadAvatar) }
            }
        }
    }
}

@Composable
private fun FolderSidebar(
    state: EmailUiState,
    onEvent: (EmailEvent) -> Unit,
    onOpenSignatures: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = ZillitTheme.colors

    Column(
        modifier = Modifier
            .width(SIDEBAR_WIDTH)
            .fillMaxHeight()
            .background(colors.surfaceSunken)
            .zillitVerticalScroll()
            .padding(vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        // The web's one filled control: "New Email" behind a pencil
        // (`NewEmailSidebar.jsx:329-339`, key `new_email`).
        ZillitButton(
            text = "New Email",
            leadingIcon = ZillitIcons.Edit,
            onClick = { onEvent(EmailEvent.Compose(ComposeMode.New)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.sm)
                .padding(bottom = ZillitTheme.spacing.sm),
        )

        if (state.isLoadingFolders && state.folders.isEmpty()) {
            ZillitText(
                text = "Loading…",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = ZillitTheme.spacing.md),
            )
        }

        // System folders first, then the user's own under their section
        // heading — the web's partition (`NewEmailSidebar.jsx:351-406`).
        val (system, custom) = state.sidebar.partition { it.isSystem }
        system.forEach { folder ->
            SidebarFolder(state, folder, onEvent)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.md)
                .padding(top = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = "FOLDERS",
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            ZillitIconButton(
                icon = ZillitIcons.Add,
                contentDescription = "Create folder",
                onClick = { onEvent(EmailEvent.EditFolder()) },
            )
        }
        custom.forEach { folder ->
            SidebarFolder(state, folder, onEvent)
        }

        // The web's foot is one Settings row behind a top hairline
        // (`NewEmailSidebar.jsx:429-447`); its menu is where signatures and
        // the rest live. The desktop has no nav strip, so Contacts and
        // Calendar ride the same menu.
        Spacer(Modifier.weight(1f))
        SettingsFootRow(onOpenSignatures, onOpenContacts, onOpenCalendar, onOpenSettings)
    }
}

@Composable
private fun SidebarFolder(state: EmailUiState, folder: EmailFolder, onEvent: (EmailEvent) -> Unit) {
    FolderRow(
        folder = folder,
        isActive = folder.name == state.selectedFolder?.name,
        // The ledger keys folders by lower-cased name (iOS's
        // `unreadEmailCountsByFolder`); match the same way.
        badge = state.folderBadges.takeIf { it.isNotEmpty() }
            ?.let { split -> split[folder.name] ?: split[folder.name.lowercase()] ?: 0 },
        onClick = { onEvent(EmailEvent.SelectFolder(folder.name)) },
        onRename = { onEvent(EmailEvent.EditFolder(folder)) },
    )
}

/** The sidebar's foot: one Settings row whose menu holds the rest. */
@Composable
private fun SettingsFootRow(
    onOpenSignatures: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        SidebarActionRow(icon = ZillitIcons.Settings, label = "Settings") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                "Signatures" to onOpenSignatures,
                "Contacts" to onOpenContacts,
                "Calendar" to onOpenCalendar,
                "Email settings" to onOpenSettings,
            ).forEach { (label, action) ->
                DropdownMenuItem(
                    text = { ZillitText(label, style = ZillitTheme.typography.bodyMedium) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
        }
    }
}

/**
 * A quiet action row in the sidebar — "New folder", "Signatures".
 *
 * Under the folder list rather than in the toolbar: these belong with the
 * folders, and every mail client puts them exactly here.
 */
@Composable
private fun SidebarActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.sm)
            .clip(ZillitTheme.shapes.medium)
            .background(if (hovered) colors.surfaceHover else colors.surfaceSunken)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(
            icon = icon,
            contentDescription = null,
            tint = colors.textMuted,
            size = FOLDER_ICON,
        )
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun FolderRow(
    folder: EmailFolder,
    isActive: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    /** The badge ledger's count for this folder; null before it has answered. */
    badge: Int? = null,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.sm)
            .clip(ZillitTheme.shapes.medium)
            .background(
                when {
                    isActive -> colors.accentSoft
                    hovered -> colors.surfaceHover
                    else -> colors.surfaceSunken
                },
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(
            icon = folder.icon(),
            contentDescription = null,
            tint = if (isActive) colors.accentText else colors.textMuted,
            size = FOLDER_ICON,
        )
        ZillitText(
            text = folder.displayName,
            style = ZillitTheme.typography.bodyMedium,
            color = if (isActive) colors.accentText else colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        // Rename appears on hover, and only on folders that can be renamed —
        // the server addresses Inbox and Sent by those exact names, and every
        // client including this one looks for them.
        if (hovered && folder.isRenameable) {
            ZillitIconButton(
                icon = ZillitIcons.Settings,
                contentDescription = "Rename ${folder.displayName}",
                onClick = onRename,
            )
        } else {
            // The badge ledger's count when it has spoken, the folder's own
            // IMAP unread until then — the phones draw the former. The pill
            // is the web's: brand orange, white count, 99+ cap
            // (`NewEmailSidebar.jsx:916-920`).
            val shown = badge ?: folder.unreadCount
            ZillitBadge(count = shown, background = colors.accent)
        }
    }
}

@Composable
private fun ListToolbar(
    state: EmailUiState,
    search: SearchState,
    onEvent: (EmailEvent) -> Unit,
) {
    if (state.isSelecting) {
        SelectionToolbar(state, onEvent)
        return
    }

    // The web stacks the toolbar: the search pill on its own line, the
    // controls under it (`EmailListHeader.jsx:47-201`).
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitSearchField(
            value = search.query.term,
            onValueChange = { onEvent(EmailEvent.QueryChanged(it)) },
            // The web's placeholder is "Search Mail..." — but this box only
            // reaches downloaded mail (no server-side search exists on any
            // client), and the placeholder must not promise otherwise.
            placeholder = "Search downloaded mail",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIconButton(
                icon = ZillitIcons.Reload,
                contentDescription = "Refresh",
                onClick = { onEvent(EmailEvent.Refresh) },
            )
            ZillitText(
                text = state.selectedFolder?.displayName ?: "Mail",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            if (state.isViewingTrash && state.messages.isNotEmpty()) {
                ZillitButton(
                    text = "Empty Trash",
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    onClick = { onEvent(EmailEvent.EmptyTrash) },
                )
            }
        }
    }
}

/**
 * Replaces the toolbar while rows are ticked.
 *
 * Taking the whole bar rather than adding a strip below it: the actions here
 * are destructive, and they should be the only thing that looks clickable while
 * a selection is live.
 */
@Composable
private fun SelectionToolbar(state: EmailUiState, onEvent: (EmailEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.accentSoft)
            .padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = "Clear selection",
            onClick = { onEvent(EmailEvent.ClearSelection) },
        )
        ZillitText(
            text = "${state.selectedIds.size} selected",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.accentText,
            modifier = Modifier.weight(1f),
        )

        MoveMenu(state, onEvent)

        ZillitButton(
            // Named for what it does. "Delete" in Trash means destroy, and a
            // button that says the same thing in both places is how people lose
            // mail they meant to keep.
            text = if (state.isViewingTrash) "Delete forever" else "Trash",
            variant = if (state.isViewingTrash) ButtonVariant.Danger else ButtonVariant.Secondary,
            size = ButtonSize.Small,
            onClick = { onEvent(EmailEvent.DeleteSelected) },
        )
    }
}

/** "Move to ▾", listing every folder except the one we are already in. */
@Composable
private fun MoveMenu(state: EmailUiState, onEvent: (EmailEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }

    Box {
        ZillitButton(
            text = "Move to",
            trailingIcon = ZillitIcons.ChevronDown,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.moveTargets.forEach { folder ->
                DropdownMenuItem(
                    text = { ZillitText(folder.displayName, style = ZillitTheme.typography.bodyMedium) },
                    onClick = {
                        open = false
                        onEvent(EmailEvent.MoveSelected(folder.name))
                    },
                )
            }
        }
    }
}

/**
 * Narrowing a search.
 *
 * Only while one is running: a permanent filter bar over an ordinary folder
 * list is a row of controls that do nothing most of the time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchFilters(search: SearchState, onEvent: (EmailEvent) -> Unit) {
    val query = search.query

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        SearchField.entries.forEach { field ->
            FilterChip(
                label = field.label,
                isOn = field in query.fields,
                onClick = {
                    val fields = if (field in query.fields) query.fields - field else query.fields + field
                    // Never all-off: a search matching no field matches nothing,
                    // which reads as the search being broken.
                    if (fields.isNotEmpty()) {
                        onEvent(EmailEvent.SearchFiltersChanged(query.copy(fields = fields)))
                    }
                },
            )
        }

        FilterChip(
            label = "Unread",
            isOn = query.readFilter == ReadFilter.Unread,
            onClick = {
                val next = if (query.readFilter == ReadFilter.Unread) ReadFilter.Any else ReadFilter.Unread
                onEvent(EmailEvent.SearchFiltersChanged(query.copy(readFilter = next)))
            },
        )
        FilterChip(
            label = "Has files",
            isOn = query.withAttachmentsOnly,
            onClick = {
                onEvent(
                    EmailEvent.SearchFiltersChanged(
                        query.copy(withAttachmentsOnly = !query.withAttachmentsOnly),
                    ),
                )
            },
        )

        ZillitText(
            text = search.scope,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

@Composable
private fun FilterChip(label: String, isOn: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors

    ZillitText(
        text = label,
        style = ZillitTheme.typography.labelSmall,
        color = if (isOn) colors.accentText else colors.textSecondary,
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .background(if (isOn) colors.accentSoft else colors.canvas)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
    )
}

private val SearchField.label: String
    get() = when (this) {
        SearchField.Subject -> "Subject"
        SearchField.From -> "From"
        SearchField.To -> "To"
        SearchField.Body -> "Message"
    }

@Composable
private fun MessageList(
    state: EmailUiState,
    search: SearchState,
    onEvent: (EmailEvent) -> Unit,
    loadAvatar: suspend (String) -> ImageBitmap?,
) {
    // One clock reading per list composition: the labels ("14:05", "4 Aug")
    // only need to be right to the minute, and a ticking clock would
    // recompose every row for nothing.
    val nowMillis = remember { kotlin.time.Clock.System.now().toEpochMilliseconds() }

    val messageState = rememberLazyListState()
    val rows = if (search.isActive) search.results else state.visibleMessages

    // New mail lands at the top; keep a reader who IS at the top pinned
    // there, and never move one who scrolled down into last week.
    com.zillit.desktop.core.designsystem.component.FollowLatest(
        listState = messageState,
        itemCount = rows.size,
        edge = com.zillit.desktop.core.designsystem.component.LatestEdge.Top,
        contentKey = state.selectedFolder,
    )

    LazyColumn(
        state = messageState,
        modifier = Modifier.fillMaxSize().then(rememberWheelScroll(messageState)),
        contentPadding = PaddingValues(vertical = ZillitTheme.spacing.xs),
    ) {

        // Folder + uid + id, not id alone: a search spans folders, and the
        // same message legitimately sits in two of them (the cache's own
        // primary key is composite for this reason — EmailCache.sq). Keying
        // by id alone made the first inbox search throw "key was already
        // used" and take the whole window down.
        items(rows, key = { "${it.folderName}/${it.uid}/${it.id}" }) { message ->
            MessageRow(
                message = message,
                isSelected = message.id == state.selectedMessageId,
                isTicked = message.id in state.selectedIds,
                // Drafts are not IMAP messages, so the mail endpoints have
                // never heard of their ids.
                selectable = !state.isViewingDrafts,
                nowMillis = nowMillis,
                // Sent rows lead with who the mail went to, as on the web
                // (`NewEmailCard.jsx:165-180`).
                showRecipients = state.selectedFolder?.name
                    .equals(EmailFolder.SENT, ignoreCase = true),
                onClick = { onEvent(EmailEvent.SelectMessage(message.id)) },
                onTick = { onEvent(EmailEvent.ToggleSelection(message.id)) },
                loadAvatar = loadAvatar,
            )
        }

        // Reaching the end is the request for more. A folder syncs 50 at a
        // time, so this is how the rest of a large mailbox arrives — and it
        // must not fire during a search, which would ask for more mail from a
        // folder the results may not even be from.
        if (state.hasMore && !search.isActive) {
            item {
                LoadMoreFooter(state.isLoadingMore) { onEvent(EmailEvent.LoadMore) }
            }
        }
    }
}

/**
 * The end of the list, which loads the next batch when it appears.
 *
 * `LaunchedEffect` on first composition rather than a scroll listener: the
 * footer only exists once the user has scrolled to it, so composition *is* the
 * signal, and it stays correct at any window height.
 */
@Composable
private fun LoadMoreFooter(isLoading: Boolean, onLoadMore: () -> Unit) {
    LaunchedEffect(Unit) { onLoadMore() }

    ZillitText(
        text = if (isLoading) "Loading more…" else "",
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
    )
}

/** The web's folder glyphs (`NewEmailSidebar.jsx:756-772`), from our set. */
private fun EmailFolder.icon() = when (name.lowercase()) {
    "inbox" -> ZillitIcons.Mail
    "sent" -> ZillitIcons.Send
    "drafts" -> ZillitIcons.File
    "trash" -> ZillitIcons.Trash
    "junk", "spam" -> ZillitIcons.Warning
    else -> ZillitIcons.Drive
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
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(PAGE_PADDING),
        )
    }
}

/**
 * Where the listing and the message stand, for a mailbox [total] wide.
 *
 * Neither pane may be squeezed to nothing, so the fraction is advisory and
 * these minimums are not: below them the listing stops being readable and
 * a message body starts wrapping every line twice.
 *
 * On a window too narrow to honour both, the listing takes its minimum and the
 * message keeps the remainder — still the larger share of any width worth
 * opening a message in. Narrower again, when half the mailbox is less than the
 * listing's minimum, the split is simply half each: at that point there is no
 * arrangement anyone would call good, and an even one is at least predictable.
 */
internal fun listPaneWidth(total: Dp, fraction: Float): Dp {
    val smallest = MIN_LIST_WIDTH.coerceAtMost(total / 2)
    val largest = (total - MIN_READING_WIDTH).coerceAtLeast(smallest)
    return (total * fraction).coerceIn(smallest, largest)
}

/** The listing's share of the mailbox when a message is open; the rest reads it. */
internal const val DEFAULT_LIST_FRACTION = 0.25f

/** The three parts of the split, for the tests that measure them. */
internal const val LIST_PANE_TAG = "email-list-pane"
internal const val SPLITTER_TAG = "email-splitter"
internal const val READING_PANE_TAG = "email-reading-pane"

internal val MIN_LIST_WIDTH = 220.dp
internal val MIN_READING_WIDTH = 400.dp

private val SIDEBAR_WIDTH = 200.dp
private val PAGE_PADDING = 16.dp
private val FOLDER_ICON = 16.dp
