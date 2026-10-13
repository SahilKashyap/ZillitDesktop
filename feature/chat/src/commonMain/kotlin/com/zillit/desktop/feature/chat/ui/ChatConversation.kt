package com.zillit.desktop.feature.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.designsystem.component.AudioPlayer
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.CrewContact

/**
 * One conversation, without the chats list beside it.
 *
 * The C&C tool draws its own list; a tool that already has one of its own —
 * the budget tools list departments and documents — wants only the thread. It
 * is the same [ThreadPane] the chat screen uses, so a budget conversation gets
 * replies, attachments, receipts and voice notes for free, and any fix to the
 * thread lands in both places at once.
 *
 * The view model behind it decides which surface these messages belong to:
 * see `ChatScope`.
 */
@Composable
@Suppress("LongParameterList") // Straight pass-throughs to ThreadPane, each defaulted.
fun ChatConversation(
    state: ChatUiState,
    onEvent: (ChatEvent) -> Unit,
    seams: ChatSeams = ChatSeams(),
    resolveName: (String) -> String? = { null },
    onOpenAttachment: (ChatAttachment) -> Unit = {},
    /** The crew member behind an id — names in the readers panel, tags, and Forward. */
    resolveContact: (String) -> CrewContact? = { null },
    /** Who Forward can send to, besides the rooms the state already lists. */
    forwardPeople: List<CrewContact> = emptyList(),
    /** The signed-in user — dropped from the readers list, as the web drops them. */
    selfId: String? = null,
    loadAvatar: suspend (String) -> ImageBitmap? = { null },
    loadThumbnail: suspend (ChatAttachment) -> ImageBitmap? = { null },
    /** The one shared speaker; null renders voice notes as plain chips. */
    player: AudioPlayer? = null,
    loadAudio: suspend (ChatAttachment) -> ByteArray? = { null },
    /**
     * Look, don't touch — the File Cabinet's live conversation. The same
     * bubbles, quotes, pictures and voice notes, but no header actions, no
     * composer, no menu or reactions, no group-info panel and no dialogs.
     * Pair it with a `ChatViewModel(readOnly = true)`: this only draws, and
     * it is the model that refrains from sending read receipts.
     */
    readOnly: Boolean = false,
) {
    if (readOnly) {
        CompositionLocalProvider(LocalChatSeams provides seams) {
            ReadOnlyConversation(
                state, onEvent, resolveName, resolveContact, onOpenAttachment,
                loadAvatar, loadThumbnail, player, loadAudio,
            )
        }
        return
    }
    CompositionLocalProvider(LocalChatSeams provides seams) {
        ThreadPane(
            state = state,
            onEvent = onEvent,
            resolveName = resolveName,
            resolveMention = { id -> resolveContact(id)?.fullName },
            resolveContact = resolveContact,
            forwardPeople = forwardPeople,
            selfId = selfId,
            onOpenAttachment = onOpenAttachment,
            loadAvatar = loadAvatar,
            loadThumbnail = loadThumbnail,
            player = player,
            loadAudio = loadAudio,
        )
    }
}

/** [ReadOnlyThread] fed from the live model's state, with its loading and empty words. */
@Composable
@Suppress("LongParameterList") // Straight pass-throughs, as above.
private fun ReadOnlyConversation(
    state: ChatUiState,
    onEvent: (ChatEvent) -> Unit,
    resolveName: (String) -> String?,
    resolveContact: (String) -> CrewContact?,
    onOpenAttachment: (ChatAttachment) -> Unit,
    loadAvatar: suspend (String) -> ImageBitmap?,
    loadThumbnail: suspend (ChatAttachment) -> ImageBitmap?,
    player: AudioPlayer?,
    loadAudio: suspend (ChatAttachment) -> ByteArray?,
) {
    if (state.peer == null) return
    if (state.isLoading && state.messages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ZillitText(
                text = str(S.desktop_chat_loading_conversation),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        return
    }
    if (state.messages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ZillitText(
                text = str(S.desktop_cabinet_chat_no_messages),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        return
    }
    ReadOnlyThread(
        messages = state.messages,
        modifier = Modifier.fillMaxSize(),
        resolveName = resolveName,
        resolveMention = { id -> resolveContact(id)?.fullName },
        loadAvatar = loadAvatar,
        loadThumbnail = loadThumbnail,
        onOpenAttachment = onOpenAttachment,
        player = player,
        loadAudio = loadAudio,
        hasOlder = state.hasOlder,
        loadingOlder = state.loadingOlder,
        onShowOlder = { onEvent(ChatEvent.ShowOlder) },
        showSenders = state.peerIsGroup,
    )
}
