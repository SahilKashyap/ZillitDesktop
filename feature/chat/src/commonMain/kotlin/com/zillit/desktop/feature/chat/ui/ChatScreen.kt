package com.zillit.desktop.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.chat.data.MessageHit
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.domain.RecentRow
import kotlinx.coroutines.launch
import com.zillit.desktop.feature.chat.domain.admits
import com.zillit.desktop.feature.chat.domain.designationLabel
import com.zillit.desktop.feature.chat.domain.lastEntryDate
import com.zillit.desktop.feature.chat.domain.lastMessageAt
import com.zillit.desktop.feature.chat.domain.recentRows
import com.zillit.desktop.feature.chat.domain.searchCrew
import com.zillit.desktop.feature.chat.domain.searchRecents
import com.zillit.desktop.feature.chat.domain.ChatFilter

/**
 * Chat & Calls: the production's people, in Android's two-tab shape.
 *
 * The crew directory works from data the desktop already syncs. Direct
 * messages ride the CNC socket protocol — rooms, receipts, its own message
 * encryption — which this build does not carry yet, and the Chats tab says
 * exactly that rather than pretending with a dead composer. Calling is
 * likewise not attempted here.
 */
@Composable
@Suppress("LongParameterList")
fun ChatScreen(
    crew: List<CrewContact>,
    /**
     * The signed-in user's id. Kept OUT of the Contacts list — nobody
     * messages themselves — but left IN [crew], which also names senders in
     * group threads; stripping it there would blank the user's own lines.
     */
    selfId: String? = null,
    loadAvatar: suspend (String) -> ImageBitmap?,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel? = null,
    onOpenAttachment: (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> Unit = {},
    player: com.zillit.desktop.core.designsystem.component.AudioPlayer? = null,
    loadAudio: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> ByteArray? =
        { null },
    loadThumbnail: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> ImageBitmap? =
        { null },
    /** Rings the open thread; null hides the call buttons. */
    onCall: ((peer: CrewContact, isGroup: Boolean, video: Boolean, line: CallLine) -> Unit)? = null,
    /** Which lines the call buttons offer. */
    lines: () -> List<CallLine> = { CallLine.DEFAULT },
    /** The call history pane; null hides the Calls tab. */
    callLog: (@Composable () -> Unit)? = null,
    /**
     * Creates a group room (`ChatRepository.createRoom`) — the host passes
     * it through because the screen holds no repository. Null hides the
     * "New group" affordance rather than offering a dead one.
     */
    createRoom: (suspend (name: String, memberIds: List<String>) -> ZillitResult<GroupRoom>)? = null,
    /**
     * The cached-thread message search (`ChatRepository.searchMessages`,
     * QA#12); null keeps the Chats search to conversation names.
     */
    searchMessages: ((String) -> List<MessageHit>)? = null,
    /**
     * `ChatRepository::deleteRoom` — the web's creator-only Delete Group
     * (`InfoSiderGroup.jsx:119,798`); null hides the affordance.
     */
    deleteRoom: (suspend (roomId: String) -> ZillitResult<Unit>)? = null,
    /**
     * One pane at a time instead of directory-beside-thread — what the Chat
     * widget needs, and what the phones do at every size. The thread takes
     * the whole window once something is picked, with a way back to the list.
     */
    compact: Boolean = false,
    /** Opens the Chat widget; null inside the widget itself, and in tests. */
    onOpenWidget: (() -> Unit)? = null,
) {
    val chatState = viewModel?.state?.collectAsState()?.value
    // Opens on Chats, as Android's pager does (ChatAndCall.kt:81-140 — page 0
    // is Chat): the conversations are what the tool is opened for; the
    // directory is one tab away.
    var tab by rememberSaveable { mutableStateOf(DirectoryTab.Chats.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var groupEditorOpen by rememberSaveable { mutableStateOf(false) }

    // A Box rather than the Row alone so the create-group dialog's scrim
    // covers the whole screen, not just the 320dp pane its button lives in.
    // Compact shows the directory until something is picked, then the thread
    // in its place. `peer` is a thread; `selectedId` a contact's card.
    val detailOpen = chatState?.peer != null || selectedId != null
    val showDirectory = !compact || !detailOpen

    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Row(Modifier.fillMaxSize()) {
            if (showDirectory) DirectoryPane(
                crew = crew,
                selfId = selfId,
                tab = tab,
                query = query,
                selectedId = selectedId,
                chatState = chatState,
                loadAvatar = loadAvatar,
                onTab = {
                    tab = it
                    // Opening the tab is the retry — the init-time fetch can
                    // predate the socket connecting.
                    if (it == DirectoryTab.Chats.name) viewModel?.onEvent(ChatEvent.RefreshRecents)
                    // Looking at the log is what reads a missed call.
                    if (it == DirectoryTab.Calls.name) viewModel?.onEvent(ChatEvent.CallsViewed)
                },
                onQuery = { query = it },
                onSelect = { selectedId = it },
                onChatEvent = { event -> viewModel?.onEvent(event) },
                callLog = callLog,
                searchMessages = searchMessages,
                deleteRoom = deleteRoom,
                onNewGroup = ({ groupEditorOpen = true }).takeIf { createRoom != null },
                modifier = if (compact) Modifier.fillMaxWidth() else Modifier.width(LIST_WIDTH),
                compact = compact,
                onOpenWidget = onOpenWidget,
            )

            if (!showDirectory || !compact) {
                DetailSide(
                    compact, chatState, viewModel, crew, selectedId, onOpenAttachment,
                    loadAvatar, loadThumbnail, onCall, lines, player, loadAudio,
                    onBack = {
                        viewModel?.onEvent(ChatEvent.CloseThread)
                        selectedId = null
                    },
                )
            }
        }

        if (createRoom != null) {
            GroupEditorHost(
                // Nobody puts themselves in the member list — the server
                // adds the creator, as Android's picker leaves them out.
                contacts = crew.filterNot { it.userId == selfId },
                createRoom = createRoom,
                visible = groupEditorOpen,
                onDismiss = { groupEditorOpen = false },
                onCreated = {
                    groupEditorOpen = false
                    // The same refresh the tab click rides: `rooms()` is
                    // refetched and the new room takes its row.
                    viewModel?.onEvent(ChatEvent.RefreshRecents)
                },
            )
        }
    }
}

/**
 * The detail half: the thread or card, with the hairline that separates it
 * from the directory when both are on screen, and the way back when they are
 * not.
 */
@Composable
@Suppress("LongParameterList")
private fun RowScope.DetailSide(
    compact: Boolean,
    chatState: ChatUiState?,
    viewModel: ChatViewModel?,
    crew: List<CrewContact>,
    selectedId: String?,
    onOpenAttachment: (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> Unit,
    loadAvatar: suspend (String) -> ImageBitmap?,
    loadThumbnail: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> ImageBitmap?,
    onCall: ((peer: CrewContact, isGroup: Boolean, video: Boolean, line: CallLine) -> Unit)?,
    lines: () -> List<CallLine>,
    player: com.zillit.desktop.core.designsystem.component.AudioPlayer?,
    loadAudio: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> ByteArray?,
    onBack: () -> Unit,
) {
    if (!compact) {
        Box(
            Modifier
                .width(HAIRLINE)
                .fillMaxHeight()
                .background(ZillitTheme.colors.border),
        )
    }

    Column(Modifier.weight(1f).fillMaxHeight()) {
        // Compact has no list beside the thread, so the way back to it has to
        // live here.
        if (compact) {
            CompactBackRow(
                title = chatState?.peer?.fullName
                    ?: crew.firstOrNull { it.userId == selectedId }?.fullName.orEmpty(),
                onBack = onBack,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            DetailPane(
                chatState, viewModel, crew, selectedId, onOpenAttachment,
                loadAvatar, loadThumbnail, onCall, lines, player, loadAudio,
            )
        }
    }
}

/**
 * The pane's own title, and the way out to the widget.
 *
 * Not shown in the widget: its bar already names the tool, and a second
 * heading in a 420px window is a line of chrome where a conversation could be.
 */
@Composable
private fun DirectoryHeading(onOpenWidget: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(text = "Chat & Calls", style = ZillitTheme.typography.titleLarge)
        if (onOpenWidget != null) {
            Spacer(Modifier.weight(1f))
            ZillitIconButton(
                icon = ZillitIcons.Detach,
                contentDescription = "Open the Chat widget",
                onClick = onOpenWidget,
            )
        }
    }
}

/**
 * Compact's way back to the directory: the only affordance that changes
 * between the two layouts, because the wide one never loses sight of the list.
 */
@Composable
private fun CompactBackRow(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIconButton(
            icon = ZillitIcons.ChevronLeft,
            contentDescription = "Back to conversations",
            onClick = onBack,
        )
        ZillitText(
            text = title.ifBlank { "Back" },
            style = ZillitTheme.typography.titleSmall,
            maxLines = 1,
        )
    }
}

/** The right pane: the open thread, a picked contact's card, or the invite. */
@Composable
@Suppress("LongParameterList")
private fun DetailPane(
    chatState: ChatUiState?,
    viewModel: ChatViewModel?,
    crew: List<CrewContact>,
    selectedId: String?,
    onOpenAttachment: (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> Unit,
    loadAvatar: suspend (String) -> ImageBitmap?,
    loadThumbnail: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> ImageBitmap?,
    onCall: ((peer: CrewContact, isGroup: Boolean, video: Boolean, line: CallLine) -> Unit)?,
    lines: () -> List<CallLine>,
    player: com.zillit.desktop.core.designsystem.component.AudioPlayer?,
    loadAudio: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> ByteArray?,
) {
    val selected = crew.firstOrNull { it.userId == selectedId }
    when {
        chatState?.peer != null && viewModel != null ->
            OpenThread(
                chatState, viewModel, crew, onOpenAttachment,
                loadAvatar, loadThumbnail, onCall, lines, player, loadAudio,
            )

        selected != null -> ContactCard(selected, loadAvatar) { contact ->
            viewModel?.onEvent(ChatEvent.OpenThread(contact))
        }

        else -> PaneMessage(
            icon = ZillitIcons.User,
            text = "Pick a contact to see their card.",
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun OpenThread(
    chatState: ChatUiState,
    viewModel: ChatViewModel,
    crew: List<CrewContact>,
    onOpenAttachment: (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> Unit,
    loadAvatar: suspend (String) -> ImageBitmap?,
    loadThumbnail: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> ImageBitmap?,
    onCall: ((peer: CrewContact, isGroup: Boolean, video: Boolean, line: CallLine) -> Unit)?,
    lines: () -> List<CallLine>,
    player: com.zillit.desktop.core.designsystem.component.AudioPlayer? = null,
    loadAudio: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> ByteArray? =
        { null },
) {
    ThreadPane(
        chatState,
        onEvent = { viewModel.onEvent(it) },
        resolveName = { id ->
            crew.firstOrNull { it.userId == id }?.fullName
                ?: chatState.peer?.fullName
        },
        // Tags: strict lookup (an unknown id stays raw text), and a tap
        // opens the person — their thread, whose header is their profile
        // line, the web's `setCurrentChat` treatment.
        resolveMention = { id -> crew.firstOrNull { it.userId == id }?.fullName },
        onOpenUser = { id ->
            crew.firstOrNull { it.userId == id }?.let { tagged ->
                viewModel.onEvent(ChatEvent.OpenThread(tagged))
            }
        },
        onOpenAttachment = onOpenAttachment,
        loadAvatar = loadAvatar,
        loadThumbnail = loadThumbnail,
        player = player,
        loadAudio = loadAudio,
        onCall = onCall?.let { ring ->
            { video, line ->
                chatState.peer?.let { open ->
                    ring(open, chatState.peerIsGroup, video, line)
                }
                Unit
            }
        },
        lines = lines(),
    )
}

/**
 * The strip's tabs, in Android's order — `ChatAndCall.kt:81-140` pages 0
 * Chat, 1 Call, 2 Contacts. Declaration order is display order.
 */
private enum class DirectoryTab(val label: String) {
    Chats("Chats"),
    Calls("Calls"),
    /** The production's people — "Contacts", as the crew asked, not "Crew". */
    Contacts("Contacts"),
}

/** The left pane: title, the two tabs, and whichever list the tab shows. */
@Composable
@Suppress("LongParameterList")
private fun DirectoryPane(
    crew: List<CrewContact>,
    selfId: String?,
    tab: String,
    query: String,
    selectedId: String?,
    chatState: ChatUiState?,
    loadAvatar: suspend (String) -> ImageBitmap?,
    onTab: (String) -> Unit,
    onQuery: (String) -> Unit,
    onSelect: (String) -> Unit,
    onChatEvent: (ChatEvent) -> Unit,
    /**
     * The call history, supplied by the host.
     *
     * A slot rather than a dependency: this module knows nothing about calls,
     * and the app composes the two — the same arrangement as the thread
     * header's call buttons. Null hides the tab entirely, which is what a
     * build with no media engine should do.
     */
    callLog: (@Composable () -> Unit)?,
    /** The Chats search's reach into cached bodies; null keeps it to names. */
    searchMessages: ((String) -> List<MessageHit>)?,
    deleteRoom: (suspend (String) -> ZillitResult<Unit>)? = null,
    /** Opens the create-group dialog; null hides the affordance. */
    onNewGroup: (() -> Unit)?,
    /** Its width: the fixed list column beside a thread, or the whole widget. */
    modifier: Modifier = Modifier.width(LIST_WIDTH),
    /** In a widget the bar above carries the name, so the pane drops its heading. */
    compact: Boolean = false,
    /** Opens the Chat widget, beside the heading. */
    onOpenWidget: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(ZillitTheme.colors.surface)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        if (!compact) DirectoryHeading(onOpenWidget)

        DirectoryTabs(tab, chatState, callLog != null, onTab)

        if (tab == DirectoryTab.Calls.name && callLog != null) {
            callLog()
        } else if (tab == DirectoryTab.Contacts.name) {
            ZillitSearchField(
                value = query,
                onValueChange = onQuery,
                placeholder = "Search name, role, department",
            )
            CrewList(
                crew = crew.filterNot { it.userId == selfId }.searchCrew(query),
                // Follows whichever thread is open, however it was opened —
                // picking someone in Chats and then switching to Contacts should
                // show that person as the one being read, not nobody.
                selectedId = chatState?.peer?.userId ?: selectedId,
                favourites = chatState?.favourites ?: emptySet(),
                onToggleFavourite = { onChatEvent(ChatEvent.ToggleFavourite(it)) },
                loadAvatar = loadAvatar,
                onOpen = { contact ->
                    // Selecting and opening are one act. The card this used to
                    // stop at carried a name, a role and a Message button —
                    // the thread header carries the first two, so the card was
                    // a click that existed to offer one more click.
                    onSelect(contact.userId)
                    onChatEvent(ChatEvent.OpenThread(contact))
                },
            )
        } else if (chatState != null) {
            ChatsTab(
                state = chatState,
                crew = crew,
                selfId = selfId,
                loadAvatar = loadAvatar,
                onEvent = onChatEvent,
                searchMessages = searchMessages,
                onNewGroup = onNewGroup,
                deleteRoom = deleteRoom,
            )
        } else {
            PaneMessage(
                icon = ZillitIcons.Chat,
                text = "Chats need a signed-in production.",
            )
        }
    }
}

/**
 * The Chats tab: its own search box over the listing (QA#6), the "New
 * group" affordance beside it (QA#13), then the filtered recents.
 *
 * The query is local `remember` state, the tools grid's arrangement — the
 * search is this pane's concern and resets with it.
 */
@Composable
@Suppress("LongParameterList")
private fun ChatsTab(
    state: ChatUiState,
    crew: List<CrewContact>,
    selfId: String?,
    loadAvatar: suspend (String) -> ImageBitmap?,
    onEvent: (ChatEvent) -> Unit,
    searchMessages: ((String) -> List<MessageHit>)?,
    onNewGroup: (() -> Unit)?,
    deleteRoom: (suspend (String) -> ZillitResult<Unit>)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search chats",
            modifier = Modifier.weight(1f),
        )
        if (onNewGroup != null) {
            ZillitIconButton(
                icon = ZillitIcons.UserPlus,
                contentDescription = "New group",
                onClick = onNewGroup,
            )
        }
    }
    RecentsList(
        state = state,
        crew = crew,
        selfId = selfId,
        loadAvatar = loadAvatar,
        onEvent = onEvent,
        query = query,
        searchMessages = searchMessages,
        deleteRoom = deleteRoom,
    )
}

/**
 * The Chats listing: everyone with an existing thread, from the server's
 * `user:list`, resolved against the crew for faces and roles. A peer no
 * longer on the production has no card to show and is left out.
 *
 * One flat list, groups and threads interleaved by newest activity — the
 * phones' order (see [recentRows]). It used to pin a Groups section on top,
 * which held a dormant room above every fresh conversation.
 *
 * [query] narrows by display name; with [searchMessages] wired it also
 * reaches the cached message bodies, whose hits follow under a "Messages"
 * header (QA#12).
 */
@Composable
@Suppress("LongParameterList")
private fun RecentsList(
    state: ChatUiState,
    crew: List<CrewContact>,
    selfId: String?,
    loadAvatar: suspend (String) -> ImageBitmap?,
    onEvent: (ChatEvent) -> Unit,
    query: String,
    searchMessages: ((String) -> List<MessageHit>)?,
    deleteRoom: (suspend (String) -> ZillitResult<Unit>)? = null,
) {
    var filter by rememberSaveable { mutableStateOf(ChatFilter.All.name) }
    val chosen = ChatFilter.valueOf(filter)

    FilterChips(chosen) { filter = it.name }

    val rows = recentRows(
        groups = state.groups,
        contacts = state.recents.mapNotNull { id -> crew.firstOrNull { it.userId == id } },
        newest = state.activity,
    ).filter { row -> chosen.admits(row, state.activity, state.unread, state.favourites) }
        .searchRecents(query)

    // Message hits ride the same query; remembered so a recomposition does
    // not re-run the sweep. Name hits and body hits show together — a query
    // matching both is answered with both, the way mail search behaves.
    val hits = remember(query, searchMessages, state.groups, crew) {
        if (query.isBlank() || searchMessages == null) {
            emptyList()
        } else {
            messageHitRows(searchMessages(query), state.groups, crew)
        }
    }

    if (rows.isEmpty() && hits.isEmpty()) {
        PaneMessage(
            icon = ZillitIcons.Chat,
            text = when {
                query.isNotBlank() -> "Nothing matches \"${query.trim()}\"."
                chosen == ChatFilter.All ->
                    "No conversations yet — message someone from the Contacts tab."
                else -> "Nothing under ${chosen.label} right now."
            },
        )
        return
    }

    val nowMillis = remember { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    val roomsState = rememberLazyListState()
    ZillitLazyColumn(
        state = roomsState,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        items(rows, key = RecentRow::id) { row ->
            Column {
                RecentRowCard(row, state, selfId, loadAvatar, onEvent, deleteRoom)
                ZillitDivider()
            }
        }
        if (hits.isNotEmpty()) {
            item(key = "message-hits-header") { MessageHitsHeader() }
            items(hits) { hit ->
                Column {
                    MessageHitRowItem(hit, nowMillis, onEvent)
                    ZillitDivider()
                }
            }
        }
    }
}

/**
 * A group row wearing the creator's Delete — the web's Delete Group from the
 * group info sider (`InfoSiderGroup.jsx:119,798`): creator only, a
 * confirmation first ("Are you sure you want to delete this group ?"), and
 * the listing re-read once the server has taken it.
 */
@Composable
private fun GroupRowWithDelete(
    row: RecentRow.Group,
    state: ChatUiState,
    selfId: String?,
    onEvent: (ChatEvent) -> Unit,
    deleteRoom: (suspend (String) -> ZillitResult<Unit>)?,
) {
    var confirming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val mine = deleteRoom != null && selfId != null && row.room.ownedBy == selfId

    CrewRow(
        contact = CrewContact(userId = row.room.id, fullName = row.room.name),
        isSelected = false,
        loadAvatar = { null },
        onClick = { onEvent(ChatEvent.OpenGroup(row.room)) },
        subtitle = (state.activity[row.room.id] ?: row.room.sortingActivity.takeIf { it > 0L })
            ?.let { "${"last_message_at".localised()}: ${lastMessageAt(it)}" },
        badge = state.unread[row.room.id] ?: 0,
        trailing = if (mine) {
            {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Delete ${row.room.name}",
                    enabled = !deleting,
                    onClick = { confirming = true },
                )
            }
        } else {
            null
        },
    )

    DeleteGroupDialog(
        roomName = row.room.name,
        visible = confirming,
        deleting = deleting,
        refusal = refusal,
        onDismiss = { confirming = false },
        onConfirm = {
            deleting = true
            scope.launch {
                when (val result = deleteRoom?.invoke(row.room.id)) {
                    is ZillitResult.Success -> {
                        confirming = false
                        onEvent(ChatEvent.RefreshRecents)
                    }

                    is ZillitResult.Failure -> refusal = result.error.localised()
                    null -> Unit
                }
                deleting = false
            }
        },
    )
}

/** The web's confirmation before a group goes (`en.js:5666`), word for word. */
@Composable
@Suppress("LongParameterList") // One dialog's display state, one each.
private fun DeleteGroupDialog(
    roomName: String,
    visible: Boolean,
    deleting: Boolean,
    refusal: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ZillitDialogShell(
        title = roomName,
        subtitle = "Are you sure you want to delete this group ?",
        icon = ZillitIcons.Trash,
        visible = visible,
        onDismiss = onDismiss,
        actions = {
            ZillitButton(
                text = "Cancel",
                variant = ButtonVariant.Tertiary,
                onClick = onDismiss,
            )
            ZillitButton(
                text = "Delete",
                variant = ButtonVariant.Danger,
                loading = deleting,
                onClick = onConfirm,
            )
        },
    ) {
        refusal?.let { message ->
            ZillitText(
                text = message,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
            )
        }
    }
}

/** One conversation row, group- or person-flavoured. */
@Composable
private fun RecentRowCard(
    row: RecentRow,
    state: ChatUiState,
    selfId: String?,
    loadAvatar: suspend (String) -> ImageBitmap?,
    onEvent: (ChatEvent) -> Unit,
    deleteRoom: (suspend (String) -> ZillitResult<Unit>)? = null,
) {
    when (row) {
        // The web's group row: name over the stamp line, badge, no
        // star and no designation slot (`GroupCard.jsx:300-333`).
        is RecentRow.Group -> GroupRowWithDelete(row, state, selfId, onEvent, deleteRoom)

        // The web's user row: name, designation, "Last Entry"
        // (`UserCard.jsx:317-362`) — no message preview.
        is RecentRow.Direct -> CrewRow(
            contact = row.contact,
            isSelected = false,
            loadAvatar = loadAvatar,
            onClick = { onEvent(ChatEvent.OpenThread(row.contact)) },
            meta = row.contact.lastEntryLine(),
            badge = state.unread[row.contact.userId] ?: 0,
            isFavourite = row.contact.userId in state.favourites,
            onToggleFavourite = { onEvent(ChatEvent.ToggleFavourite(row.contact.userId)) },
        )
    }
}

/**
 * The Contacts tab: everyone else on the production, one flat roll.
 *
 * Alphabetical with no department sections — the web's `ContactsList`, whose
 * rows are the same card the chat list uses with badges suppressed
 * (`heideBadges`) and the star kept. A row opens that person's conversation.
 */
@Composable
@Suppress("LongParameterList")
private fun CrewList(
    crew: List<CrewContact>,
    selectedId: String?,
    favourites: Set<String>,
    loadAvatar: suspend (String) -> ImageBitmap?,
    onOpen: (CrewContact) -> Unit,
    onToggleFavourite: (String) -> Unit,
) {
    val crewState = rememberLazyListState()
    ZillitLazyColumn(
        state = crewState,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        items(crew.sortedBy { it.fullName.lowercase() }, key = CrewContact::userId) { contact ->
            Column {
                CrewRow(
                    contact = contact,
                    isSelected = contact.userId == selectedId,
                    loadAvatar = loadAvatar,
                    onClick = { onOpen(contact) },
                    meta = contact.lastEntryLine(),
                    isFavourite = contact.userId in favourites,
                    onToggleFavourite = { onToggleFavourite(contact.userId) },
                )
                ZillitDivider()
            }
        }
    }
}

/**
 * One listing row, the web's `UserCard` shape: name with an inline
 * "- (Admin)" suffix, the designation (or an explicit subtitle) under it, a
 * meta line under that, and badge + star at the trailing edge.
 */
@Composable
@Suppress("LongParameterList") // One row's worth of display state.
private fun CrewRow(
    contact: CrewContact,
    isSelected: Boolean,
    loadAvatar: suspend (String) -> ImageBitmap?,
    onClick: () -> Unit,
    subtitle: String? = null,
    meta: String? = null,
    badge: Int = 0,
    isFavourite: Boolean? = null,
    onToggleFavourite: () -> Unit = {},
    /** An extra control at the row's end — the group row's Delete. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val unread = badge > 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(
                when {
                    isSelected -> ZillitTheme.colors.accentSoft
                    // Lights under the cursor like every other list in the
                    // app; a row that ignores the pointer reads as inert.
                    hovered -> ZillitTheme.colors.surfaceHover
                    else -> ZillitTheme.colors.surface
                },
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(
            name = contact.fullName,
            image = rememberAvatar(contact.userId, loadAvatar),
        )
        CrewIdentity(contact, unread, subtitle, meta, Modifier.weight(1f))
        RowTrailing(badge, isFavourite, onToggleFavourite)
        trailing?.invoke()
    }
}

/** The name line, then whatever the row has to say under it. */
@Composable
private fun CrewIdentity(
    contact: CrewContact,
    unread: Boolean,
    subtitle: String?,
    meta: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = contact.fullName,
                style = if (unread) {
                    ZillitTheme.typography.titleSmall
                } else {
                    ZillitTheme.typography.bodyMedium
                },
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Inline, the web's way — not a trailing tag.
            if (contact.isAdmin) {
                ZillitText(
                    text = " - (Admin)",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        // `designation` is a translation key off `project/users`
        // (`driver_label`); an explicit subtitle is already display text.
        // The generic member designation is hidden, as on the phones.
        (subtitle ?: contact.designationLabel()?.localised())?.takeIf { it.isNotBlank() }?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.labelSmall,
                color = if (unread) {
                    ZillitTheme.colors.textSecondary
                } else {
                    ZillitTheme.colors.textMuted
                },
                maxLines = 1,
            )
        }
        meta?.takeIf { it.isNotBlank() }?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/** The row's right edge: how much is waiting, and the star. */
@Composable
private fun RowTrailing(
    badge: Int,
    isFavourite: Boolean?,
    onToggleFavourite: () -> Unit,
) {
    if (badge > 0) {
        // The C&C listing wears the brand orange, not the alert red —
        // the web's chat badges.
        ZillitBadge(count = badge, background = ZillitTheme.colors.accent)
    }
    if (isFavourite != null) {
        ZillitIconButton(
            icon = if (isFavourite) ZillitIcons.StarFilled else ZillitIcons.StarOutline,
            contentDescription = if (isFavourite) "Unstar" else "Star",
            onClick = onToggleFavourite,
            tint = if (isFavourite) ZillitTheme.colors.warning else ZillitTheme.colors.textMuted,
            size = STAR_SIZE,
        )
    }
}

/** The user rows' third line — `last_entry: Aug 19, 2026`, the web's. */
private fun CrewContact.lastEntryLine(): String? =
    lastActiveMillis?.takeIf { it > 0 }
        ?.let { "${"last_entry".localised()}: ${lastEntryDate(it)}" }

private val STAR_SIZE = 22.dp

/**
 * The three underline tabs. Chats and Calls each wear their own count — the
 * badge service's split, the same numbers the phones show — so "anything
 * waiting?" is answered without opening either. The local per-row unread
 * stands in for Chats until the split has answered.
 */
@Composable
private fun DirectoryTabs(
    tab: String,
    chatState: ChatUiState?,
    hasCallLog: Boolean,
    onTab: (String) -> Unit,
) {
    ZillitTabStrip(
        tabs = DirectoryTab.entries
            .filter { it != DirectoryTab.Calls || hasCallLog }
            .map { entry ->
                ZillitTab(
                    id = entry.name,
                    label = entry.label,
                    count = when (entry) {
                        // The live conversations' own sum — see `chatsBadge`.
                        DirectoryTab.Chats -> chatState?.chatsBadge ?: 0
                        DirectoryTab.Calls -> chatState?.callsBadge ?: 0
                        DirectoryTab.Contacts -> 0
                    },
                )
            },
        activeId = tab,
        onSelect = onTab,
    )
}

/** The listing's five chips, one always lit; they wrap, never crush. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FilterChips(chosen: ChatFilter, onPick: (ChatFilter) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ChatFilter.entries.forEach { entry ->
            ZillitChoiceChip(
                label = entry.label,
                selected = chosen == entry,
                onClick = { onPick(entry) },
            )
        }
    }
}

/** The right pane: one person, large — the paper crew card, on glass. */
@Composable
private fun ContactCard(
    contact: CrewContact,
    loadAvatar: suspend (String) -> ImageBitmap?,
    onMessage: (CrewContact) -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = CARD_MAX_WIDTH)
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.large),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The person's own hue across the card's head — the same rule their
        // avatar follows, so the card is findably *theirs* before the name
        // is read. See avatarHue.
        Box(
            Modifier
                .fillMaxWidth()
                .height(CARD_EDGE)
                .background(
                    com.zillit.desktop.core.designsystem.component.avatarHue(contact.fullName),
                ),
        )
        Column(
            modifier = Modifier.padding(ZillitTheme.spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            CardBody(contact, loadAvatar, onMessage)
        }
    }
}

/** Everything under the hue strip: identity, contact line, the one action. */
@Composable
private fun CardBody(
    contact: CrewContact,
    loadAvatar: suspend (String) -> ImageBitmap?,
    onMessage: (CrewContact) -> Unit,
) {
    CardIdentity(contact, loadAvatar)
    contact.email?.takeIf { it.isNotBlank() }?.let { email ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitIcon(
                icon = ZillitIcons.Mail,
                contentDescription = null,
                tint = ZillitTheme.colors.textMuted,
                size = MAIL_ICON,
            )
            ZillitText(
                text = email,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
    ZillitButton(
        text = "Message",
        onClick = { onMessage(contact) },
    )
}

/** Face, name, badge and role — the card's identity block. */
@Composable
private fun CardIdentity(contact: CrewContact, loadAvatar: suspend (String) -> ImageBitmap?) {
    ZillitAvatar(
        name = contact.fullName,
        image = rememberAvatar(contact.userId, loadAvatar),
        size = CARD_AVATAR,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(text = contact.fullName, style = ZillitTheme.typography.titleLarge)
        if (contact.isAdmin) ZillitTag("Admin", tone = TagTone.Accent)
    }
    val role = listOfNotNull(
        contact.department?.takeIf { it.isNotBlank() },
        contact.designationLabel(),
    ).joinToString(" · ")
    if (role.isNotBlank()) {
        ZillitText(
            text = role,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun rememberAvatar(
    userId: String,
    load: suspend (String) -> ImageBitmap?,
): ImageBitmap? = produceState<ImageBitmap?>(initialValue = null, userId) {
    value = load(userId)
}.value

@Composable
private fun PaneMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Column(
        modifier = Modifier.widthIn(max = MESSAGE_MAX_WIDTH).padding(ZillitTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(ICON_DISC)
                .clip(CircleShape)
                .background(ZillitTheme.colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                icon = icon,
                contentDescription = null,
                tint = ZillitTheme.colors.accentText,
                size = ICON_SIZE,
            )
        }
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

private val LIST_WIDTH = 320.dp
private val HAIRLINE = 1.dp
private val CARD_MAX_WIDTH = 380.dp
private val CARD_EDGE = 6.dp
private val CARD_AVATAR = 72.dp
private val MAIL_ICON = 14.dp
private val MESSAGE_MAX_WIDTH = 360.dp
private val ICON_DISC = 64.dp
private val ICON_SIZE = 28.dp
