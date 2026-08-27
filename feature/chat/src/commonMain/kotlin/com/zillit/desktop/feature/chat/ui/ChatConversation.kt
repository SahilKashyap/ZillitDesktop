package com.zillit.desktop.feature.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.zillit.desktop.feature.chat.domain.ChatAttachment

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
fun ChatConversation(
    state: ChatUiState,
    onEvent: (ChatEvent) -> Unit,
    seams: ChatSeams = ChatSeams(),
    resolveName: (String) -> String? = { null },
    onOpenAttachment: (ChatAttachment) -> Unit = {},
) {
    CompositionLocalProvider(LocalChatSeams provides seams) {
        ThreadPane(
            state = state,
            onEvent = onEvent,
            resolveName = resolveName,
            onOpenAttachment = onOpenAttachment,
        )
    }
}
