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
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.forward.ForwardGroup
import com.zillit.desktop.core.workspace.forward.ForwardPick
import com.zillit.desktop.core.workspace.forward.ForwardPickerDialog
import com.zillit.desktop.core.workspace.forward.ForwardSection
import com.zillit.desktop.feature.chat.domain.ChatComposerRules
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.domain.ReadByRow
import com.zillit.desktop.feature.chat.domain.designationLabel
import com.zillit.desktop.feature.chat.domain.lastMessageAt

/**
 * The dialogs the bubble menu opens — Edit, Forward, Read by — each a
 * [ZillitDialogShell] over the thread, kept composed with its trigger null
 * so the shell's exit can play with the last content still in it.
 */

/**
 * Rewriting one of our own lines — the web's `EditModal`
 * (`cnc_latest/components/EditModal.jsx`): the words, prefilled, and Save.
 * The 8000-character ceiling is the composer's; the dialog says so rather
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
        title = str(S.edit_message),
        subtitle = if (target.attachment != null) {
            str(S.desktop_chat_caption_changes_for_everyone)
        } else {
            str(S.desktop_chat_words_change_for_everyone)
        },
        icon = ZillitIcons.Edit,
        visible = state.editing != null,
        onDismiss = { onEvent(ChatEvent.CancelEdit) },
        width = EDIT_WIDTH,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(ChatEvent.CancelEdit) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = str(S.save),
                enabled = state.editDraft.isNotBlank() && !overLimit && !unchanged,
                onClick = { onEvent(ChatEvent.SubmitEdit) },
            )
        },
    ) {
        ZillitTextField(
            value = state.editDraft,
            onValueChange = { onEvent(ChatEvent.EditDraftChanged(it)) },
            placeholder = str(S.desktop_chat_your_message),
            singleLine = false,
            errorText = ChatComposerRules.BODY_TOO_LONG.takeIf { overLimit },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The question before a line is withdrawn for everyone — the web's
 * "Delete message" modal (`MyMessage.jsx`) and the Popconfirm its selection
 * mode hangs off the bar's Delete (`ChatFooterCnc`: `multiplemessagedelete`).
 * Delete used to fire from the menu on one click, and there is no undo.
 *
 * One dialog for both: the sentence changes with the count, and Yes
 * withdraws either the one line or the whole ticked batch.
 */
@Composable
internal fun DeleteMessageDialog(state: ChatUiState, onEvent: (ChatEvent) -> Unit) {
    val asking: DeleteAsk? = when {
        state.confirmingDelete != null -> DeleteAsk.One(state.confirmingDelete.id)
        state.confirmingBatch.isNotEmpty() -> DeleteAsk.Many(state.confirmingBatch.size)
        else -> null
    }
    // Held through the exit animation, as the Edit dialog's target is.
    var shown by remember { mutableStateOf(asking) }
    if (asking != null) shown = asking
    val target = shown ?: return

    ZillitDialogShell(
        title = str(S.desktop_chat_delete_message),
        icon = ZillitIcons.Trash,
        visible = asking != null,
        onDismiss = { onEvent(ChatEvent.CancelDelete) },
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(ChatEvent.CancelDelete) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = str(S.delete),
                onClick = {
                    when (target) {
                        is DeleteAsk.One -> onEvent(ChatEvent.Delete(target.messageId))
                        is DeleteAsk.Many -> onEvent(ChatEvent.DeleteSelected)
                    }
                },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(
            text = when {
                target is DeleteAsk.Many && target.count > 1 -> {
                    str(S.desktop_board_delete_messages_confirm, target.count)
                }
                else -> str(S.desktop_chat_delete_message_confirm)
            },
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/** Which delete the question is about — one line, or a ticked batch. */
private sealed interface DeleteAsk {
    data class One(val messageId: String) : DeleteAsk

    data class Many(val count: Int) : DeleteAsk
}

/**
 * Where to send a copy — the shared picker, which is the web's
 * `ForwardMsgModal` (see `ForwardPickerDialog`).
 *
 * The chat's own halves head the list — Groups and People, each a list that
 * opens, any number of them ticked, the open thread among them (as it is on
 * the web). After them comes everything the host can reach: the notice
 * boards, the mailbox, the film tools this line's kind allows.
 */
@Composable
internal fun ForwardDialog(
    state: ChatUiState,
    people: List<CrewContact>,
    onEvent: (ChatEvent) -> Unit,
) {
    var shown by remember { mutableStateOf(state.forwarding) }
    if (state.forwarding != null) shown = state.forwarding
    val source = shown ?: return
    val sections = remember(state.groups, people, state.forwardSections) {
        chatSections(state.groups, people) + state.forwardSections
    }

    ForwardPickerDialog(
        visible = state.forwarding != null,
        subtitle = forwardSubtitle(source),
        sections = sections,
        resetKey = source.id,
        onSend = { targets -> onEvent(ChatEvent.ForwardPicked(targets)) },
        onStep = { target -> onEvent(ChatEvent.ForwardStepPicked(target)) },
        onDismiss = { onEvent(ChatEvent.CancelForward) },
    )
}

/**
 * The two halves this client can reach by itself, as picker entries: the
 * rooms it is in and the crew it can write to.
 *
 * Each is a list that opens, not a run of rows: the web's modal lists "Chat
 * User" and "Group Chat" as two entries that open their own pickers, and a
 * production's crew is long enough that spilling it into the dialog buries
 * everything under it. Ids carry the prefix the view model reads them back
 * by (`chatTargetOf`).
 */
private fun chatSections(
    rooms: List<GroupRoom>,
    people: List<CrewContact>,
): List<ForwardSection> {
    val entries = buildList {
        if (rooms.isNotEmpty()) {
            add(
                ForwardGroup(
                    id = "chat-rooms",
                    label = str(S.groups_txt),
                    icon = ZillitIcons.Users,
                    caption = str(S.desktop_forward_n_groups, rooms.size),
                    children = rooms.map { room ->
                        ForwardPick(
                            id = "$ROOM_TARGET${room.id}",
                            label = room.displayName,
                            icon = ZillitIcons.Users,
                        )
                    },
                ),
            )
        }
        if (people.isNotEmpty()) {
            add(
                ForwardGroup(
                    id = "chat-people",
                    label = str(S.section_people),
                    icon = ZillitIcons.User,
                    caption = str(S.dd_n_people, people.size),
                    children = people.map { person ->
                        ForwardPick(
                            id = "$USER_TARGET${person.userId}",
                            label = person.fullName,
                            // Role AND department, because the picker's one
                            // search box looks at the caption: `searchCrew`
                            // matched all three fields, and a coordinator
                            // looking for "camera" should still find them.
                            caption = person.roleLine(),
                            avatarUserId = person.userId,
                        )
                    },
                ),
            )
        }
    }
    return if (entries.isEmpty()) emptyList() else listOf(ForwardSection(str(S.desktop_chat_calls), entries))
}

/** "1st AD · Camera" — what the picker shows under a name, and searches. */
private fun CrewContact.roleLine(): String? =
    listOfNotNull(designationLabel(), department?.takeIf { it.isNotBlank() })
        .distinct()
        .joinToString(" · ")
        .takeIf { it.isNotBlank() }

/** What is being forwarded, in a line: the words, or the file's name, or the place. */
private fun forwardSubtitle(source: ChatMessage): String = when {
    source.location != null -> str(S.desktop_chat_a_shared_location)
    source.attachment != null -> source.attachment.name
    else -> source.body
}.take(FORWARD_SUBTITLE_CHARS)

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
        title = str(S.read_byr),
        icon = ZillitIcons.Eye,
        visible = state.readBy != null,
        onDismiss = { onEvent(ChatEvent.DismissReadBy) },
        width = FORWARD_WIDTH,
        scrollable = false,
        actions = {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(ChatEvent.DismissReadBy) },
                variant = ButtonVariant.Secondary,
            )
        },
    ) {
        ZillitSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = str(S.invitees_search_users),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ReadByTab(str(S.read), read?.size, !showUnread) { showUnread = false }
            ReadByTab(str(S.unread_txt), unread?.size, showUnread) { showUnread = true }
        }
        when {
            view.error != null -> ZillitText(
                text = view.error,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
            )
            view.isLoading -> ZillitText(
                text = str(S.ah_loading),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            else -> {
                val rows = (if (showUnread) unread else read).orEmpty()
                if (rows.isEmpty()) {
                    ZillitText(
                        text = if (showUnread) {
                            str(S.desktop_chat_everyone_has_read)
                        } else {
                            str(S.desktop_chat_no_one_has_read_yet)
                        },
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
        if (row.isDelivered) str(S.delivered) else str(S.not_delivered)
    } else {
        buildString {
            row.readAtMillis?.takeIf { it > 0 }?.let { append(str(S.desktop_read_at, lastMessageAt(it))) }
            val delivered = row.deliveredAtMillis
            if (delivered != null) {
                if (isNotEmpty()) append(" · ")
                append(str(S.desktop_delivered_at, if (delivered == 0L) str(S.today) else lastMessageAt(delivered)))
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
