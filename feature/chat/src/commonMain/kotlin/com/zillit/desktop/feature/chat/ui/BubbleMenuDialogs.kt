package com.zillit.desktop.feature.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.chat.domain.ChatComposerRules
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.ForwardTarget
import com.zillit.desktop.feature.chat.domain.ReadByRow
import com.zillit.desktop.feature.chat.domain.designationLabel
import com.zillit.desktop.feature.chat.domain.lastMessageAt
import com.zillit.desktop.feature.chat.domain.searchCrew

/**
 * The dialogs the bubble menu opens — Edit, Forward, Read by — each a
 * [ZillitDialogShell] over the thread, kept composed with its trigger null
 * so the shell's exit can play with the last content still in it.
 */

/**
 * Rewriting one of our own lines — the web's `EditModal`
 * (`cnc_latest/components/EditModal.jsx`): the words, prefilled, and Save.
 * The 2000-character ceiling is the composer's; the dialog says so rather
 * than letting Save fail on the wire.
 */
@Composable
internal fun EditMessageDialog(state: ChatUiState, onEvent: (ChatEvent) -> Unit) {
    var shown by remember { mutableStateOf(state.editing) }
    if (state.editing != null) shown = state.editing
    val target = shown ?: return
    val overLimit = ChatComposerRules.bodyTooLong(state.editDraft.trim())
    val unchanged = state.editDraft.trim() == target.body.trim()

    ZillitDialogShell(
        title = "Edit message",
        subtitle = if (target.attachment != null) {
            "The caption changes for everyone."
        } else {
            "The words change for everyone."
        },
        icon = ZillitIcons.Edit,
        visible = state.editing != null,
        onDismiss = { onEvent(ChatEvent.CancelEdit) },
        width = EDIT_WIDTH,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(ChatEvent.CancelEdit) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = "Save",
                enabled = state.editDraft.isNotBlank() && !overLimit && !unchanged,
                onClick = { onEvent(ChatEvent.SubmitEdit) },
            )
        },
    ) {
        ZillitTextField(
            value = state.editDraft,
            onValueChange = { onEvent(ChatEvent.EditDraftChanged(it)) },
            placeholder = "Your message",
            singleLine = false,
            errorText = ChatComposerRules.BODY_TOO_LONG.takeIf { overLimit },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Where to send a copy — the web's "Forward In App" picker
 * (`ForwardMsgModal.jsx`), reduced to the two chat halves this client can
 * reach: rooms first, then people, one search over both, any number ticked.
 * The open thread is a valid destination too, as it is on the web.
 */
@Composable
@Suppress("LongMethod") // The picker: search, two sections, the empty note.
internal fun ForwardDialog(
    state: ChatUiState,
    people: List<CrewContact>,
    onEvent: (ChatEvent) -> Unit,
) {
    var shown by remember { mutableStateOf(state.forwarding) }
    if (state.forwarding != null) shown = state.forwarding
    val source = shown ?: return
    var query by remember(source.id) { mutableStateOf("") }
    var picked by remember(source.id) { mutableStateOf(emptySet<ForwardTarget>()) }
    val rooms = remember(state.groups, query) {
        state.groups.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    }
    val crew = remember(people, query) { people.searchCrew(query) }

    ZillitDialogShell(
        title = "Forward",
        subtitle = forwardSubtitle(source),
        icon = ZillitIcons.Forward,
        visible = state.forwarding != null,
        onDismiss = { onEvent(ChatEvent.CancelForward) },
        width = FORWARD_WIDTH,
        scrollable = false,
        actions = {
            ZillitText(
                text = if (picked.isEmpty()) "" else "${picked.size} selected",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(ChatEvent.CancelForward) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = "Send",
                enabled = picked.isNotEmpty(),
                onClick = { onEvent(ChatEvent.ForwardTo(picked.toList())) },
            )
        },
    ) {
        ZillitSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search people and groups",
            modifier = Modifier.fillMaxWidth(),
        )
        val listState = rememberLazyListState()
        ZillitLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = FORWARD_LIST_HEIGHT),
        ) {
            if (rooms.isNotEmpty()) {
                item(key = "groups-heading") { SectionHeading("Groups") }
                items(rooms, key = { "g-${it.id}" }) { room ->
                    val target = ForwardTarget(room.id, isGroup = true)
                    TargetRow(
                        name = room.name,
                        caption = null,
                        checked = target in picked,
                        onToggle = { picked = picked.toggled(target) },
                    )
                }
            }
            if (crew.isNotEmpty()) {
                item(key = "people-heading") { SectionHeading("People") }
                items(crew, key = { "u-${it.userId}" }) { person ->
                    val target = ForwardTarget(person.userId, isGroup = false)
                    TargetRow(
                        name = person.fullName,
                        userId = person.userId,
                        caption = person.designationLabel(),
                        checked = target in picked,
                        onToggle = { picked = picked.toggled(target) },
                    )
                }
            }
            if (rooms.isEmpty() && crew.isEmpty()) {
                item(key = "empty") {
                    ZillitText(
                        text = "Nobody matches.",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                        modifier = Modifier.padding(ZillitTheme.spacing.sm),
                    )
                }
            }
        }
    }
}

/** What is being forwarded, in a line: the words, or the file's name, or the place. */
private fun forwardSubtitle(source: ChatMessage): String = when {
    source.location != null -> "A shared location."
    source.attachment != null -> source.attachment.name
    else -> source.body
}.take(FORWARD_SUBTITLE_CHARS)

private fun Set<ForwardTarget>.toggled(target: ForwardTarget): Set<ForwardTarget> =
    if (target in this) this - target else this + target

@Composable
private fun SectionHeading(label: String) {
    ZillitText(
        text = label.uppercase(),
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        modifier = Modifier.padding(top = ZillitTheme.spacing.sm, bottom = ZillitTheme.spacing.xxs),
    )
}

@Composable
private fun TargetRow(
    name: String,
    caption: String?,
    checked: Boolean,
    onToggle: () -> Unit,
    /** A person's id; a group has none and draws its initials. */
    userId: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.small)
            .clickable(onClick = onToggle)
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = name, userId = userId, size = TARGET_AVATAR)
        Column(Modifier.weight(1f)) {
            ZillitText(text = name, style = ZillitTheme.typography.bodySmall, maxLines = 1)
            if (caption != null) {
                ZillitText(
                    text = caption,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        ZillitCheckbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

/**
 * Who has and has not read a room's line — the web's `ReadByUsers` modal:
 * two lists, the read side stamped with the read time and when it was
 * delivered, the unread side saying whether it reached the device at all.
 * Rows are named from the crew; one the crew cannot place is dropped, as
 * the web's `findUser` drops it, and one's own row is not a reader.
 */
@Composable
@Suppress("LongMethod") // Search, two tabs, and the three states of the answer.
internal fun ReadByDialog(
    state: ChatUiState,
    selfId: String?,
    resolveContact: (String) -> CrewContact?,
    onEvent: (ChatEvent) -> Unit,
) {
    var shown by remember { mutableStateOf(state.readBy) }
    if (state.readBy != null) shown = state.readBy
    val view = shown ?: return
    var showUnread by remember(view.message.id) { mutableStateOf(false) }
    var query by remember(view.message.id) { mutableStateOf("") }

    val read = view.report?.read?.named(resolveContact, selfId, query)
    val unread = view.report?.unread?.named(resolveContact, selfId, query)

    ZillitDialogShell(
        title = "Read by",
        icon = ZillitIcons.Eye,
        visible = state.readBy != null,
        onDismiss = { onEvent(ChatEvent.DismissReadBy) },
        width = FORWARD_WIDTH,
        scrollable = false,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = "Close",
                onClick = { onEvent(ChatEvent.DismissReadBy) },
                variant = ButtonVariant.Secondary,
            )
        },
    ) {
        ZillitSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search users",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ReadByTab("Read", read?.size, !showUnread) { showUnread = false }
            ReadByTab("Unread", unread?.size, showUnread) { showUnread = true }
        }
        when {
            view.error != null -> ZillitText(
                text = view.error,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
            )
            view.isLoading -> ZillitText(
                text = "Loading…",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            else -> {
                val rows = (if (showUnread) unread else read).orEmpty()
                if (rows.isEmpty()) {
                    ZillitText(
                        text = if (showUnread) "Everyone in the group has read this." else "No one has read this yet.",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                } else {
                    val listState = rememberLazyListState()
                    ZillitLazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = FORWARD_LIST_HEIGHT),
                    ) {
                        items(rows, key = { it.row.userId }) { reader -> ReaderRow(reader, showUnread) }
                    }
                }
            }
        }
    }
}

/** A receipt row with its crew member attached. */
private data class NamedReader(val row: ReadByRow, val contact: CrewContact)

/** The web's `getReadByList`/`getUnreadByList`: crew-matched, self dropped, the search applied. */
private fun List<ReadByRow>.named(
    resolveContact: (String) -> CrewContact?,
    selfId: String?,
    query: String,
): List<NamedReader> = mapNotNull { row ->
    if (row.userId == selfId) return@mapNotNull null
    val contact = resolveContact(row.userId) ?: return@mapNotNull null
    if (query.isNotBlank() && !contact.fullName.contains(query.trim(), ignoreCase = true)) return@mapNotNull null
    NamedReader(row, contact)
}

/** A count-carrying pill, as the Home board's read-by panel draws its two. */
@Composable
private fun ReadByTab(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .background(if (selected) ZillitTheme.colors.accentSoft else ZillitTheme.colors.canvas)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall,
            color = if (selected) ZillitTheme.colors.accent else ZillitTheme.colors.textPrimary,
        )
        ZillitText(
            text = count?.toString() ?: "…",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/**
 * One reader: name and designation, then the stamps — read time and
 * delivery on the read side (`ReadByUsers.jsx:128-141`, "Today" for a
 * literal zero), delivered-or-not on the unread side (`:164-172`).
 */
@Composable
private fun ReaderRow(reader: NamedReader, unread: Boolean) {
    val row = reader.row
    val caption = if (unread) {
        if (row.isDelivered) "Delivered" else "Not delivered"
    } else {
        buildString {
            row.readAtMillis?.takeIf { it > 0 }?.let { append("Read ${lastMessageAt(it)}") }
            val delivered = row.deliveredAtMillis
            if (delivered != null) {
                if (isNotEmpty()) append(" · ")
                append("Delivered ${if (delivered == 0L) "Today" else lastMessageAt(delivered)}")
            }
        }
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitAvatar(name = reader.contact.fullName, userId = reader.contact.userId, size = TARGET_AVATAR)
            Column(Modifier.weight(1f)) {
                val role = reader.contact.designationLabel()
                ZillitText(
                    text = if (role != null) "${reader.contact.fullName} ($role)" else reader.contact.fullName,
                    style = ZillitTheme.typography.bodySmall,
                    maxLines = 1,
                )
                if (caption.isNotBlank()) {
                    ZillitText(
                        text = caption,
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(HAIRLINE_DP).background(ZillitTheme.colors.border))
    }
}

private val EDIT_WIDTH = 480.dp
private val FORWARD_WIDTH = 440.dp
private val FORWARD_LIST_HEIGHT = 360.dp
private val TARGET_AVATAR = 28.dp
private val HAIRLINE_DP = 1.dp
private const val FORWARD_SUBTITLE_CHARS = 80
