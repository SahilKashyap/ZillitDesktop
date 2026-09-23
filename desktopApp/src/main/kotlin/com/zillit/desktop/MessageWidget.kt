package com.zillit.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.flattenMentions
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.delay

/**
 * The floating message card: the sender and the first line of a message that
 * arrived while Zillit's window was not in front, stacked bottom-right and
 * gone after a few seconds. A click opens the thread; the × drops the card.
 *
 * One card per conversation — a burst from one person replaces its card
 * rather than stacking five — and three at most, the oldest making room.
 * The card never takes focus, for the same reason the call card does not.
 *
 * Nothing here marks a message read: the card is a glimpse, and the badge
 * clears when the thread is opened, exactly as if the banner were clicked.
 */
@Composable
internal fun ApplicationScope.MessageWidget(
    ready: AppGraph.Ready,
    chat: ChatViewModel?,
    preferences: PreferenceStore,
    frame: ComposeWindow?,
    darkTheme: Boolean,
    crewName: (String) -> String?,
    showMain: () -> Unit,
    openChat: () -> Unit,
) {
    chat ?: return
    val toasts = remember { mutableStateListOf<MessageToast>() }
    val groupName: (String) -> String? = { id -> chat.state.value.groups.firstOrNull { it.id == id }?.name }
    LaunchedEffect(ready, chat) {
        ready.chatRepository.incoming.collect { message ->
            if (message.isMine) return@collect
            if (!preferences.get(ZillitPreferences.MessageWidget)) return@collect
            if (preferences.get(ZillitPreferences.MuteNotifications)) return@collect
            if (frame.isInFront()) return@collect
            toasts.admit(message.toToast(crewName, groupName))
        }
    }
    if (toasts.isEmpty()) return

    val windowState = rememberWindowState(
        size = DpSize(CARD_WIDTH, stackHeight(toasts.size)),
        position = WindowPosition.Aligned(Alignment.BottomEnd),
    )
    LaunchedEffect(toasts.size) { windowState.size = DpSize(CARD_WIDTH, stackHeight(toasts.size)) }
    Window(
        onCloseRequest = { toasts.clear() },
        state = windowState,
        title = str(S.desktop_messages),
        icon = androidx.compose.ui.res.painterResource("icons/zillit-icon.png"),
        alwaysOnTop = true,
        undecorated = true,
        resizable = false,
        focusable = false,
    ) {
        ZillitTheme(darkTheme = darkTheme) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(GAP)) {
                toasts.toList().forEach { toast ->
                    key(toast.id) {
                        LaunchedEffect(toast.id) {
                            delay(TOAST_MILLIS)
                            toasts.remove(toast)
                        }
                        ToastCard(
                            toast = toast,
                            onOpen = {
                                toasts.remove(toast)
                                showMain()
                                openChat()
                                openConversation(chat, ready, toast)
                            },
                            onDismiss = { toasts.remove(toast) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ToastCard(toast: MessageToast, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT)
            .clip(RoundedCornerShape(CORNER))
            .background(colors.surfaceRaised)
            .border(1.dp, colors.border, RoundedCornerShape(CORNER))
            .clickable(onClick = onOpen)
            .padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitText(
                text = toast.sender,
                style = ZillitTheme.typography.label,
                color = colors.textPrimary,
                maxLines = 1,
            )
            ZillitText(
                text = toast.preview,
                style = ZillitTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 2,
            )
        }
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = str(S.sync_action_dismiss),
            onClick = onDismiss,
            tint = colors.textMuted,
            size = DISMISS_SIZE,
        )
    }
}

/** What one card shows, and enough to open the thread it came from. */
internal data class MessageToast(
    val id: String,
    val sender: String,
    val preview: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String?,
    val isGroup: Boolean,
)

/**
 * The header names what the reader will recognise: the person for a direct
 * message, "Group · Person" for a group. A sender the crew list cannot name
 * (a guest, say) still gets a header that says which group spoke.
 */
internal fun ChatMessage.toToast(crewName: (String) -> String?, groupName: (String) -> String?): MessageToast {
    val person = crewName(senderId)
    val header = if (isGroup) {
        listOfNotNull(groupName(receiverId), person).joinToString(" · ").ifBlank { str(S.desktop_new_group_message) }
    } else {
        person ?: str(S.notification_redacted_new_message)
    }
    return MessageToast(
        id = uniqueId.ifBlank { id },
        sender = header,
        preview = cardPreview(crewName),
        conversationId = if (isGroup) receiverId else senderId,
        senderId = senderId,
        senderName = person,
        isGroup = isGroup,
    )
}

/**
 * The first line as a person would read it: mention tokens become names, and
 * a token nobody can name becomes "@someone" rather than an id in braces.
 * Shared with the notification banner, which showed the same raw tokens.
 */
internal fun ChatMessage.cardPreview(nameOf: (String) -> String?): String =
    attachment?.let { str(S.docusign_sent_on, it.name) }
        ?: flattenMentions(body, nameOf).replace(UNRESOLVED_MENTION, str(S.desktop_someone_mention))
            .ifBlank { str(S.desktop_sent_a_message) }

/** Newest first, one per conversation, at most [MAX_TOASTS]. */
internal fun MutableList<MessageToast>.admit(toast: MessageToast) {
    removeAll { it.conversationId == toast.conversationId }
    add(0, toast)
    while (size > MAX_TOASTS) removeAt(lastIndex)
}

internal fun stackHeight(count: Int): Dp = CARD_HEIGHT * count + GAP * (count - 1).coerceAtLeast(0)

private fun openConversation(chat: ChatViewModel, ready: AppGraph.Ready, toast: MessageToast) {
    if (toast.isGroup) {
        chat.state.value.groups.firstOrNull { it.id == toast.conversationId }
            ?.let { chat.onEvent(ChatEvent.OpenGroup(it)) }
        return
    }
    val name = ready.projectContext?.context?.value?.user(toast.senderId)?.fullName
        ?: toast.senderName
        ?: str(S.crew_member)
    chat.onEvent(ChatEvent.OpenThread(CrewContact(userId = toast.senderId, fullName = name)))
}

private val CARD_WIDTH = 340.dp
private val CARD_HEIGHT = 84.dp
private val GAP = 8.dp
private val CORNER = 12.dp
private val DISMISS_SIZE = 24.dp
private val UNRESOLVED_MENTION = Regex("@?\\{\\{[a-zA-Z0-9]+\\}\\}")
private const val MAX_TOASTS = 3
private const val TOAST_MILLIS = 8_000L
