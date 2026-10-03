package com.zillit.desktop.feature.chat.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.LocalAvatarLoader
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.chat.data.MessageHit
import com.zillit.desktop.feature.chat.domain.ChatFilter
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.domain.RecentRow
import com.zillit.desktop.feature.chat.domain.admits
import com.zillit.desktop.feature.chat.domain.chatTimeLabel
import com.zillit.desktop.feature.chat.domain.designationLabel
import com.zillit.desktop.feature.chat.domain.lastEntryDate
import com.zillit.desktop.feature.chat.domain.recentRows
import com.zillit.desktop.feature.chat.domain.searchCrew
import com.zillit.desktop.feature.chat.domain.searchRecents
import kotlinx.coroutines.launch

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
    /**
     * The call history — the list and the pane beside it — built by the host
     * when the Calls tab opens; null hides the tab.
     */
    callPanes: (@Composable () -> CallsPanes)? = null,
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
    // Built only while the tab is open, as the old slot composed only then:
    // the history is fetched when it is looked at, not when chat opens.
    val calls = if (tab == DirectoryTab.Calls.name) callPanes?.invoke() else null

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
                calls = calls,
                hasCalls = callPanes != null,
                searchMessages = searchMessages,
                deleteRoom = deleteRoom,
                onNewGroup = ({ groupEditorOpen = true }).takeIf { createRoom != null },
                modifier = if (compact) Modifier.fillMaxWidth() else Modifier.width(LIST_WIDTH),
                compact = compact,
                onOpenWidget = onOpenWidget,
            )

            if (!showDirectory || !compact) {
                DetailSide(
                    compact, chatState, viewModel, crew, selfId, selectedId, onOpenAttachment,
                    loadAvatar, loadThumbnail, onCall, lines, player, loadAudio,
                    // The Calls tab owns the pane beside it, as WhatsApp's does;
                    // an open thread is still there on the way back to Chats.
                    callsSide = calls?.takeIf { !compact }?.let { panes ->
                        { panes.Side(onStartCall = { tab = DirectoryTab.Contacts.name }) }
                    },
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
    selfId: String?,
    selectedId: String?,
    onOpenAttachment: (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> Unit,
    loadAvatar: suspend (String) -> ImageBitmap?,
    loadThumbnail: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> ImageBitmap?,
    onCall: ((peer: CrewContact, isGroup: Boolean, video: Boolean, line: CallLine) -> Unit)?,
    lines: () -> List<CallLine>,
    player: com.zillit.desktop.core.designsystem.component.AudioPlayer?,
    loadAudio: suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> ByteArray?,
    callsSide: (@Composable () -> Unit)?,
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
        // A thread paints its own paper; with nothing open the pane is the
        // panel grey WhatsApp leaves there, not the window's canvas.
        Box(
            Modifier.weight(1f).fillMaxWidth().background(ZillitTheme.colors.chatPanel),
            contentAlignment = Alignment.Center,
        ) {
            if (callsSide != null) {
                callsSide()
            } else {
                DetailPane(
                    chatState, viewModel, crew, selfId, selectedId, onOpenAttachment,
                    loadAvatar, loadThumbnail, onCall, lines, player, loadAudio,
                )
            }
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
private fun DirectoryHeading(onOpenWidget: (() -> Unit)?, onNewGroup: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = str(S.desktop_chat_calls),
            style = ZillitTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        // The heading's glyphs, WhatsApp's: new chat first, then the way out.
        if (onNewGroup != null) {
            ZillitIconButton(
                icon = ZillitIcons.UserPlus,
                contentDescription = str(S.desktop_new_group),
                onClick = onNewGroup,
                tint = ZillitTheme.colors.textSecondary,
            )
        }
        if (onOpenWidget != null) {
            ZillitIconButton(
                icon = ZillitIcons.Detach,
                contentDescription = str(S.desktop_open_chat_widget),
                onClick = onOpenWidget,
                tint = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

/** The padded block over the rows: the heading (not in the widget) and the tabs. */
@Composable
@Suppress("LongParameterList") // The block's display state and its three verbs.
private fun DirectoryTop(
    heading: Boolean,
    tab: String,
    chatState: ChatUiState?,
    hasCallLog: Boolean,
    onTab: (String) -> Unit,
    onOpenWidget: (() -> Unit)?,
    onNewGroup: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.padding(
            start = ZillitTheme.spacing.md,
            end = ZillitTheme.spacing.md,
            top = ZillitTheme.spacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        if (heading) DirectoryHeading(onOpenWidget = onOpenWidget, onNewGroup = onNewGroup)
        DirectoryTabs(tab, chatState, hasCallLog, onTab)
    }
}

/**
 * The listing's search, WhatsApp's: a filled grey pill with no resting
 * edge, inset from the pane's sides like the block above it.
 */
@Composable
private fun ListingSearch(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    ZillitSearchField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        containerColor = ZillitTheme.colors.chatPanel,
        bordered = false,
        modifier = modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.md),
    )
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
            contentDescription = str(S.desktop_back_to_conversations),
            onClick = onBack,
        )
        ZillitText(
            text = title.ifBlank { str(S.back) },
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
    selfId: String?,
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
                chatState, viewModel, crew, selfId, onOpenAttachment,
                loadAvatar, loadThumbnail, onCall, lines, player, loadAudio,
            )

        selected != null -> ContactCard(selected, loadAvatar) { contact ->
            viewModel?.onEvent(ChatEvent.OpenThread(contact))
        }

        else -> PaneMessage(
            icon = ZillitIcons.User,
            text = str(S.desktop_chat_pick_a_contact),
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun OpenThread(
    chatState: ChatUiState,
    viewModel: ChatViewModel,
    crew: List<CrewContact>,
    selfId: String?,
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
        // The readers panel names and captions its rows from the crew.
        resolveContact = { id -> crew.firstOrNull { it.userId == id } },
        // Forward's people: everyone still here, minus oneself — the same
        // cut the Contacts tab makes.
        forwardPeople = crew.filterNot { it.userId == selfId || it.hasLeft },
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
private enum class DirectoryTab(private val labelKey: String) {
    Chats(S.chats_text),
    Calls(S.desktop_calls),
    /** The production's people — "Contacts", as the crew asked, not "Crew". */
    Contacts(S.contacts),
    ;

    val label: String get() = str(labelKey)
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
     * The call history, supplied by the host while the Calls tab is open —
     * see [CallsPanes]. [hasCalls] false hides the tab entirely, which is
     * what a build with no media engine should do.
     */
    calls: CallsPanes?,
    hasCalls: Boolean,
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
    // WhatsApp's listing: the heading, tabs, search and chips sit in a
    // padded block; the rows under them run edge to edge, each closed by a
    // hairline that starts where the text does.
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(ZillitTheme.colors.surface),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        DirectoryTop(
            heading = !compact,
            tab = tab,
            chatState = chatState,
            hasCallLog = hasCalls,
            onTab = onTab,
            onOpenWidget = onOpenWidget,
            onNewGroup = onNewGroup.takeIf { tab == DirectoryTab.Chats.name && chatState != null },
        )

        if (tab == DirectoryTab.Calls.name && calls != null) {
            calls.List(inlineDetail = !compact)
        } else if (tab == DirectoryTab.Contacts.name) {
            ListingSearch(
                value = query,
                onValueChange = onQuery,
                placeholder = str(S.desktop_chat_search_name_role_department),
            )
            CrewList(
                // Someone who left or was removed is not a contact any more —
                // Android's Contacts tab drops `left` and `removed`
                // (`MembersVM.kt:473`). Their thread stays in the Chats list,
                // captioned, because the history is still theirs to read.
                crew = crew.filterNot { it.userId == selfId || it.hasLeft }.searchCrew(query),
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
                // In the heading beside the title, WhatsApp's new-chat glyph —
                // except in the widget, which has no heading.
                onNewGroup = onNewGroup.takeIf { compact },
                deleteRoom = deleteRoom,
            )
        } else {
            PaneMessage(
                icon = ZillitIcons.Chat,
                text = str(S.desktop_chat_need_signed_in_project),
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
        modifier = Modifier.padding(end = if (onNewGroup != null) ZillitTheme.spacing.md else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ListingSearch(
            value = query,
            onValueChange = { query = it },
            placeholder = str(S.desktop_search_chats),
            modifier = Modifier.weight(1f),
        )
        if (onNewGroup != null) {
            ZillitIconButton(
                icon = ZillitIcons.UserPlus,
                contentDescription = str(S.desktop_new_group),
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
                query.isNotBlank() -> str(S.desktop_nothing_matches_query, query.trim())
                chosen == ChatFilter.All -> str(S.desktop_chat_no_conversations_yet)
                else -> str(S.desktop_chat_nothing_under_filter, chosen.label)
            },
        )
        return
    }

    val nowMillis = remember { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    val roomsState = rememberLazyListState()
    // Rows touch, each closed by its own hairline (see CrewRow), as
    // WhatsApp's do. Rows animate to their new place when activity reorders
    // them, so a conversation that just moved up is seen moving, not swapped.
    ZillitLazyColumn(state = roomsState) {
        items(rows, key = RecentRow::id) { row ->
            Box(Modifier.animateItem()) {
                RecentRowCard(row, state, selfId, loadAvatar, onEvent, deleteRoom, nowMillis, crew)
            }
        }
        if (hits.isNotEmpty()) {
            item(key = "message-hits-header") { MessageHitsHeader() }
            items(hits) { hit ->
                Column(Modifier.padding(horizontal = ZillitTheme.spacing.md)) {
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
@Suppress("LongParameterList") // One row's data, its verbs and the clock.
private fun GroupRowWithDelete(
    row: RecentRow.Group,
    state: ChatUiState,
    selfId: String?,
    onEvent: (ChatEvent) -> Unit,
    deleteRoom: (suspend (String) -> ZillitResult<Unit>)?,
    nowMillis: Long,
    nameFor: (String) -> String? = { null },
) {
    var confirming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val mine = deleteRoom != null && selfId != null && row.room.ownedBy == selfId

    CrewRow(
        contact = CrewContact(userId = row.room.id, fullName = row.room.displayName),
        isSelected = state.peerIsGroup && state.peer?.userId == row.room.id,
        loadAvatar = { null },
        onClick = { onEvent(ChatEvent.OpenGroup(row.room)) },
        // The newest line, led by who wrote it — "You:" or a first name —
        // the way a room's row reads on the phones; "Group" until one is cached.
        subtitle = state.previews[row.room.id]?.line(selfId, inRoom = true, nameFor = nameFor) ?: str(S.group),
        // The room's newest word, as a clock or a date at the row's end — the
        // mail list's column, where "Last Message At: Sep 15, 2026 at 06:…"
        // used to run under the name and ellipsise its own time away.
        stamp = (state.activity[row.room.id] ?: row.room.sortingActivity.takeIf { it > 0L })
            ?.let { chatTimeLabel(it, nowMillis) },
        badge = state.unread[row.room.id] ?: 0,
        trailing = if (mine) {
            {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = str(S.desktop_delete_named, row.room.displayName),
                    enabled = !deleting,
                    onClick = { confirming = true },
                )
            }
        } else {
            null
        },
    )

    DeleteGroupDialog(
        roomName = row.room.displayName,
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
        subtitle = str(S.are_you_sure_you_want_to_delete_this_group),
        icon = ZillitIcons.Trash,
        visible = visible,
        onDismiss = onDismiss,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                variant = ButtonVariant.Tertiary,
                onClick = onDismiss,
            )
            ZillitButton(
                text = str(S.delete),
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

/**
 * One conversation row, group- or person-flavoured.
 *
 * The open conversation is lit, whichever tab opened it: the list is the
 * map of where the reader is, and a map with no "you are here" made every
 * row look equally closed.
 */
@Composable
@Suppress("LongParameterList") // One row's data, its verbs and the clock.
private fun RecentRowCard(
    row: RecentRow,
    state: ChatUiState,
    selfId: String?,
    loadAvatar: suspend (String) -> ImageBitmap?,
    onEvent: (ChatEvent) -> Unit,
    deleteRoom: (suspend (String) -> ZillitResult<Unit>)? = null,
    nowMillis: Long = 0L,
    /** Names a room line's writer for its preview. */
    crew: List<CrewContact> = emptyList(),
) {
    when (row) {
        // The web's group row: name over the stamp line, badge, no
        // star and no designation slot (`GroupCard.jsx:300-333`).
        is RecentRow.Group -> GroupRowWithDelete(
            row, state, selfId, onEvent, deleteRoom, nowMillis,
            nameFor = { id -> crew.firstOrNull { it.userId == id }?.fullName },
        )

        // Name over the newest line — the phones' listing row — with the
        // designation standing in until a line is cached; the stamp is the
        // conversation's newest activity, the same clock the list sorts by,
        // at the row's end rather than as the web's "Last Entry:" line.
        is RecentRow.Direct -> CrewRow(
            contact = row.contact,
            isSelected = !state.peerIsGroup && state.peer?.userId == row.contact.userId,
            loadAvatar = loadAvatar,
            onClick = { onEvent(ChatEvent.OpenThread(row.contact)) },
            subtitle = state.previews[row.contact.userId]?.line(selfId, inRoom = false),
            stamp = (state.activity[row.contact.userId] ?: row.contact.lastActiveMillis)
                ?.takeIf { it > 0L }
                ?.let { chatTimeLabel(it, nowMillis) },
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
    ZillitLazyColumn(state = crewState) {
        // One row per person: the key is the user id, and a duplicate one throws.
        val people = crew.distinctBy(CrewContact::userId).sortedBy { it.fullName.lowercase() }
        items(people, key = CrewContact::userId) { contact ->
            CrewRow(
                contact = contact,
                isSelected = contact.userId == selectedId,
                loadAvatar = loadAvatar,
                onClick = { onOpen(contact) },
                meta = contact.lastEntryLine(),
                isFavourite = contact.userId in favourites,
                onToggleFavourite = { onToggleFavourite(contact.userId) },
            )
        }
    }
}

/**
 * One listing row, WhatsApp's: the face, then the name with the stamp at the
 * far end, and under it the preview (or role) with the star and the count at
 * its end. Rows touch; a hairline under the text, not under the face, closes
 * each one. The web's `UserCard` lines — the "(Admin)" suffix, Disconnected,
 * the Contacts tab's last entry — stack under those two.
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
    /** A clock or a date at the name line's end — when the row last moved. */
    stamp: String? = null,
    badge: Int = 0,
    isFavourite: Boolean? = null,
    onToggleFavourite: () -> Unit = {},
    /** An extra control at the row's end — the group row's Delete. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Eased rather than switched: the highlight follows the cursor down the
    // list instead of blinking from row to row.
    val background by animateColorAsState(
        when {
            isSelected -> ZillitTheme.colors.chatRowSelected
            // Lights under the cursor like every other list in the
            // app; a row that ignores the pointer reads as inert.
            hovered -> ZillitTheme.colors.chatRowHover
            else -> ZillitTheme.colors.surface
        },
        animationSpec = tween(ROW_TINT_MILLIS),
        label = "rowBackground",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(start = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitAvatar(
            name = contact.fullName,
            image = rememberChatFace(contact.userId, loadAvatar),
            size = ROW_AVATAR,
        )
        Box(Modifier.weight(1f)) {
            CrewIdentity(
                contact, badge > 0, subtitle, meta, stamp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ROW_HEIGHT)
                    .padding(end = ZillitTheme.spacing.md, top = ROW_PAD_V, bottom = ROW_PAD_V),
            ) {
                RowTrailing(badge, isFavourite, onToggleFavourite, revealed = hovered)
                // The group's Delete waits for the cursor, as the star does.
                trailing?.let { control -> Box(Modifier.alpha(if (hovered) 1f else 0f)) { control() } }
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(HAIRLINE)
                    .background(ZillitTheme.colors.divider),
            )
        }
    }
}

/** The name line, then the preview line with the row's controls at its end. */
@Composable
@Suppress("LongParameterList") // The row's lines, one each.
private fun CrewIdentity(
    contact: CrewContact,
    unread: Boolean,
    subtitle: String?,
    meta: String?,
    stamp: String?,
    modifier: Modifier = Modifier,
    controls: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs, Alignment.CenterVertically),
    ) {
        NameLine(contact, unread, stamp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            // `designation` is a translation key off `project/users`
            // (`driver_label`); an explicit subtitle is already display text.
            // The generic member designation is hidden, as on the phones.
            ZillitText(
                text = (subtitle ?: contact.designationLabel()?.localised()).orEmpty(),
                style = ZillitTheme.typography.bodySmall,
                color = if (unread) ZillitTheme.colors.textPrimary else ZillitTheme.colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            controls()
        }
        // "Disconnected", in red, under someone who left or was removed from
        // the production — Android's listing row (`disconnedtedTxtView`,
        // ChatAndGroupListingAdapter.kt:147-155) and its Members row, which
        // suffixes the designation the same way. Their history still opens;
        // the caption says why the composer will be gone.
        if (contact.hasLeft) {
            ZillitText(
                text = str(S.disconnected),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
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

/** The name, its inline admin suffix, and the stamp at the far end. */
@Composable
private fun NameLine(contact: CrewContact, unread: Boolean, stamp: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        // Name and suffix together take the slack; the stamp keeps its
        // width, so a long name ellipsises and the clock never does.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(
                text = contact.fullName,
                style = ZillitTheme.typography.bodyLarge.copy(
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Inline, the web's way — not a trailing tag.
            if (contact.isAdmin) {
                ZillitText(
                    text = "(Admin)",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        if (!stamp.isNullOrBlank()) {
            // Lit with the badge: an unread row's clock is the second
            // thing the eye asks for after "how many".
            ZillitText(
                text = stamp,
                style = ZillitTheme.typography.labelSmall,
                color = if (unread) ZillitTheme.colors.accentText else ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * The preview line's end: how much is waiting, in a round accent count, and
 * the star — shown when it is lit or the cursor is on the row, and faded
 * rather than removed otherwise, so the press always has something to land on.
 */
@Composable
private fun RowTrailing(
    badge: Int,
    isFavourite: Boolean?,
    onToggleFavourite: () -> Unit,
    revealed: Boolean = true,
) {
    // The star first, so the count — when there is one — sits flush at the
    // row's end, where WhatsApp keeps it.
    if (isFavourite != null) {
        ZillitIconButton(
            icon = if (isFavourite) ZillitIcons.StarFilled else ZillitIcons.StarOutline,
            contentDescription = if (isFavourite) str(S.desktop_unstar) else str(S.desktop_star),
            onClick = onToggleFavourite,
            tint = if (isFavourite) ZillitTheme.colors.warning else ZillitTheme.colors.textMuted,
            size = STAR_SIZE,
            modifier = Modifier.alpha(if (isFavourite || revealed) 1f else 0f),
        )
    }
    if (badge > 0) {
        // The C&C listing wears the brand orange, not the alert red —
        // the web's chat badges.
        ZillitBadge(
            count = badge,
            background = ZillitTheme.colors.accent,
            modifier = Modifier.defaultMinSize(minWidth = BADGE_MIN),
        )
    }
}

/**
 * The row's one-line reading of a preview: "You: …" for one's own line,
 * the writer's first name in a room, the bare words from the other end of
 * a DM — who wrote it is the name over the row.
 */
private fun ChatPreview.line(
    selfId: String?,
    inRoom: Boolean,
    nameFor: (String) -> String? = { null },
): String {
    val words = text.lineSequence().firstOrNull().orEmpty().trim()
    val by = when {
        selfId != null && senderId == selfId -> str(S.you)
        inRoom -> nameFor(senderId)?.substringBefore(' ')
        else -> null
    }
    return if (by == null) words else "$by: $words"
}

/** The user rows' third line — `last_entry: Aug 19, 2026`, the web's. */
private fun CrewContact.lastEntryLine(): String? =
    lastActiveMillis?.takeIf { it > 0 }
        ?.let { "${"last_entry".localised()}: ${lastEntryDate(it)}" }

private val STAR_SIZE = 22.dp
private val BADGE_MIN = 20.dp
private val ROW_HEIGHT = 72.dp
private val ROW_PAD_V = 10.dp

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

/**
 * The listing's five chips, one always lit, on one line.
 *
 * The line scrolls sideways rather than wrapping: at the pane's 320dp the
 * fifth chip used to drop to a second row on its own, which cost a row of
 * list for one word. The last chip peeking past the edge is the hint.
 */
@Composable
private fun FilterChips(chosen: ChatFilter, onPick: (ChatFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ChatFilter.entries.forEach { entry ->
            FilterPill(label = entry.label, selected = chosen == entry, onClick = { onPick(entry) })
        }
    }
}

/**
 * One filter, WhatsApp's pill: the panel grey at rest, the accent's soft tint
 * with its text colour when chosen — a quiet mark, where a filled orange chip
 * shouted over the list it filters.
 */
@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        when {
            selected -> colors.accentSoft
            hovered -> colors.surfaceHover
            else -> colors.chatPanel
        },
        animationSpec = tween(ROW_TINT_MILLIS),
        label = "filterPill",
    )
    ZillitText(
        text = label,
        style = ZillitTheme.typography.label.copy(
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        ),
        color = if (selected) colors.accentText else colors.textSecondary,
        maxLines = 1,
        modifier = Modifier
            .clip(ZillitTheme.shapes.pill)
            .background(background)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = PILL_PAD_V),
    )
}

private val PILL_PAD_V = 6.dp

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
        text = str(S.message),
        onClick = { onMessage(contact) },
    )
}

/** Face, name, badge and role — the card's identity block. */
@Composable
private fun CardIdentity(contact: CrewContact, loadAvatar: suspend (String) -> ImageBitmap?) {
    ZillitAvatar(
        name = contact.fullName,
        image = rememberChatFace(contact.userId, loadAvatar),
        size = CARD_AVATAR,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(text = contact.fullName, style = ZillitTheme.typography.titleLarge)
        if (contact.isAdmin) ZillitTag(str(S.admin), tone = TagTone.Accent)
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

/**
 * A person's face for a chat row or header: the app's shared face loader
 * where the host installed one ([LocalAvatarLoader] — cached per production,
 * fetched and decoded off the UI thread), the screen's own [load] seam
 * otherwise (tests, previews).
 *
 * The seam alone fetched the full-size photo and decoded it on the UI thread
 * every time a row came into view, and a lazy list recycles its rows: a
 * scroll through the listing paid that for every row it passed, the decodes
 * landed as the list came to rest — a hitch at the end of the scroll — and
 * every face flashed from initials to photo again on its way back in.
 */
@Composable
internal fun rememberChatFace(
    userId: String,
    load: suspend (String) -> ImageBitmap?,
): ImageBitmap? {
    val shared = LocalAvatarLoader.current
    return produceState<ImageBitmap?>(initialValue = null, userId, shared) {
        value = if (shared != null) shared.load(userId) else load(userId)
    }.value
}

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
private val ROW_AVATAR = 48.dp
private const val ROW_TINT_MILLIS = 120
private val CARD_MAX_WIDTH = 380.dp
private val CARD_EDGE = 6.dp
private val CARD_AVATAR = 72.dp
private val MAIL_ICON = 14.dp
private val MESSAGE_MAX_WIDTH = 360.dp
private val ICON_DISC = 64.dp
private val ICON_SIZE = 28.dp
