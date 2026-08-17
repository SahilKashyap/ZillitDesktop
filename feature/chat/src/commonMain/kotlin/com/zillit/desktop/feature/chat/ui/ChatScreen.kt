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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
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
import com.zillit.desktop.core.designsystem.component.rememberWheelScroll
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.domain.byDepartment
import com.zillit.desktop.feature.chat.domain.searchCrew
import com.zillit.desktop.feature.chat.domain.ChatFilter
import com.zillit.desktop.feature.chat.domain.chatTimeLabel

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
    onCall: ((peer: CrewContact, isGroup: Boolean, video: Boolean) -> Unit)? = null,
    /** The call history pane; null hides the Calls tab. */
    callLog: (@Composable () -> Unit)? = null,
) {
    val chatState = viewModel?.state?.collectAsState()?.value
    var tab by rememberSaveable { mutableStateOf(DirectoryTab.Crew.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

    Row(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        DirectoryPane(
            crew = crew,
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
            },
            onQuery = { query = it },
            onSelect = { selectedId = it },
            onChatEvent = { event -> viewModel?.onEvent(event) },
            callLog = callLog,
        )

        Box(
            Modifier
                .width(HAIRLINE)
                .fillMaxHeight()
                .background(ZillitTheme.colors.border),
        )

        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            val selected = crew.firstOrNull { it.userId == selectedId }
            when {
                chatState?.peer != null && viewModel != null ->
                    OpenThread(
                        chatState, viewModel, crew, onOpenAttachment,
                        loadAvatar, loadThumbnail, onCall, player, loadAudio,
                    )

                selected != null -> ContactCard(selected, loadAvatar) { contact ->
                    viewModel?.onEvent(ChatEvent.OpenThread(contact))
                }

                else -> PaneMessage(
                    icon = ZillitIcons.User,
                    text = "Pick someone from the crew to see their card.",
                )
            }
        }
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
    onCall: ((peer: CrewContact, isGroup: Boolean, video: Boolean) -> Unit)?,
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
        onOpenAttachment = onOpenAttachment,
        loadAvatar = loadAvatar,
        loadThumbnail = loadThumbnail,
        player = player,
        loadAudio = loadAudio,
        onCall = onCall?.let { ring ->
            { video ->
                chatState.peer?.let { open ->
                    ring(open, chatState.peerIsGroup, video)
                }
                Unit
            }
        },
    )
}

private enum class DirectoryTab(val label: String) {
    Chats("Chats"),
    Crew("Crew"),
    Calls("Calls"),
}

/** The left pane: title, the two tabs, and whichever list the tab shows. */
@Composable
@Suppress("LongParameterList")
private fun DirectoryPane(
    crew: List<CrewContact>,
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
) {
    Column(
        modifier = Modifier
            .width(LIST_WIDTH)
            .fillMaxHeight()
            .background(ZillitTheme.colors.surface)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(text = "Chat & Calls", style = ZillitTheme.typography.titleLarge)

        // Underline tabs, not chips: chips are for filters, and this row picks
        // which list the pane *is*. The Chats tab wears the total unread, so
        // the answer to "anything waiting?" does not need the tab opened.
        ZillitTabStrip(
            tabs = DirectoryTab.entries
                .filter { it != DirectoryTab.Calls || callLog != null }
                .map { entry ->
                    ZillitTab(
                        id = entry.name,
                        label = entry.label,
                        count = if (entry == DirectoryTab.Chats) {
                            chatState?.unread?.values?.sum() ?: 0
                        } else {
                            0
                        },
                    )
                },
            activeId = tab,
            onSelect = onTab,
        )

        if (tab == DirectoryTab.Calls.name && callLog != null) {
            callLog()
        } else if (tab == DirectoryTab.Crew.name) {
            ZillitSearchField(
                value = query,
                onValueChange = onQuery,
                placeholder = "Search name, role, department",
            )
            CrewList(
                crew = crew.searchCrew(query),
                // Follows whichever thread is open, however it was opened —
                // picking someone in Chats and then switching to Crew should
                // show that person as the one being read, not nobody.
                selectedId = chatState?.peer?.userId ?: selectedId,
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
            RecentsList(
                state = chatState,
                crew = crew,
                loadAvatar = loadAvatar,
                onEvent = onChatEvent,
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
 * The Chats tab: everyone with an existing thread, from the server's
 * `user:list`, resolved against the crew for faces and roles. A peer no
 * longer on the production has no card to show and is left out.
 */
@Composable
@Suppress("LongParameterList")
private fun RecentsList(
    state: ChatUiState,
    crew: List<CrewContact>,
    loadAvatar: suspend (String) -> ImageBitmap?,
    onEvent: (ChatEvent) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(ChatFilter.All.name) }
    val chosen = ChatFilter.valueOf(filter)
    val now = state.activity.values.maxOrNull() ?: 0L

    FilterChips(chosen) { filter = it.name }

    val groups = state.groups.filter { room -> chosen.admits(room.id, isGroup = true, state) }
    val rows = state.recents
        .mapNotNull { id -> crew.firstOrNull { it.userId == id } }
        .filter { contact -> chosen.admits(contact.userId, isGroup = false, state) }

    if (rows.isEmpty() && groups.isEmpty()) {
        PaneMessage(
            icon = ZillitIcons.Chat,
            text = when (chosen) {
                ChatFilter.All -> "No conversations yet — message someone from the Crew tab."
                else -> "Nothing under ${chosen.label} right now."
            },
        )
        return
    }

    val roomsState = rememberLazyListState()
    LazyColumn(
        state = roomsState,
        modifier = Modifier.then(rememberWheelScroll(roomsState)),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        if (groups.isNotEmpty()) {
            item(key = "groups-header") { SectionLine("Groups") }
            items(groups, key = GroupRoom::id) { room ->
                CrewRow(
                    contact = CrewContact(userId = room.id, fullName = room.name),
                    isSelected = false,
                    loadAvatar = { null },
                    onClick = { onEvent(ChatEvent.OpenGroup(room)) },
                    subtitle = state.previews[room.id],
                    badge = state.unread[room.id] ?: 0,
                    timeLabel = state.activity[room.id]?.let { chatTimeLabel(it, now) },
                    isFavourite = room.id in state.favourites,
                    onToggleFavourite = { onEvent(ChatEvent.ToggleFavourite(room.id)) },
                    showAdmin = false,
                )
            }
            if (rows.isNotEmpty()) item(key = "dm-header") { SectionLine("Direct messages") }
        }
        items(rows, key = CrewContact::userId) { contact ->
            CrewRow(
                contact = contact,
                isSelected = false,
                loadAvatar = loadAvatar,
                onClick = { onEvent(ChatEvent.OpenThread(contact)) },
                // The last line of the thread beats a job title here.
                subtitle = state.previews[contact.userId],
                badge = state.unread[contact.userId] ?: 0,
                timeLabel = state.activity[contact.userId]?.let { chatTimeLabel(it, now) },
                isFavourite = contact.userId in state.favourites,
                onToggleFavourite = { onEvent(ChatEvent.ToggleFavourite(contact.userId)) },
                showAdmin = false,
            )
        }
    }
}

/**
 * The Crew tab: everyone on the production, by department.
 *
 * A row opens that person's conversation. It used to select them and show a
 * card whose only action was "Message" — two clicks to reach the thing the
 * pane exists for, while the Chats tab beside it opened on one.
 */
@Composable
private fun CrewList(
    crew: List<CrewContact>,
    selectedId: String?,
    loadAvatar: suspend (String) -> ImageBitmap?,
    onOpen: (CrewContact) -> Unit,
) {
    val crewState = rememberLazyListState()
    LazyColumn(
        state = crewState,
        modifier = Modifier.then(rememberWheelScroll(crewState)),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        crew.byDepartment().forEach { (department, people) ->
            item(key = "dept-$department") {
                ZillitText(
                    // Grouped by the raw key above, so two spellings cannot
                    // split one department — only the heading is translated.
                    text = department.localised(),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    modifier = Modifier.padding(
                        top = ZillitTheme.spacing.sm,
                        bottom = ZillitTheme.spacing.xxs,
                    ),
                )
            }
            items(people, key = CrewContact::userId) { contact ->
                CrewRow(
                    contact = contact,
                    isSelected = contact.userId == selectedId,
                    loadAvatar = loadAvatar,
                    onClick = { onOpen(contact) },
                )
            }
        }
    }
}

@Composable
private fun CrewRow(
    contact: CrewContact,
    isSelected: Boolean,
    loadAvatar: suspend (String) -> ImageBitmap?,
    onClick: () -> Unit,
    subtitle: String? = null,
    badge: Int = 0,
    timeLabel: String? = null,
    isFavourite: Boolean? = null,
    onToggleFavourite: () -> Unit = {},
    showAdmin: Boolean = true,
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
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = contact.fullName,
                style = if (unread) {
                    ZillitTheme.typography.titleSmall
                } else {
                    ZillitTheme.typography.bodyMedium
                },
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
            )
            // `designation` is a translation key off `project/users`
            // (`driver_label`); an explicit subtitle is already display text.
            (subtitle ?: contact.designation?.localised())?.takeIf { it.isNotBlank() }?.let {
                ZillitText(
                    text = it,
                    style = ZillitTheme.typography.labelSmall,
                    // An unread preview is the message itself, not furniture —
                    // it earns the darker ink until it is read.
                    color = if (unread) {
                        ZillitTheme.colors.textSecondary
                    } else {
                        ZillitTheme.colors.textMuted
                    },
                    maxLines = 1,
                )
            }
        }
        RowTrailing(contact, badge, timeLabel, isFavourite, onToggleFavourite, showAdmin)
    }
}

/** The row's right edge: when, how much is waiting, and the star. */
@Composable
@Suppress("LongParameterList") // One row's worth of trailing state, passed through.
private fun RowTrailing(
    contact: CrewContact,
    badge: Int,
    timeLabel: String?,
    isFavourite: Boolean?,
    onToggleFavourite: () -> Unit,
    showAdmin: Boolean,
) {
    timeLabel?.takeIf { it.isNotBlank() }?.let {
        ZillitText(
            text = it,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
    if (badge > 0) {
        ZillitBadge(count = badge)
    } else if (showAdmin && contact.isAdmin) {
        ZillitTag("Admin", tone = TagTone.Accent)
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

private val STAR_SIZE = 22.dp

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

/** Whether one conversation belongs under this chip. */
private fun ChatFilter.admits(id: String, isGroup: Boolean, state: ChatUiState): Boolean =
    when (this) {
        ChatFilter.All -> true
        ChatFilter.Groups -> isGroup
        ChatFilter.Members -> !isGroup
        ChatFilter.Unread -> (state.unread[id] ?: 0) > 0
        ChatFilter.Favourites -> id in state.favourites
    }

@Composable
private fun SectionLine(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        modifier = Modifier.padding(
            top = ZillitTheme.spacing.sm,
            bottom = ZillitTheme.spacing.xxs,
        ),
    )
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
        contact.designation?.takeIf { it.isNotBlank() },
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
