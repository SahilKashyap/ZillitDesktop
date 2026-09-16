package com.zillit.desktop.feature.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
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
) {
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
