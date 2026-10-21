package com.zillit.desktop.feature.chat.cabinet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.cabinet.CabinetEntry
import com.zillit.desktop.core.cabinet.CabinetViewer
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.AudioPlayer
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.chat.data.ChatRepository
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.ui.ChatSeams
import com.zillit.desktop.feature.chat.ui.LocalChatSeams
import com.zillit.desktop.feature.chat.ui.ReadOnlyThread
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * What the host lends the cabinet's chat viewer beyond the repository: how to
 * name a writer, fetch a face or a picture, and open a file. All optional — a
 * viewer built with none still reads every line, it just draws initials, no
 * thumbnails, and files that do not open.
 *
 * The same seams the live thread takes from `ChatToolProvider`, so the host
 * hands over the functions it already has rather than writing second ones.
 */
@Suppress("LongParameterList") // One seam each, as ChatToolProvider takes them; a bag would only hide them.
class CabinetChatHooks(
    /** A user id's display name, from the crew list. */
    val resolveName: (String) -> String? = { null },
    val loadAvatar: suspend (String) -> ImageBitmap? = { null },
    val loadThumbnail: suspend (ChatAttachment) -> ImageBitmap? = { null },
    /** Saves a file and hands it to the OS — gated by [canDownload]. */
    val onOpenAttachment: (ChatAttachment) -> Unit = {},
    /** The lightbox's full-size fetch; null falls back to the thumbnail. */
    val loadFullImage: (suspend (ChatAttachment) -> ImageBitmap?)? = null,
    /** A document's own bytes for the in-app viewer; null leaves it nothing to draw. */
    val loadDocument: (suspend (ChatAttachment) -> ByteArray?)? = null,
    /** A clip's playable address; null leaves video to the save-and-open. */
    val videoUrl: (suspend (ChatAttachment) -> String?)? = null,
    /** The Chat tool's download right, read per click. */
    val canDownload: () -> Boolean = { true },
    val onOpenUrl: ((String) -> Unit)? = null,
    val player: AudioPlayer? = null,
    val loadAudio: suspend (ChatAttachment) -> ByteArray? = { null },
)

/**
 * The File Cabinet's `group_chat` row: the production's group rooms, each
 * opening that room's archived lines for reading — the web's `CncGroupModal`.
 *
 * The bubbles are the live thread's own ([ReadOnlyThread]); only the source
 * differs, [ChatRepository.cabinetHistory]. Nothing here can send, edit,
 * delete, react or forward.
 */
class GroupChatCabinetViewer(
    private val repository: ChatRepository,
    private val hooks: CabinetChatHooks = CabinetChatHooks(),
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : CabinetViewer {

    override val identifier: String = IDENTIFIER
    override val expands: Boolean = true

    override suspend fun entries(): List<CabinetEntry> =
        (repository.cabinetRooms() as? ZillitResult.Success)?.data
            .orEmpty()
            .map { CabinetEntry(key = it.id, name = it.displayName) }

    @Composable
    override fun Content(entry: CabinetEntry?, modifier: Modifier) {
        if (entry == null) {
            Notice(str(S.desktop_cabinet_chat_no_messages), modifier)
            return
        }
        val pages = remember(entry.key) { CabinetRoomPages(repository, entry.key, clock) }
        LaunchedEffect(pages) { pages.loadFirst() }
        val scope = rememberCoroutineScope()
        CompositionLocalProvider(LocalChatSeams provides hooks.toSeams()) {
            when {
                pages.loading -> Notice(str(S.desktop_chat_loading_conversation), modifier)
                pages.failed -> Failed(modifier) { scope.launch { pages.loadFirst() } }
                pages.messages.isEmpty() -> Notice(str(S.desktop_cabinet_chat_no_messages), modifier)
                else -> ReadOnlyThread(
                    messages = pages.messages,
                    modifier = modifier,
                    resolveName = hooks.resolveName,
                    resolveMention = hooks.resolveName,
                    loadAvatar = hooks.loadAvatar,
                    loadThumbnail = hooks.loadThumbnail,
                    onOpenAttachment = hooks.onOpenAttachment,
                    player = hooks.player,
                    loadAudio = hooks.loadAudio,
                    hasOlder = pages.hasOlder,
                    loadingOlder = pages.loadingOlder,
                    onShowOlder = { scope.launch { pages.loadOlder() } },
                )
            }
        }
    }

    companion object {
        /** The cabinet row this serves — the web's `commonUnits[].identifier`. */
        const val IDENTIFIER = "group_chat"
    }
}

private fun CabinetChatHooks.toSeams() = ChatSeams(
    canDownload = canDownload,
    loadFullImage = loadFullImage,
    loadDocument = loadDocument,
    videoUrl = videoUrl,
    onOpenUrl = onOpenUrl,
)

/** A line of words centred in the viewer: loading, or nothing to show. */
@Composable
private fun Notice(text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

@Composable
private fun Failed(modifier: Modifier, onRetry: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = str(S.dm_failed_to_load),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
            )
            ZillitButton(
                text = str(S.retry),
                onClick = onRetry,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
    }
}

/**
 * One room's archive, paged backwards by time.
 *
 * The web asks once, from now. This asks again from the oldest line it holds
 * for as long as a page brings lines it has not seen — the server's window
 * size is not on the wire, so "more" is only known by trying, and a page of
 * nothing new retires the button.
 */
internal class CabinetRoomPages(
    private val repository: ChatRepository,
    private val roomId: String,
    private val clock: () -> Long,
) {
    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set
    var hasOlder by mutableStateOf(false)
        private set
    var loadingOlder by mutableStateOf(false)
        private set

    suspend fun loadFirst() {
        loading = true
        failed = false
        when (val page = repository.cabinetHistory(roomId, clock())) {
            is ZillitResult.Success -> {
                messages = page.data
                hasOlder = page.data.isNotEmpty()
            }
            is ZillitResult.Failure -> failed = true
        }
        loading = false
    }

    suspend fun loadOlder() {
        val oldest = messages.minOfOrNull(ChatMessage::pageStamp) ?: return
        if (loadingOlder) return
        loadingOlder = true
        val page = (repository.cabinetHistory(roomId, oldest) as? ZillitResult.Success)?.data.orEmpty()
        val known = messages.mapTo(HashSet()) { it.id }
        val fresh = page.filter { it.id !in known }
        messages = (fresh + messages).sortedBy(ChatMessage::timestampMillis)
        hasOlder = fresh.isNotEmpty()
        loadingOlder = false
    }
}
