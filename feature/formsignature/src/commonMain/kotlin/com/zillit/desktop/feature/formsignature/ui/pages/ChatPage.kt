package com.zillit.desktop.feature.formsignature.ui.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.formsignature.ui.FormSignatureUiState

/**
 * "Chat with Admins" / "Chat with Users" — the web's `FormChatDiscussionV2`,
 * which is the unit-chat component on the tool's own room. The board itself
 * is the host's (the Home engine on the `form-signature` segment); this page
 * frames it and carries the admin's "pick who you are answering" rule.
 */
@Composable
internal fun ChatPage(
    state: FormSignatureUiState,
    board: (@Composable () -> Unit)?,
) {
    val chat = state.chat
    val unit = chat.unit
    Column(Modifier.fillMaxSize()) {
        if (unit != null && unit.answers(state.currentUserId) && chat.receiver == null) {
            ZillitNotice(
                text = str(S.desktop_fs_chat_select_user_hint),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
                modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
            )
        }
        Box(Modifier.fillMaxSize()) {
            when {
                unit == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ZillitEmptyState(title = str(S.desktop_fs_no_unit_for_discussion), icon = ZillitIcons.Chat)
                }
                board == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ZillitEmptyState(
                        title = str(S.desktop_fs_room_unavailable),
                        message = str(S.desktop_fs_room_unavailable_hint),
                        icon = ZillitIcons.Chat,
                    )
                }
                else -> board()
            }
        }
    }
}
