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
    private val onCall: ((peer: CrewContact, isGroup: Boolean, video: Boolean, line: CallLine) -> Unit)? = null,
    /** Which lines the call buttons offer — Line 3 only where the host says the production has it. */
    private val lines: () -> List<CallLine> = { CallLine.DEFAULT },
    /** The one shared speaker; null renders voice notes as plain chips. */
    private val player: com.zillit.desktop.core.designsystem.component.AudioPlayer? = null,
    private val loadAudio:
    suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> ByteArray? = { null },
    /** The call history pane, supplied by the app; null hides the Calls tab. */
    private val callLog: (@Composable () -> Unit)? = null,
    /**
     * The viewer's download right on the C&C tool — `ProjectPermissions`'
     * `canDownload`, read per click so a rights change lands live. Gates
     * every save-to-disk path; the in-app viewer stays free to look.
     */
    private val canDownload: () -> Boolean = { true },
    /** Asks an admin for the download right, from the refusal. Null omits the offer. */
    private val requestDownloadRights: (() -> Unit)? = null,
    /** The system clipboard's picture half — composer paste and "Copy image". */
    private val clipboard: ClipboardMediaSource? = systemClipboardMedia(),
    /** The full-size fetch behind the lightbox; null falls back to thumbnails. */
    private val loadFullImage: (
        suspend (com.zillit.desktop.feature.chat.domain.ChatAttachment) -> ImageBitmap?
    )? = null,
    /** `ChatRepository::createRoom`; null hides the "New group" affordance. */
    private val createRoom: (
        suspend (String, List<String>) -> com.zillit.desktop.core.common.ZillitResult<
            com.zillit.desktop.feature.chat.domain.GroupRoom,
            >
    )? = null,
    /** `ChatRepository::searchMessages`; null keeps the Chats search to names. */
    private val searchMessages: ((String) -> List<com.zillit.desktop.feature.chat.data.MessageHit>)? = null,
    /** `ChatRepository::deleteRoom`; null hides the creator's group Delete. */
    private val deleteRoom: (
        suspend (roomId: String) -> com.zillit.desktop.core.common.ZillitResult<Unit>
    )? = null,
    /**
     * The app's guarded external-URL launcher — `main.kt`'s `openInBrowser`,
     * the same one the Maps, Sides and Document Distribution providers take.
     * Behind "Open in Maps" on a shared-location bubble; null hides it.
     */
    private val onOpenUrl: ((String) -> Unit)? = null,
    /**
     * One pane at a time — the Chat widget's shape. The rail's copy stays
     * wide; both share this one [ChatViewModel], so a message read in either
     * is read in both.
     */
    private val compact: Boolean = false,
    /** Opens the Chat widget — the tool's own way to it, as Drive has. */
    private val onOpenWidget: (() -> Unit)? = null,
) : ToolProvider {

    override val path: String = "/cnc"
    override val title: String = "Chat & Calls"
    override val icon = ZillitIcons.Chat
    override val openMode: OpenMode = OpenMode.Maximized

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        // Provided as a local rather than parameters: ThreadPane sits behind
        // ChatScreen, whose signature belongs to a concurrent session — see
        // ChatSeams.
        androidx.compose.runtime.CompositionLocalProvider(
            LocalChatSeams provides ChatSeams(
                canDownload = canDownload,
                requestDownloadRights = requestDownloadRights,
                clipboard = clipboard,
                loadFullImage = loadFullImage,
                onOpenUrl = onOpenUrl,
            ),
        ) {
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
                createRoom = createRoom,
                searchMessages = searchMessages,
                deleteRoom = deleteRoom,
                compact = compact,
                onOpenWidget = onOpenWidget,
                lines = lines,
            )
        }
    }
}
