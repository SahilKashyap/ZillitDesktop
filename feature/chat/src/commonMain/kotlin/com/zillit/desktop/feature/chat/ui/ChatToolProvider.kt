package com.zillit.desktop.feature.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.chat.domain.CrewContact

/**
 * The `/cnc` rail destination. The host hands in the crew (already stripped
 * of keep-name-private members) and an avatar loader; the screen owns the rest.
 */
@Suppress("LongParameterList") // Every host seam the screen needs, one each; a bag would only hide them.
class ChatToolProvider(
    private val crew: () -> List<CrewContact>,
    /** The signed-in user's id, hidden from the Contacts list. */
    private val selfId: () -> String? = { null },
    private val loadAvatar: suspend (String) -> ImageBitmap?,
    private val viewModel: ChatViewModel? = null,
    private val onOpenAttachment: (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> Unit = {},
    private val loadThumbnail:
    suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> ImageBitmap? = { null },
    private val onCall: ((peer: CrewContact, isGroup: Boolean, video: Boolean) -> Unit)? = null,
    /** The one shared speaker; null renders voice notes as plain chips. */
    private val player: com.zillit.desktop.core.designsystem.component.AudioPlayer? = null,
    private val loadAudio:
    suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> ByteArray? = { null },
    /** The call history pane, supplied by the app; null hides the Calls tab. */
    private val callLog: (@Composable () -> Unit)? = null,
) : ToolProvider {

    override val path: String = "/cnc"
    override val title: String = "Chat & Calls"
    override val icon = ZillitIcons.Chat
    override val openMode: OpenMode = OpenMode.Maximized

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        ChatScreen(
            crew = crew(),
            selfId = selfId(),
            loadAvatar = loadAvatar,
            viewModel = viewModel,
            onOpenAttachment = onOpenAttachment,
            loadThumbnail = loadThumbnail,
            player = player,
            loadAudio = loadAudio,
            onCall = onCall,
            callLog = callLog,
        )
    }
}
