package com.zillit.desktop.feature.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.SharedContent
import com.zillit.desktop.feature.chat.domain.SharedFile
import com.zillit.desktop.feature.chat.domain.SharedLink
import com.zillit.desktop.feature.chat.domain.chatTimeLabel
import com.zillit.desktop.feature.chat.domain.designationLabel

/** Which page of the info panel is showing. */
internal enum class InfoPage { Contact, Shared }

/** The three shelves of the shared-content page, in WhatsApp's order. */
private enum class SharedTab(private val labelKey: String) {
    Media(S.media_tab),
    Docs(S.docs_tab),
    Links(S.links_tab),
    ;

    val label: String get() = str(labelKey)
}

/** What the panel needs from the thread around it, gathered once. */
internal class InfoHooks(
    val loadAvatar: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap?,
    val media: BubbleMedia,
    /** Rings the conversation; null when it cannot be called. */
    val onCall: ((video: Boolean, line: CallLine) -> Unit)?,
    val lines: List<CallLine>,
    val onEvent: (ChatEvent) -> Unit,
)

/**
 * WhatsApp's contact info, beside the thread: who this is, large; the two
 * calls; what the conversation has shared; and the star. "Media, links and
 * docs" opens its own page over the same column.
 *
 * Groups get the same panel titled "Group info", without the star (rooms are
 * not starred) and without an address.
 */
@Composable
internal fun ContactInfoPanel(
    state: ChatUiState,
    peer: CrewContact,
    page: InfoPage,
    shared: SharedContent,
    hooks: InfoHooks,
    onPage: (InfoPage?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(ZillitTheme.colors.chatPanel)) {
        when (page) {
            InfoPage.Contact -> {
                PanelBar(
                    title = if (state.peerIsGroup) str(S.desktop_group_info) else str(S.contact_info),
                    icon = ZillitIcons.Close,
                    iconLabel = str(S.close),
                    onIcon = { onPage(null) },
                )
                ContactPage(state, peer, shared, hooks) { onPage(InfoPage.Shared) }
            }

            InfoPage.Shared -> {
                PanelBar(
                    title = str(S.desktop_media_links_docs),
                    icon = ZillitIcons.ArrowLeft,
                    iconLabel = str(S.back),
                    onIcon = { onPage(InfoPage.Contact) },
                )
                SharedPage(state, shared, hooks)
            }
        }
    }
}

/** The panel's own bar: one glyph that leaves, and the page's name. */
@Composable
private fun PanelBar(title: String, icon: ImageVector, iconLabel: String, onIcon: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.chatPanel)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIconButton(
            icon = icon,
            contentDescription = iconLabel,
            onClick = onIcon,
            tint = ZillitTheme.colors.textSecondary,
            size = BAR_ACTION,
        )
        ZillitText(text = title, style = ZillitTheme.typography.titleSmall, maxLines = 1)
    }
}

/** The contact page's white blocks on the panel grey, WhatsApp's light layout. */
@Composable
private fun ContactPage(
    state: ChatUiState,
    peer: CrewContact,
    shared: SharedContent,
    hooks: InfoHooks,
    onOpenShared: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        Section { IdentityBlock(state, peer, hooks) }
        Section { SharedBlock(state, shared, hooks, onOpenShared) }
        if (!state.peerIsGroup) {
            val starred = peer.userId in state.favourites
            Section {
                InfoRow(
                    icon = if (starred) ZillitIcons.StarFilled else ZillitIcons.StarOutline,
                    tint = if (starred) ZillitTheme.colors.warning else ZillitTheme.colors.textSecondary,
                    label = if (starred) {
                        str(S.desktop_remove_from_favourites)
                    } else {
                        str(S.desktop_add_to_favourites)
                    },
                    onClick = { hooks.onEvent(ChatEvent.ToggleFavourite(peer.userId)) },
                )
            }
        }
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(ZillitTheme.colors.surface)) { content() }
}

/** Face, name, the lines that place them, and the call buttons. */
@Composable
private fun IdentityBlock(state: ChatUiState, peer: CrewContact, hooks: InfoHooks) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitAvatar(
            name = peer.fullName,
            image = rememberChatFace(peer.userId, hooks.loadAvatar),
            size = BIG_AVATAR,
        )
        Row(
            modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(text = peer.fullName, style = ZillitTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (peer.isAdmin) ZillitTag(str(S.admin), tone = TagTone.Accent)
        }
        peer.email?.takeIf { it.isNotBlank() }?.let { CentredLine(it) }
        listOfNotNull(
            peer.department?.takeIf { it.isNotBlank() }?.localised(),
            peer.designationLabel()?.localised(),
        ).joinToString(" · ").takeIf { it.isNotBlank() }?.let { CentredLine(it) }
        if (!state.peerIsGroup && peer.hasLeft) {
            ZillitText(
                text = str(S.disconnected),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
            )
        }
        hooks.onCall?.let { ring -> CallActions(ring, hooks.lines) }
    }
}

@Composable
private fun CentredLine(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodyMedium,
        color = ZillitTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
    )
}

/** Voice and Video as WhatsApp's labelled tiles; each asks which line first. */
@Composable
private fun CallActions(ring: (video: Boolean, line: CallLine) -> Unit, lines: List<CallLine>) {
    Row(
        modifier = Modifier.padding(top = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        CallTile(ZillitIcons.Phone, str(S.desktop_voice), lines) { line -> ring(false, line) }
        CallTile(ZillitIcons.Camera, str(S.video), lines) { line -> ring(true, line) }
    }
}

@Composable
private fun CallTile(icon: ImageVector, label: String, lines: List<CallLine>, onPick: (CallLine) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = Modifier
                .width(CALL_TILE)
                .clip(RoundedCornerShape(TILE_CORNER))
                .border(HAIRLINE, ZillitTheme.colors.border, RoundedCornerShape(TILE_CORNER))
                .clickable { if (lines.size == 1) onPick(lines.first()) else open = true }
                .padding(vertical = ZillitTheme.spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitIcon(icon = icon, contentDescription = null, tint = ZillitTheme.colors.accentText, size = TILE_ICON)
            ZillitText(text = label, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textPrimary)
        }
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = lines.map { line ->
                ZillitMenuEntry.Action(label = line.label, icon = icon, tone = ZillitMenuTone.Approve) { onPick(line) }
            },
        )
    }
}

/** The "Media, links and docs" row, its count, and the newest pictures under it. */
@Composable
private fun SharedBlock(state: ChatUiState, shared: SharedContent, hooks: InfoHooks, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(icon = ZillitIcons.Photo, contentDescription = null, tint = ZillitTheme.colors.textSecondary)
        ZillitText(
            text = str(S.desktop_media_links_docs),
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = shared.total.toString(),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        ZillitIcon(
            icon = ZillitIcons.ChevronRight,
            contentDescription = null,
            tint = ZillitTheme.colors.textMuted,
            size = CHEVRON,
        )
    }
    if (shared.media.isNotEmpty()) {
        Row(
            modifier = Modifier.padding(
                start = ZillitTheme.spacing.lg,
                end = ZillitTheme.spacing.lg,
                bottom = ZillitTheme.spacing.md,
            ),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            // Always four slots, so the newest pictures keep one size
            // whether there are four of them or one.
            repeat(PREVIEW_TILES) { index ->
                Box(Modifier.weight(1f).aspectRatio(1f)) {
                    shared.media.getOrNull(index)?.let { item ->
                        SharedTile(item, hooks.media) { hooks.media.onView(item.file) }
                    }
                }
            }
        }
    }
    LoadedOnlyNote(state, hooks.onEvent)
}

/**
 * The honest footnote: what is listed is what the thread has loaded, and
 * there is more history behind it. Loading older pages fills the list.
 */
@Composable
private fun LoadedOnlyNote(state: ChatUiState, onEvent: (ChatEvent) -> Unit) {
    if (!state.hasOlder) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg)
            .padding(bottom = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = str(S.desktop_chat_info_loaded_only),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = if (state.loadingOlder) str(S.ah_loading) else str(S.desktop_load_older),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.accentText,
            modifier = Modifier.clickable(enabled = !state.loadingOlder) { onEvent(ChatEvent.ShowOlder) },
        )
    }
}

/** A plain row with a leading glyph — the star, and whatever joins it later. */
@Composable
private fun InfoRow(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(icon = icon, contentDescription = null, tint = tint)
        ZillitText(text = label, style = ZillitTheme.typography.bodyMedium)
    }
}

/** The shared-content page: Media, Docs and Links tabs over their lists. */
@Composable
private fun SharedPage(state: ChatUiState, shared: SharedContent, hooks: InfoHooks) {
    var tab by rememberSaveable { mutableStateOf(SharedTab.Media.name) }
    Column(Modifier.fillMaxSize().background(ZillitTheme.colors.surface)) {
        ZillitTabStrip(
            tabs = SharedTab.entries.map { ZillitTab(id = it.name, label = it.label) },
            activeId = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = ZillitTheme.spacing.md),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (SharedTab.valueOf(tab)) {
                SharedTab.Media -> MediaGrid(shared.media, hooks.media)
                SharedTab.Docs -> DocList(shared.docs, hooks.media)
                SharedTab.Links -> LinkList(shared.links)
            }
        }
        LoadedOnlyNote(state, hooks.onEvent)
    }
}

@Composable
private fun MediaGrid(items: List<SharedFile>, media: BubbleMedia) {
    if (items.isEmpty()) {
        EmptyShelf(str(S.desktop_no_media))
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(GRID_TILE),
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
        verticalArrangement = Arrangement.spacedBy(GRID_GAP),
    ) {
        items(items, key = { it.messageId + it.file.media }) { item ->
            Box(Modifier.aspectRatio(1f)) { SharedTile(item, media) { media.onView(item.file) } }
        }
    }
}

@Composable
private fun DocList(items: List<SharedFile>, media: BubbleMedia) {
    if (items.isEmpty()) {
        EmptyShelf(str(S.desktop_no_documents))
        return
    }
    val now = remember { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    ZillitLazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.messageId + it.file.media }) { item ->
            ShelfRow(
                icon = if (item.file.kind == "audio") ZillitIcons.Audio else ZillitIcons.File,
                title = item.file.name.ifBlank { item.file.kind },
                detail = listOfNotNull(
                    item.file.sizeBytes.takeIf { it > 0 }?.let(::byteLabel),
                    chatTimeLabel(item.atMillis, now).takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                onClick = { media.onOpen(item.file) },
            )
        }
    }
}

@Composable
private fun LinkList(items: List<SharedLink>) {
    if (items.isEmpty()) {
        EmptyShelf(str(S.desktop_no_links))
        return
    }
    val uri = LocalUriHandler.current
    val now = remember { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    ZillitLazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.messageId + it.url }) { link ->
            ShelfRow(
                icon = ZillitIcons.Link,
                title = link.text,
                titleColor = ZillitTheme.colors.accentText,
                detail = listOf(link.body.lineSequence().first().trim(), chatTimeLabel(link.atMillis, now))
                    .filter { it.isNotBlank() && it != link.text }
                    .joinToString(" · "),
                onClick = { runCatching { uri.openUri(link.url) } },
            )
        }
    }
}

/** One document or link: a glyph disc, the name, and a quiet line under it. */
@Composable
private fun ShelfRow(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
    titleColor: androidx.compose.ui.graphics.Color = ZillitTheme.colors.textPrimary,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            Modifier.size(SHELF_DISC).clip(CircleShape).background(ZillitTheme.colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = icon, contentDescription = null, tint = ZillitTheme.colors.accentText, size = SHELF_ICON)
        }
        Column(Modifier.weight(1f)) {
            ZillitText(text = title, style = ZillitTheme.typography.bodyMedium, color = titleColor, maxLines = 1)
            if (detail.isNotBlank()) {
                ZillitText(
                    text = detail,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun EmptyShelf(text: String) {
    Box(Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg), contentAlignment = Alignment.Center) {
        ZillitText(text = text, style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textMuted)
    }
}

/**
 * A square crop of a shared picture or clip, through the thread's poster
 * memory so a picture the thread already fetched is not fetched again. A
 * miss shows the kind's glyph; a clip wears a play mark.
 */
@Composable
private fun SharedTile(item: SharedFile, media: BubbleMedia, onClick: () -> Unit) {
    val file = item.file
    val poster by produceState(media.posters.known(file.media) ?: PosterState.Loading, file.media) {
        if (value == PosterState.Loading) {
            val image = media.loadThumbnail(file)
            media.posters.keep(file.media, image)
            value = PosterState.Done(image)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(TILE_CORNER))
            .background(ZillitTheme.colors.surfaceSunken)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val image = (poster as? PosterState.Done)?.image
        if (image != null) {
            Image(
                image,
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (poster is PosterState.Done) {
            ZillitIcon(icon = ZillitIcons.Photo, contentDescription = file.name, tint = ZillitTheme.colors.textMuted)
        }
        if (file.kind == "video") {
            Box(
                Modifier.size(PLAY_MARK).clip(CircleShape).background(PLAY_SCRIM),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(
                    icon = ZillitIcons.Play,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    size = PLAY_GLYPH,
                )
            }
        }
    }
}

/**
 * The thread beside its info panel: a column of its own when [sideBySide],
 * covering the thread otherwise. No [page], no panel.
 */
@Composable
@Suppress("LongParameterList") // The panel's inputs, passed through once.
internal fun WithInfoPanel(
    page: InfoPage?,
    sideBySide: Boolean,
    state: ChatUiState,
    peer: CrewContact,
    shared: SharedContent,
    hooks: InfoHooks,
    onPage: (InfoPage?) -> Unit,
    thread: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) { thread() }
        if (sideBySide && page != null) {
            Box(Modifier.width(HAIRLINE).fillMaxHeight().background(ZillitTheme.colors.border))
            ContactInfoPanel(state, peer, page, shared, hooks, onPage, Modifier.width(INFO_PANEL_WIDTH))
        }
    }
    if (!sideBySide && page != null) {
        ContactInfoPanel(state, peer, page, shared, hooks, onPage)
    }
}

/** "113 kB", "2.4 MB" — WhatsApp's size line. */
internal fun byteLabel(bytes: Long): String = when {
    bytes < KILO -> "$bytes B"
    bytes < KILO * KILO -> "${bytes / KILO} kB"
    else -> {
        // Rounded to the nearest tenth, not cut: 2,516,582 bytes is 2.4 MB.
        val tenths = (bytes * TEN + KILO * KILO / 2) / (KILO * KILO)
        "${tenths / TEN}.${tenths % TEN} MB"
    }
}

/** How wide the panel sits beside the thread. */
internal val INFO_PANEL_WIDTH: Dp = 380.dp

/** Below this the thread and the panel cannot share the pane; the panel covers it. */
internal val INFO_SIDE_BY_SIDE_MIN: Dp = 760.dp

private val BAR_ACTION = 36.dp
private val SECTION_GAP = 10.dp
private val BIG_AVATAR = 160.dp
private val CALL_TILE = 96.dp
private val TILE_CORNER = 8.dp
private val TILE_ICON = 22.dp
private val HAIRLINE = 1.dp
private val CHEVRON = 16.dp
private const val PREVIEW_TILES = 4
private val GRID_TILE = 104.dp
private val GRID_GAP = 4.dp
private val SHELF_DISC = 40.dp
private val SHELF_ICON = 18.dp
private val PLAY_MARK = 28.dp
private val PLAY_GLYPH = 14.dp
private val PLAY_SCRIM = androidx.compose.ui.graphics.Color(0x8C000000)
private const val KILO = 1024L
private const val TEN = 10L
