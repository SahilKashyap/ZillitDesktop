package com.zillit.desktop.feature.chat.ui

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.sync.NewOperation
import com.zillit.desktop.core.sync.OfflineSupport
import com.zillit.desktop.core.sync.SyncState
import com.zillit.desktop.core.locationpicker.PickedLocation
import com.zillit.desktop.core.locationpicker.oneLine
import com.zillit.desktop.feature.chat.data.ChatSilence
import com.zillit.desktop.feature.chat.data.CHAT_SEND_KIND
import com.zillit.desktop.feature.chat.data.LOCATION_KIND
import com.zillit.desktop.feature.chat.data.PresenceSource
import com.zillit.desktop.feature.chat.data.QueuedChatSend
import com.zillit.desktop.feature.chat.data.toQueuedBubble
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import com.zillit.desktop.core.media.PreviewResult
import com.zillit.desktop.feature.chat.data.ChatRepository
import com.zillit.desktop.feature.chat.data.ReplyAwareChatRepository
import com.zillit.desktop.feature.chat.domain.ChatLocation
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.ChatReplyRef
import com.zillit.desktop.feature.chat.domain.ChatSendState
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.ChatVoice
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.domain.ChatComposerRules
import com.zillit.desktop.feature.chat.domain.ChatPick
import com.zillit.desktop.feature.chat.domain.ChatTranslator
import com.zillit.desktop.feature.chat.domain.ForwardTarget
import com.zillit.desktop.feature.chat.domain.ReadByReport
import com.zillit.desktop.feature.chat.domain.PendingChatUpload
import com.zillit.desktop.feature.chat.domain.liveChatUnread
import com.zillit.desktop.feature.chat.domain.sortedRecents

/**
 * One conversation's newest line, for its row: the words (or what the
 * file/place reads as) and who wrote them, so the row can say "You:" or
 * lead with the writer's name in a room — the phones' listing line.
 */
data class ChatPreview(val text: String, val senderId: String = "")

data class ChatUiState(
    /** Who the open thread is with; null shows the contact card instead. */
    val peer: CrewContact? = null,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val isLoading: Boolean = false,
    /** Peer ids with an existing thread — the Chats tab's rows. */
    val recents: List<String> = emptyList(),
    /** The open peer is writing right now. */
    val peerTyping: Boolean = false,
    /**
     * The open 1:1 peer's device is online right now — the header's green dot.
     * Read from the same Firebase node iOS and web watch; always false for
     * groups and for crew with no registered device.
     */
    val peerOnline: Boolean = false,
    /** Seconds on the open microphone; null when not recording. */
    val recordingSeconds: Int? = null,
    /** The open thread is a group room; sends ride `group_chat`. */
    val peerIsGroup: Boolean = false,
    /** The production's rooms, above the direct threads. */
    val groups: List<GroupRoom> = emptyList(),
    /** The newest line per conversation — the recents list's previews. */
    val previews: Map<String, ChatPreview> = emptyMap(),
    /** Unread badge per peer, absent when zero. */
    val unread: Map<String, Int> = emptyMap(),
    /** Newest activity per conversation, for the listing's time column. */
    val activity: Map<String, Long> = emptyMap(),
    /** Starred conversations, newest concern first in the Favourites filter. */
    val favourites: Set<String> = emptySet(),
    /**
     * Upload percent per in-flight attachment, keyed by the bubble's unique
     * id. -1 while the file is prepared (posters, PDF pages), 0..100 as the
     * bytes move; absent once the message is on the wire.
     */
    val uploads: Map<String, Int> = emptyMap(),
    /**
     * The badge service's own split of this area — what the mobile clients
     * draw on the Chats and Calls tabs: `chat_label` (member + group chats)
     * and `call_label` (missed calls). Keyed by the wire's tool label.
     */
    val sectionBadges: Map<String, Int> = emptyMap(),
    /** Keys the notification backlog filed as rooms — see [liveChatUnread]. */
    val ledgerRooms: Set<String> = emptySet(),
    /**
     * The picked (or pasted) file between the picker and the send — what the
     * preview dialog shows. Nothing uploads until the dialog says Send.
     */
    val pendingPreview: PendingChatUpload? = null,
    /** The message the composer is quoting; null writes a plain line. */
    val replyTo: ChatMessage? = null,
    /** The server may hold messages older than the loaded window. */
    val hasOlder: Boolean = false,
    /** A page of older messages is on its way. */
    val loadingOlder: Boolean = false,
    val error: String? = null,
    /**
     * A quiet confirmation — "Forwarded", "Message translated" — the web's
     * `message.success`, shown as a toast and gone on its own.
     */
    val info: String? = null,
    /** The message open in the Edit dialog, and the words as they are being rewritten. */
    val editing: ChatMessage? = null,
    val editDraft: String = "",
    /** The message the Forward picker is open for; null keeps it closed. */
    val forwarding: ChatMessage? = null,
    /** The readers panel — which message, and its answer as it arrives. */
    val readBy: ReadByView? = null,
    /**
     * Machine translations by server id, shown under the original words.
     * Local to this screen, as the web keeps its `isTranslated` copies in
     * its store: nothing is sent, and a reopened thread starts untranslated.
     */
    val translations: Map<String, String> = emptyMap(),
    /** Ids whose translation is on its way — the menu item says so. */
    val translating: Set<String> = emptySet(),
    /**
     * Whether the menu offers Translate at all: a translator is wired and
     * the production's language differs from this computer's (ZL-16953).
     */
    val translateOffered: Boolean = false,
) {
    /**
     * Unread across the conversations the user can still open — the Chats
     * tab and the rail's C&C count. Not [sectionBadges]' `chat_label`: the
     * server's ledger keeps rows for rooms the user lost, which the phones
     * clear locally and never display (see [liveChatUnread]).
     */
    val chatsBadge: Int get() = liveChatUnread(groups, ledgerRooms, unread)

    /** Missed calls — the Calls tab. */
    val callsBadge: Int get() = sectionBadges["call_label"] ?: 0

    val canSend: Boolean get() = draft.isNotBlank() && peer != null
}

/**
 * The readers panel's state: the message asked about, and the server's
 * answer once it lands — or why it did not.
 */
data class ReadByView(
    val message: ChatMessage,
    val report: ReadByReport? = null,
    val error: String? = null,
) {
    val isLoading: Boolean get() = report == null && error == null
}

sealed interface ChatEvent {
    data class OpenThread(val contact: CrewContact) : ChatEvent
    data object CloseThread : ChatEvent
    data class DraftChanged(val text: String) : ChatEvent
    data object Send : ChatEvent
    data class Arrived(val message: ChatMessage) : ChatEvent
    data object DismissError : ChatEvent

    /** The Chats tab asking for the thread list. */
    data object RefreshRecents : ChatEvent

    /**
     * A different production is open. Everything on screen belonged to the
     * last one — conversations, threads, badges, stars — so it all goes.
     */
    data object ProjectChanged : ChatEvent

    /** Delivered by the socket: the open peer started or stopped writing. */
    data class PeerTyping(val peerId: String, val started: Boolean) : ChatEvent

    /** The other end reports how far our messages to them have got. */
    data class Receipt(val peerId: String, val state: ChatSendState) : ChatEvent

    /** A room row: the same thread machinery, group-flavoured. */
    data class OpenGroup(val room: GroupRoom) : ChatEvent

    /** The paperclip: pick a file and open the preview over the thread. */
    data object AttachFile : ChatEvent

    /**
     * The attach sheet's answer — Photo, Video, Document or Audio — as the
     * phones' `PickerDialog` items and iOS's action sheet offer them. The
     * kind filters the OS dialog and is checked again after the choice.
     */
    data class AttachKind(val kind: PreviewKind) : ChatEvent

    /**
     * The composer's pin, after the shared map picker answered: send this
     * place as a `message_type: "location"` message.
     *
     * The picked value arrives with the event rather than being fetched by
     * this class, because the picker is a composition-local service installed
     * at the app root (`core:locationpicker`'s `LocalLocationPicker`) — the
     * composer is where it is in scope. Cancelling the picker sends nothing:
     * no event is raised at all.
     */
    data class ShareLocation(val place: PickedLocation) : ChatEvent

    /** Cmd+V with a picture on the clipboard: preview it like a picked file. */
    class ImagePasted(val name: String, val contentType: String, val bytes: ByteArray) : ChatEvent

    /** The preview dialog's Send: the (possibly edited) file plus its caption. */
    data class PreviewSend(val result: PreviewResult, val caption: String) : ChatEvent

    /** The preview dialog dismissed — the pick is dropped, nothing uploads. */
    data object PreviewCancelled : ChatEvent

    /** The menu's Reply: quote this message in the composer. */
    data class StartReply(val messageId: String) : ChatEvent

    /** The reply bar's X: back to a plain line. */
    data object CancelReply : ChatEvent

    /** The quiet button atop the thread: fetch the page before the oldest loaded. */
    data object ShowOlder : ChatEvent

    /** Withdraws one of our own messages, for everyone in the thread. */
    data class Delete(val messageId: String) : ChatEvent

    /** The server says these ids are gone — ours from another device, or theirs. */
    data class Deleted(val messageIds: List<String>) : ChatEvent
    /** Sets, switches or (same emoji again) removes our reaction. */
    data class React(val messageId: String, val emoji: String) : ChatEvent
    data object StartRecording : ChatEvent
    data object StopRecording : ChatEvent
    data object CancelRecording : ChatEvent

    /** The row's star: keep this conversation in the Favourites filter. */
    data class ToggleFavourite(val id: String) : ChatEvent

    /** The menu's Edit on one of our own lines: open the dialog on its words. */
    data class StartEdit(val messageId: String) : ChatEvent
    data class EditDraftChanged(val text: String) : ChatEvent
    data object SubmitEdit : ChatEvent
    data object CancelEdit : ChatEvent

    /** The socket says a line's words changed — ours from another device, or theirs. */
    data class Edited(val message: ChatMessage) : ChatEvent

    /** The menu's Forward: open the destination picker on this message. */
    data class StartForward(val messageId: String) : ChatEvent

    /** The picker's Send: a copy to each destination. */
    data class ForwardTo(val targets: List<ForwardTarget>) : ChatEvent
    data object CancelForward : ChatEvent

    /** The menu's Read by, on a room's line. */
    data class ShowReadBy(val messageId: String) : ChatEvent
    data object DismissReadBy : ChatEvent

    /** The menu's Translate on someone's words; Show original takes it back. */
    data class Translate(val messageId: String) : ChatEvent
    data class ShowOriginal(val messageId: String) : ChatEvent

    /**
     * The Image Reply dialog's Post: someone's picture drawn on, sent back
     * as a new picture with a caption — a plain media send, as the web's
     * `ImageReplyHandler` sends it (not a threaded reply).
     */
    class ImageReply(
        val name: String,
        val contentType: String,
        val bytes: ByteArray,
        val caption: String,
    ) : ChatEvent

    data object DismissInfo : ChatEvent

    /** A host seam said no — the sentence goes where every other refusal goes. */
    data class Refused(val message: String) : ChatEvent

    /**
     * The Calls tab came on screen. Missed calls are read by looking at the
     * log — iOS's `readCNCMessage(.misscall)`, a `notification:read` on the
     * `call_label` segment — and the tab's count falls with them.
     */
    data object CallsViewed : ChatEvent
}

/**
 * One open DM thread. History loads when a thread opens; sends are optimistic
 * with the bubble flipping to Failed rather than vanishing; arrivals for the
 * open peer append, everything else is ignored until a recents list exists.
 */
// One function per chat event; the set is the surface. LargeClass follows for
// the same reason: the events ARE chat's surface (threads, sends, media,
// replies, paging, offline), and each handler is already extracted and small.
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
class ChatViewModel(
    private val repository: ChatRepository,
    private val nowMillis: () -> Long,
    private val newUniqueId: () -> String,
    /** Opens the OS picker; the upload itself runs after the preview's Send. */
    private val pickAttachment: suspend () -> ChatPick = { ChatPick.Cancelled },
    /**
     * The same, filtered to one kind from the attach sheet. Defaults to the
     * untyped picker so a host (or test) that wires only that one still works.
     */
    private val pickAttachmentOf: suspend (PreviewKind) -> ChatPick = { pickAttachment() },
    /**
     * The host's routed uploader for bytes that never saw the picker — a
     * pasted image. The picker's own files carry their uploader inside
     * [PendingChatUpload]; this seam serves the clipboard path.
     */
    private val uploadMedia: suspend (
        name: String,
        contentType: String,
        bytes: ByteArray,
        onProgress: (Int) -> Unit,
    ) -> ChatAttachment? = { _, _, _, _ -> null },
    /**
     * A picture of a shared place — Google Static Maps, the same image the
     * boards already post. The phones snapshot their own map and upload it
     * beside the location (`mapView/MapsActivity.kt:205-224`), and their
     * bubbles draw that picture; a location sent without one arrives on
     * Android and the web as an empty image frame. Null bytes are fine —
     * the place still sends, with the pin card the receivers fall back to.
     */
    private val staticMap: suspend (lat: Double, lng: Double) -> ByteArray? = { _, _ -> null },
    /** The microphone, already wired to the uploader; null hides the mic. */
    private val voice: ChatVoice? = null,
    /** The starred set's home between sessions; hosts wire preferences. */
    private val loadFavourites: suspend () -> Set<String> = { emptySet() },
    private val saveFavourites: suspend (Set<String>) -> Unit = {},
    /**
     * Told after a thread's read marks land. Hosts hang badge refreshes here:
     * the server does not echo a badge event back for one's own reads, so
     * whoever draws counts must ask again themselves.
     */
    private val onThreadRead: suspend (String) -> Unit = {},
    /**
     * This area's counts by tool (`chat_label`, `call_label`) — the badge
     * service's answer to `?section=cnc_label&group=tool`. Hosts wire it;
     * empty leaves the tabs bare.
     */
    private val sectionBadges: suspend () -> Map<String, Int>? = { emptyMap() },
    /** Reads the missed-call badge — the Calls tab was opened. Hosts wire the emit. */
    private val onCallsViewed: suspend () -> Unit = {},
    /**
     * With this wired, a message written with no network is kept on this
     * computer and sent when it is back — the clock the phones show. Null
     * keeps sends live-only, as before.
     */
    private val offline: OfflineSupport? = null,
    /**
     * The green-dot feed. Null — no Firebase configuration, or a host that
     * does not care — leaves headers exactly as they were.
     */
    private val presence: PresenceSource? = null,
    /** The production the presence node is scoped by; null while none is open. */
    private val presenceProjectId: () -> String? = { null },
    /**
     * The signed-in user is a production admin — the web's `is_admin`, which
     * lifts the two-hour clock on Edit and Delete (see
     * [ChatComposerRules.canRewrite]).
     */
    private val isAdmin: () -> Boolean = { false },
    /** Machine translation for the menu's Translate; null leaves the item out. */
    private val translator: ChatTranslator? = null,
) : ZillitViewModel<ChatUiState, ChatEvent, Nothing>(ChatUiState()) {

    /**
     * One watcher, replaced on every thread open — iOS keeps exactly one RTDB
     * observer the same way. Cancelling on switch is what stops a burst of
     * chat-hopping from accumulating a poller per visited thread.
     */
    private var presenceJob: kotlinx.coroutines.Job? = null

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The media that has not reached the wire, by unique id.
     *
     * Declared ABOVE `init` and not beside its own functions, because
     * property initialisers run in source order: `init` collects
     * `support.online`, a StateFlow that replays its current value into a
     * `Dispatchers.Main.immediate` collector — synchronously, during
     * construction. With the declaration below `init`, that first emission
     * reached [retryUnsentMedia] while this map was still null and the app
     * died on the AWT thread before drawing a frame (seen live,
     * 2026-08-25: `NullPointerException … "$this$filterValues$iv" is null`).
     */
    private val unsentMedia = linkedMapOf<String, UnsentMedia>()

    init {
        launch {
            val stars = loadFavourites()
            if (stars.isNotEmpty()) setState { copy(favourites = stars) }
        }
        // The join rides every connection, not just the first: startup can win
        // the race against the socket, and a reconnect drops the server-side
        // room — either way the user simply stops receiving.
        launch {
            repository.connections.collect {
                repository.join()
                onEvent(ChatEvent.RefreshRecents)
            }
        }
        launch { repository.incoming.collect { onEvent(ChatEvent.Arrived(it)) } }
        // A queued message's clock becomes a tick (or a failure mark) as the
        // outbox moves it, without waiting for the thread to be reopened.
        offline?.let { support -> launch { support.engine.status.collect { refreshQueued() } } }
        // Failed media re-sends itself the moment connectivity returns —
        // media bytes cannot ride the outbox (see [unsentMedia]), so the
        // online edge is their retry trigger the way the engine's status
        // stream is the text outbox's.
        offline?.let { support ->
            launch { support.online.collect { up -> if (up) retryUnsentMedia() } }
        }
        // A thread read on another of this user's devices: drop the row's
        // count here too, and let the badge store re-ask the server.
        launch {
            repository.selfReads.collect { conversationId ->
                repository.markThreadRead(conversationId, nowMillis())
                serverUnread.remove(conversationId)
                setState { copy(unread = unread - conversationId) }
                onThreadRead(conversationId)
                refreshSectionBadges()
            }
        }
        // `notification:silent`'s chat half — a room lost, messages deleted —
        // applied here as the phones apply it to their ledgers, then the seed
        // re-read so the server's copy of those rows cannot put them back.
        launch {
            repository.silenced.collect { silence ->
                repository.silence(silence)
                silence.rooms.forEach(serverUnread::remove)
                setState { copy(unread = unread - silence.rooms) }
                reloadBacklog()
                refreshSectionBadges()
            }
        }
        // The host's ledger seeded or moved under the listing: fold its rows
        // again, or a badge that landed after the listing loaded never shows.
        launch { repository.backlogChanges.collect { reloadBacklog() } }
        launch {
            repository.typing.collect { (peer, started) ->
                onEvent(ChatEvent.PeerTyping(peer, started))
            }
        }
        launch { repository.deletions.collect { onEvent(ChatEvent.Deleted(it)) } }
        // An edit is not a new line: the bubble's words change in place.
        // (This flow existed on the repository for a while with nobody
        // listening — an edit made on the phone never reached the screen.)
        launch { repository.edits.collect { onEvent(ChatEvent.Edited(it)) } }
        // A group made, renamed or left somewhere else. The listing is the
        // whole of what changes here, so it is simply re-read — the same
        // fetch a reconnect does.
        launch { repository.roomChanges.collect { onEvent(ChatEvent.RefreshRecents) } }
        launch {
            repository.receipts.collect { receipt ->
                onEvent(ChatEvent.Receipt(receipt.peerId, receipt.state))
            }
        }
    }

    // Exhaustive dispatch over the sealed event set — branch count is the
    // pattern, not a complexity smell (see HomeFeedViewModel's onEvent).
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.OpenThread -> openThread(event.contact)
            is ChatEvent.OpenGroup -> openThread(
                CrewContact(userId = event.room.id, fullName = event.room.name),
                isGroup = true,
            )
            ChatEvent.CloseThread -> {
                stopPresence()
                setState { copy(peer = null, messages = emptyList(), peerOnline = false) }
            }
            is ChatEvent.DraftChanged -> {
                // The transition is the signal: empty→writing says "start",
                // writing→empty says "end". Every keystroke would be noise.
                val was = currentState.draft.isNotBlank()
                val now = event.text.isNotBlank()
                setState { copy(draft = event.text) }
                val peer = currentState.peer
                if (peer != null && was != now) {
                    val group = currentState.peerIsGroup
                    launch { repository.sendTyping(peer.userId, started = now, isGroup = group) }
                }
            }
            ChatEvent.Send -> send()
            is ChatEvent.ToggleFavourite -> toggleFavourite(event.id)
            ChatEvent.CallsViewed -> if (currentState.callsBadge > 0) {
                launch {
                    onCallsViewed()
                    applySplit(sectionBadges())
                }
            }
            ChatEvent.AttachFile -> launch { pickForPreview() }
            is ChatEvent.AttachKind -> launch { pickForPreview(event.kind) }
            is ChatEvent.ShareLocation -> shareLocation(event.place)
            is ChatEvent.ImagePasted -> imagePasted(event)
            is ChatEvent.PreviewSend -> sendMedia(event.result, event.caption)
            ChatEvent.PreviewCancelled -> setState { copy(pendingPreview = null) }
            is ChatEvent.StartReply -> setState {
                copy(replyTo = messages.firstOrNull { it.id == event.messageId })
            }
            ChatEvent.CancelReply -> setState { copy(replyTo = null) }
            ChatEvent.ShowOlder -> loadOlder()
            is ChatEvent.Delete -> deleteMessage(event.messageId)
            is ChatEvent.Deleted -> dropDeleted(event.messageIds)
            is ChatEvent.React -> react(event.messageId, event.emoji)
            is ChatEvent.StartEdit -> startEdit(event.messageId)
            is ChatEvent.EditDraftChanged -> setState { copy(editDraft = event.text) }
            ChatEvent.SubmitEdit -> submitEdit()
            ChatEvent.CancelEdit -> setState { copy(editing = null, editDraft = "") }
            is ChatEvent.Edited -> applyEdit(event.message)
            is ChatEvent.StartForward -> setState {
                copy(forwarding = messages.firstOrNull { it.id == event.messageId })
            }
            is ChatEvent.ForwardTo -> forward(event.targets)
            ChatEvent.CancelForward -> setState { copy(forwarding = null) }
            is ChatEvent.ShowReadBy -> showReadBy(event.messageId)
            ChatEvent.DismissReadBy -> setState { copy(readBy = null) }
            is ChatEvent.Translate -> translate(event.messageId)
            is ChatEvent.ShowOriginal -> setState { copy(translations = translations - event.messageId) }
            is ChatEvent.ImageReply -> imageReply(event)
            ChatEvent.DismissInfo -> setState { copy(info = null) }
            is ChatEvent.Refused -> setState { copy(error = event.message) }
            ChatEvent.StartRecording -> startRecording()
            ChatEvent.StopRecording -> finishRecording(discard = false)
            ChatEvent.CancelRecording -> finishRecording(discard = true)
            is ChatEvent.Arrived -> arrived(event.message)
            ChatEvent.DismissError -> setState { copy(error = null) }
            is ChatEvent.Receipt -> applyReceipt(event.peerId, event.state)
            is ChatEvent.PeerTyping ->
                if (currentState.peer?.userId == event.peerId) {
                    setState { copy(peerTyping = event.started) }
                }
            ChatEvent.ProjectChanged -> startFreshProject()
            ChatEvent.RefreshRecents -> {
                refreshSectionBadges()
                // Re-read on every refresh: the production's language lands
                // with its context, which may be after this model was built.
                setState { copy(translateOffered = translator?.isOffered() == true) }
                launchResult(
                    block = { repository.rooms() },
                    onSuccess = { rooms ->
                        setState { copy(groups = rooms) }
                        // The rooms' own newest lines, now that their ids are known.
                        setState { copy(previews = previews + previewsFor(emptyList())) }
                    },
                    onError = { },
                )
                // The per-conversation counts and stamps, from the server's
                // backlog. A failed fetch keeps whatever the cache can say —
                // no toast.
                reloadBacklog()
                launchResult(
                    block = { repository.recentPeers() },
                    onSuccess = { ids ->
                        showRecents(ids)
                        launch { rememberRecents() }
                    },
                    // The list comes over the socket, so with no network it
                    // never answers: show the last list this production had,
                    // plus every thread kept on this computer — no toast.
                    onError = { launch { showRecents(rememberedRecents()) } },
                )
            }
        }
    }

    /**
     * A cached thread proves a conversation even when the server's list
     * misses it — the union is the truth. So does a conversation the server
     * has stamped: a thread never opened here is still a row.
     */
    private fun showRecents(ids: List<String>) {
        val activity = knownActivity()
        val ordered = sortedRecents((ids + activity.keys).distinct(), activity)
        setState {
            copy(
                recents = ordered,
                previews = previewsFor(ordered),
                unread = combinedUnread(repository.unreadCounts(serverActivity, backlogWindowStart)),
                activity = activity,
            )
        }
    }

    /**
     * Keeps the listing for when the socket cannot answer: the ids on the
     * shelf and the server's stamps for them, so an offline open sorts the
     * way the last online one did rather than by whatever threads happen to
     * be cached here.
     */
    private suspend fun rememberRecents() {
        val support = offline ?: return
        val scope = support.currentScope() ?: return
        val kept = CachedRecents(ids = currentState.recents, activity = serverActivity.toMap())
        support.cache.put(scope, RECENTS_CACHE, json.encodeToString(CachedRecents.serializer(), kept), nowMillis())
    }

    /** The kept listing; its stamps re-enter [serverActivity] before the ids are shown. */
    private suspend fun rememberedRecents(): List<String> {
        val support = offline ?: return emptyList()
        val scope = support.currentScope() ?: return emptyList()
        val kept = support.cache.get(scope, RECENTS_CACHE) ?: return emptyList()
        val restored = runCatching { json.decodeFromString(CachedRecents.serializer(), kept.json) }
            // The cache once held a bare id list; a build that wrote that shape
            // must still read on this one.
            .getOrElse {
                runCatching { json.decodeFromString(ListSerializer(String.serializer()), kept.json) }
                    .map { ids -> CachedRecents(ids = ids) }
                    .getOrDefault(CachedRecents())
            }
        learnActivity(restored.activity)
        return restored.ids
    }

    /**
     * When each conversation last moved, by every account this desktop has:
     * the local cache's newest line, the server's newest notification, and
     * live arrivals — the largest wins. Android's `sorting_activity`
     * (`MembersVM.kt:150`, sorted at `:372-374`) is the newest message
     * `created` whether or not the thread was ever opened; the cache alone
     * knows only threads opened here, which left a message that arrived
     * while the app was closed badged but low.
     */
    private fun knownActivity(): Map<String, Long> {
        val local = repository.newestActivity()
        return (local.keys + serverActivity.keys).associateWith { key ->
            maxOf(local[key] ?: 0L, serverActivity[key] ?: 0L)
        }
    }

    /** Takes the newer stamp per conversation; an older word never moves a row down. */
    private fun learnActivity(stamps: Map<String, Long>) {
        stamps.forEach { (key, at) ->
            if (at > (serverActivity[key] ?: 0L)) serverActivity[key] = at
        }
    }

    /** Re-asks the badge service for this area's split — after anything moves. */
    private fun refreshSectionBadges() {
        launch { applySplit(sectionBadges()) }
    }

    /** A null answer is a failed ask — the last split stands, as the rail's does. */
    private fun applySplit(split: Map<String, Int>?) {
        split?.let { fresh -> setState { copy(sectionBadges = fresh) } }
    }

    /** The watcher has three exits — replace, close, project switch — and one off switch. */
    private fun stopPresence() {
        presenceJob?.cancel()
        presenceJob = null
    }

    /**
     * Follows the open peer's `devices/{deviceId}/{projectId}` node while the
     * thread is on screen. Groups have no single device to be online, and a
     * peer with no registered device simply never gets the dot.
     */
    private fun watchPresence(contact: CrewContact, isGroup: Boolean) {
        stopPresence()

        // Each exit says which one it took. A header with no dot has five
        // possible causes and they are indistinguishable on screen; that
        // ambiguity has already cost this feature a debugging round.
        val source = presence ?: run {
            ZillitLog.d(PRESENCE_TAG) { "no presence source wired" }
            return
        }
        if (isGroup) return
        val deviceId = contact.deviceId ?: run {
            ZillitLog.d(PRESENCE_TAG) { "peer has no registered device; no dot possible" }
            return
        }
        val projectId = presenceProjectId() ?: run {
            ZillitLog.d(PRESENCE_TAG) { "no project resolved yet; opening again will retry" }
            return
        }

        presenceJob = launch {
            ZillitLog.d(PRESENCE_TAG) { "watching device $deviceId on $projectId" }
            source.watch(deviceId, projectId).collect { online ->
                ZillitLog.d(PRESENCE_TAG) { "device $deviceId online=$online" }
                // Guarded by peer, not by job identity: a stale collection's
                // last emission must not paint the next thread's header.
                setState { if (peer?.userId == contact.userId) copy(peerOnline = online) else this }
            }
        }
    }

    private fun openThread(contact: CrewContact, isGroup: Boolean = false) {
        // The session cache answers instantly; the fetch refreshes behind it.
        val known = repository.cached(contact.userId)
        setState {
            copy(
                peer = contact,
                peerIsGroup = isGroup,
                messages = known.orEmpty(),
                isLoading = known == null,
                peerTyping = false,
                peerOnline = false,
                replyTo = null,
                pendingPreview = null,
                hasOlder = false,
                loadingOlder = false,
                error = null,
            )
        }
        // Media that never left this machine belongs in the thread even
        // before history answers — the cache knows nothing of it.
        mergeUnsentMedia(contact.userId)
        // Re-join on every open: the init-time join can predate the socket
        // connecting, and a join is idempotent while a missed one costs
        // delivery for the whole session.
        launch { repository.join() }
        watchPresence(contact, isGroup)
        launchResult(
            block = { repository.history(contact.userId, nowMillis(), isGroup) },
            onSuccess = { rows ->
                setState {
                    if (peer?.userId == contact.userId) {
                        // A late history answer must not wipe bubbles still in
                        // flight — an upload started after the fetch went out
                        // exists only locally until its send is acknowledged.
                        val inFlight = messages.filter { local ->
                            local.isMine && local.sendState != ChatSendState.Sent &&
                                rows.none { it.uniqueId == local.uniqueId }
                        }
                        // The "Show older" pager's cue (QA #7/#8). This used
                        // to be "a full page of 50", which is an assumption
                        // about the server's window size that nothing on the
                        // wire confirms — and when the window is smaller, the
                        // pager never appears and everything older than the
                        // first page is unreachable. The web assumes nothing:
                        // it offers older until a page comes back empty. Same
                        // here — anything past a lone row may have history,
                        // and the older-page fetch retires the button itself.
                        copy(
                            isLoading = false,
                            messages = rows + inFlight,
                            hasOlder = rows.size > 1,
                        )
                    } else {
                        this
                    }
                }
                // Messages written offline for this thread, still waiting.
                launch { refreshQueued() }
                mergeUnsentMedia(contact.userId)
                // The sender learns their words were seen the moment the
                // thread is on screen — Android's status 3 on the newest row.
                // Groups emit their own read-untill (room_id + my user id):
                // it is what clears the room's badge server-side, and a
                // client that keeps only a local mark leaves the count
                // standing forever on every device.
                rows.lastOrNull { !it.isMine }?.let { newest ->
                    launch { repository.markRead(contact.userId, newest.id, isGroup) }
                }
                // Opening reads everything the thread holds.
                rows.maxOfOrNull(ChatMessage::timestampMillis)?.let {
                    repository.markThreadRead(contact.userId, it)
                }
                serverUnread.remove(contact.userId)
                setState { copy(unread = unread - contact.userId) }
                launch {
                    onThreadRead(contact.userId)
                    // The host's refetch waited for the server; the tab split
                    // asks now for the same reason.
                    applySplit(sectionBadges())
                }
            },
            onError = { error ->
                setState { copy(isLoading = false, error = error.localised()) }
            },
        )
    }

    /**
     * The composer's pin, resolved.
     *
     * The body follows the phones': their picker offers a description field
     * that defaults to the place's address, and whatever it holds becomes the
     * message's encrypted body (`utils/MediaExtension.kt:308-322` builds the
     * item with `description = address`; `ChatAndGroupVM.kt:605-615` passes it
     * as `mMessage`). Here the composer's draft IS that description field — a
     * line already typed rides along as the label — and an empty one falls
     * back to the place's own name, then its address, so the bubble always
     * has something to say above the pin.
     */
    private fun shareLocation(place: PickedLocation) {
        // The label carries the WHOLE place — name and street — because the
        // server keeps only `lat`/`long` from the location object and drops
        // its `address` (seen live, 2026-08-25: a shared studio came back
        // from history as a name and two numbers). The encrypted body is the
        // one part of a location message that survives the round trip, so a
        // place shared here still reads as a place tomorrow. A typed caption
        // still wins — that is the sender's own word for where this is.
        val label = currentState.draft.trim()
            .ifBlank { place.oneLine().trim() }
            .ifBlank { place.address.trim() }
        val where = ChatLocation(address = place.address.trim(), lat = place.lat, lng = place.lng)

        // The picture is best-effort and must never hold the place hostage:
        // no key, no network, a refused upload — the location goes anyway,
        // exactly as it did before there was a picture at all.
        launch {
            val map = runCatching { staticMap(place.lat, place.lng) }.getOrNull()
            val picture = map?.let {
                runCatching { uploadMedia(LOCATION_MAP_NAME, LOCATION_MAP_TYPE, it) { } }.getOrNull()
            }
            send(attachment = picture, location = where, bodyOverride = label)
        }
    }

    private fun send(
        attachment: ChatAttachment? = null,
        location: ChatLocation? = null,
        /** The body when it does not come from the composer — a shared place's label. */
        bodyOverride: String? = null,
    ) {
        val peer = currentState.peer ?: return
        // The composer is gone for a left peer, but the guard holds anyway:
        // a keyboard shortcut or a stale event must not message someone who
        // is no longer on the production (Android's userActive gate).
        if (!currentState.peerIsGroup && peer.hasLeft) {
            setState { copy(error = str(S.desktop_chat_person_no_longer_on_project)) }
            return
        }
        val typed = currentState.draft.trim()
        val body = bodyOverride
            ?: typed.ifEmpty { if (attachment == null) return else "" }
        // Every send funnels through here — typed line, file caption, shared
        // place — so the web's 2000-character ceiling is stated once.
        if (ChatComposerRules.bodyTooLong(body)) {
            setState { copy(error = ChatComposerRules.BODY_TOO_LONG) }
            return
        }
        val reply = currentState.replyTo?.asReplyRef()
        val optimistic = appendOptimistic(peer, body, attachment, reply, location)
        setState { copy(draft = "", replyTo = null) }
        val isGroup = currentState.peerIsGroup

        // No network and no file to upload: straight to the outbox, no
        // round trip to fail first. A place has nothing to upload, so it
        // queues like words. (The outbox carries no reply reference — a reply
        // queued offline goes out as a plain line.)
        val support = offline
        if (support != null && support.isOffline && attachment == null) {
            launch { queue(support, peer.userId, body, optimistic, isGroup, location) }
        } else {
            launchResult(
                block = {
                    sendOnWire(peer.userId, body, optimistic, isGroup, attachment, reply, location)
                },
                onSuccess = { setSendState(optimistic.uniqueId, ChatSendState.Sent) },
                onError = { error ->
                    // The message never left this machine (socket down, no
                    // network): keep it and send it later rather than fail it.
                    if (support != null && attachment == null && error is ZillitError.NoConnection) {
                        launch { queue(support, peer.userId, body, optimistic, isGroup, location) }
                    } else {
                        setSendState(optimistic.uniqueId, ChatSendState.Failed)
                        setState { copy(error = error.localised()) }
                    }
                },
            )
        }
    }

    /**
     * One send, with the quote when there is one and the repository can carry
     * it. The capability is asked for with `as?` because [ChatRepository] and
     * its implementation are another session's files: until they adopt
     * [ReplyAwareChatRepository], a reply still sends — as a plain line whose
     * quote lives only on this screen's bubble.
     */
    @Suppress("LongParameterList") // One send: its addressee, its content, and how it is framed.
    private suspend fun sendOnWire(
        receiverId: String,
        body: String,
        optimistic: ChatMessage,
        isGroup: Boolean,
        attachment: ChatAttachment?,
        reply: ChatReplyRef?,
        location: ChatLocation?,
    ): ZillitResult<Unit> {
        val capable = repository as? ReplyAwareChatRepository
        return if (reply != null && capable != null) {
            capable.sendWithReply(
                receiverId, body, optimistic.uniqueId, optimistic.timestampMillis,
                isGroup, attachment, reply, location,
            )
        } else {
            repository.send(
                receiverId, body, optimistic.uniqueId, optimistic.timestampMillis,
                isGroup = isGroup,
                attachment = attachment,
                location = location,
            )
        }
    }

    /**
     * The parent as the wire's `Reply_chat` wants it — built from the quoted
     * message exactly as Android does (`ChatAndGroupVM.kt:451-459`): its
     * server id, sender, words, and what kind of thing it was.
     */
    private fun ChatMessage.asReplyRef(): ChatReplyRef = ChatReplyRef(
        messageId = id,
        // Our own optimistic rows carry the placeholder sender "me".
        senderId = if (isMine) repository.selfId() ?: senderId else senderId,
        body = body,
        // The parent's own `message_type`. Location wins over the attachment
        // for the same reason it does on the way out: a phone-sent place has
        // a map screenshot attached, and quoting it as "image" would draw the
        // wrong glyph on every client that reads the quote.
        kind = if (location != null) LOCATION_KIND else attachment?.kind ?: "text",
        attachmentName = attachment?.name.orEmpty(),
    )

    // -- offline: the outbox -----------------------------------------------------

    @Suppress("LongParameterList") // The queued payload's own fields, one each.
    private suspend fun queue(
        support: OfflineSupport,
        receiverId: String,
        body: String,
        optimistic: ChatMessage,
        isGroup: Boolean,
        location: ChatLocation? = null,
    ) {
        val payload = QueuedChatSend(
            receiverId = receiverId,
            body = body,
            uniqueId = optimistic.uniqueId,
            timestampMillis = optimistic.timestampMillis,
            isGroup = isGroup,
            location = location,
        )
        val enqueued = support.engine.enqueue(
            NewOperation(
                kind = CHAT_SEND_KIND,
                label = str(S.desktop_outbox_message_to, currentState.peer?.fullName?.ifBlank { null } ?: receiverId),
                payload = json.encodeToString(QueuedChatSend.serializer(), payload),
                // One thread's messages leave in the order they were written.
                groupKey = "chat:$receiverId",
                // The message's own id, so the bubble and the queue agree by construction.
                id = optimistic.uniqueId,
            ),
        )
        setSendState(optimistic.uniqueId, if (enqueued != null) ChatSendState.Queued else ChatSendState.Failed)
    }

    /**
     * The open thread's queued messages, from the outbox: those still there
     * wear the clock (or the failure mark), those the outbox has sent become
     * sent — unless the server's echo already replaced the bubble.
     */
    private suspend fun refreshQueued() {
        val support = offline ?: return
        val peerId = currentState.peer?.userId ?: return
        val operations = support.engine.operations().filter { it.kind == CHAT_SEND_KIND }
        val byId = operations.associateBy { it.id }
        setState {
            if (peer?.userId != peerId) return@setState this
            val known = messages.map { it.uniqueId }.toSet()
            val restored = operations.mapNotNull { it.toQueuedBubble(peerId, json) }.filter { it.uniqueId !in known }
            val followed = messages.map { message ->
                val op = byId[message.uniqueId] ?: return@map message
                val state = when (op.state) {
                    SyncState.Done -> ChatSendState.Sent
                    SyncState.Failed -> ChatSendState.Failed
                    SyncState.Pending, SyncState.InFlight -> ChatSendState.Queued
                }
                if (message.isMine && message.sendState.isOurs()) message.copy(sendState = state) else message
            }
            copy(messages = (followed + restored).sortedBy { it.timestampMillis })
        }
    }

    /** The bubble that appears before the server has spoken. */
    private fun appendOptimistic(
        peer: CrewContact,
        body: String,
        attachment: ChatAttachment?,
        replyTo: ChatReplyRef? = null,
        location: ChatLocation? = null,
    ): ChatMessage {
        val uniqueId = newUniqueId()
        val optimistic = ChatMessage(
            id = uniqueId,
            uniqueId = uniqueId,
            senderId = "me",
            receiverId = peer.userId,
            body = body,
            timestampMillis = nowMillis(),
            isMine = true,
            sendState = ChatSendState.Sending,
            attachment = attachment,
            location = location,
            replyTo = replyTo,
        )
        setState {
            // The listing follows the send, not the ack: the shelf reorders,
            // the preview shows what was just written, the clock reads now —
            // the behaviour every messenger trains. The repository's caches
            // catch up when the server acks (see ChatRepositoryImpl.send),
            // so the next wholesale recompute agrees rather than snapping
            // back to the peer's last line.
            val bumped = activity + (peer.userId to optimistic.timestampMillis)
            copy(
                messages = messages + optimistic,
                // The first message to someone makes them a conversation.
                recents = sortedRecents(withPeer(recents, peer.userId), bumped),
                activity = bumped,
                previews = withPreview(previews, peer.userId, optimistic),
            )
        }
        return optimistic
    }

    // -- media: preview, send, survive, retry ------------------------------------

    /**
     * One media message not yet accepted by the server, kept by the view
     * model rather than the screen: the bytes to (re)upload, the stored file
     * once storage has it, and the bubble to restore when its thread reopens.
     *
     * In-memory only, on purpose: the text outbox persists across restarts,
     * but media bytes do not go in the outbox DB — a restart drops an unsent
     * file, exactly as scoped for QA #9. What this store fixes is the smaller
     * betrayal: a failed upload vanishing just because the thread was closed
     * and reopened.
     */
    private class UnsentMedia(
        val peerId: String,
        val isGroup: Boolean,
        var message: ChatMessage,
        val bytes: ByteArray,
        val upload: suspend (ByteArray, (Int) -> Unit) -> ChatAttachment?,
        var stored: ChatAttachment? = null,
    )

    /** The paperclip: the pick goes to the preview, not straight to the wire. */
    private suspend fun pickForPreview(kind: PreviewKind? = null) {
        if (currentState.peer == null) return
        val pending = when (val pick = if (kind == null) pickAttachment() else pickAttachmentOf(kind)) {
            is ChatPick.Cancelled -> return
            // The picker weighed the file without reading it, as the web
            // weighs a File before uploading; its reason is the user's.
            is ChatPick.Refused -> {
                setState { copy(error = pick.reason) }
                return
            }

            is ChatPick.Ready -> pick.upload
        }
        // What the picker's own scales could not judge: an executable is
        // refused by name, whatever its size (`ChatFooterCnc.jsx:604-609`).
        val refusal = ChatComposerRules.refuse(pending.name, pending.bytes.size.toLong())
        if (refusal != null) {
            setState { copy(error = refusal) }
            return
        }
        ZillitLog.d(TAG) { "picked ${pending.name} for preview" }
        setState { copy(pendingPreview = pending) }
    }

    /** A pasted picture takes the picker's road, with the host uploader behind it. */
    private fun imagePasted(event: ChatEvent.ImagePasted) {
        if (currentState.peer == null) return
        // The clipboard can hold a bigger picture than any picker would pass.
        val refusal = ChatComposerRules.refuse(event.name, event.bytes.size.toLong())
        if (refusal != null) {
            setState { copy(error = refusal) }
            return
        }
        val pending = PendingChatUpload(event.name, event.contentType, event.bytes) { bytes, onProgress ->
            uploadMedia(event.name, event.contentType, bytes, onProgress)
        }
        setState { copy(pendingPreview = pending) }
    }

    /**
     * The preview's Send: bubble first, then bytes — the message rides the
     * socket only once the file is in storage, since sent earlier it would
     * name an object that does not exist yet. The caption is the message
     * body, as the phones' gallery viewer sends it.
     */
    private fun sendMedia(result: PreviewResult, caption: String) {
        val peer = currentState.peer ?: return
        val pending = currentState.pendingPreview ?: return
        // A caption is a body like any other; refuse it before the optimistic
        // bubble appears, since this path never reaches the guard in send().
        if (ChatComposerRules.bodyTooLong(caption.trim())) {
            setState { copy(error = ChatComposerRules.BODY_TOO_LONG) }
            return
        }
        setState { copy(pendingPreview = null) }
        val placeholder = ChatAttachment(media = "", name = result.name, contentType = result.contentType)
        val optimistic = appendOptimistic(peer, body = caption.trim(), attachment = placeholder)
        unsentMedia[optimistic.uniqueId] = UnsentMedia(
            peerId = peer.userId,
            isGroup = currentState.peerIsGroup,
            message = optimistic,
            bytes = result.bytes,
            upload = pending.upload,
        )
        launch { runMediaSend(optimistic.uniqueId) }
    }

    /** Upload (once) then send; on failure the entry stays for the next try. */
    private suspend fun runMediaSend(uniqueId: String) {
        val entry = unsentMedia[uniqueId] ?: return
        followMediaState(entry, ChatSendState.Sending)
        val stored = entry.stored ?: uploadEntry(entry, uniqueId) ?: return
        entry.stored = stored
        entry.message = entry.message.copy(attachment = stored)
        setState {
            copy(
                messages = messages.map {
                    if (it.uniqueId == uniqueId) it.copy(attachment = stored) else it
                },
            )
        }
        when (
            val sent = repository.send(
                entry.peerId, entry.message.body, uniqueId, entry.message.timestampMillis,
                isGroup = entry.isGroup,
                attachment = stored,
            )
        ) {
            is ZillitResult.Success -> {
                unsentMedia.remove(uniqueId)
                setSendState(uniqueId, ChatSendState.Sent)
            }
            is ZillitResult.Failure -> {
                markMediaUnsent(entry)
                setState { copy(error = sent.error.localised()) }
            }
        }
    }

    /** The bytes to storage, narrated by [ChatUiState.uploads]; null keeps the entry. */
    private suspend fun uploadEntry(entry: UnsentMedia, uniqueId: String): ChatAttachment? {
        setState { copy(uploads = uploads + (uniqueId to PREPARING)) }
        val stored = entry.upload(entry.bytes) { percent ->
            setState { copy(uploads = uploads + (uniqueId to percent)) }
        }
        setState { copy(uploads = uploads - uniqueId) }
        ZillitLog.d(TAG) { "media upload finished stored=${stored != null}" }
        if (stored == null) {
            markMediaUnsent(entry)
            setState { copy(error = str(S.desktop_could_not_upload_named, entry.message.attachment?.name)) }
        }
        return stored
    }

    /** The clock when the network is the problem (it retries alone), the mark when refused. */
    private fun markMediaUnsent(entry: UnsentMedia) {
        val state = if (offline?.isOffline == true) ChatSendState.Queued else ChatSendState.Failed
        followMediaState(entry, state)
    }

    /** Keeps the store's copy and the on-screen bubble saying the same thing. */
    private fun followMediaState(entry: UnsentMedia, state: ChatSendState) {
        entry.message = entry.message.copy(sendState = state)
        setSendState(entry.message.uniqueId, state)
    }

    /** Every entry still waiting or failed, sent again — the online-edge collector's job. */
    private fun retryUnsentMedia() {
        val again = unsentMedia.filterValues {
            it.message.sendState == ChatSendState.Failed || it.message.sendState == ChatSendState.Queued
        }.keys.toList()
        again.forEach { id -> launch { runMediaSend(id) } }
    }

    /**
     * Restores this peer's unsent media bubbles into the open thread —
     * `cached()` and history know nothing of a file that never reached the
     * server, so without this a Failed upload vanished on reopen (QA #9).
     */
    private fun mergeUnsentMedia(peerId: String) {
        if (unsentMedia.values.none { it.peerId == peerId }) return
        setState {
            if (peer?.userId != peerId) return@setState this
            val known = messages.map { it.uniqueId }.toSet()
            val restored = unsentMedia.values
                .filter { it.peerId == peerId && it.message.uniqueId !in known }
                .map { it.message }
            copy(messages = (messages + restored).sortedBy { it.timestampMillis })
        }
    }

    /**
     * The next page back — `history` pages from the oldest loaded stamp
     * (`/messages/{peer}/{ts}/previous`), and the pages MERGE: an arrival
     * during the fetch must not be replaced by the older window.
     */
    private fun loadOlder() {
        val peer = currentState.peer ?: return
        if (currentState.loadingOlder) return
        val oldest = currentState.messages
            .filter { it.timestampMillis > 0 }
            .minOfOrNull { it.timestampMillis } ?: return
        val isGroup = currentState.peerIsGroup
        setState { copy(loadingOlder = true) }
        launchResult(
            block = { repository.history(peer.userId, oldest, isGroup) },
            onSuccess = { page ->
                setState {
                    if (this.peer?.userId != peer.userId) {
                        copy(loadingOlder = false)
                    } else {
                        val merged = (page + messages)
                            .distinctBy { it.uniqueId }
                            .sortedBy { it.timestampMillis }
                        copy(
                            messages = merged,
                            loadingOlder = false,
                            // More behind it while a page still brings rows
                            // this thread had not seen. The server's window
                            // includes the boundary row, so the last page
                            // comes back holding only what was already here
                            // — that is the stop, not a page shorter than
                            // some assumed size. See the first-page note.
                            hasOlder = merged.size > messages.size,
                        )
                    }
                }
            },
            onError = { setState { copy(loadingOlder = false) } },
        )
    }

    /**
     * A fresh state rather than a targeted clear: every field here belongs to
     * the open production, and a list of what to wipe would rot the next time
     * one is added.
     */
    /**
     * The server's unread per conversation, seeded from the notification
     * backlog on every listing refresh and moved by arrivals and reads
     * between refreshes. The rows the local cache can count are only the
     * threads this desktop has opened; a peer who wrote while it was closed
     * has a badge on the phone and had none here until this.
     */
    private val serverUnread = mutableMapOf<String, Int>()

    /**
     * When each conversation last moved, by accounts other than the local
     * cache: the backlog's newest notification per conversation, and live
     * arrivals as they land. See [knownActivity].
     */
    private val serverActivity = mutableMapOf<String, Long>()

    /** Where the last backlog's window began; the cache counts nothing older. */
    private var backlogWindowStart: Long = 0L

    /**
     * Seeds the server's word on unread per conversation, and the listing's
     * order, from the notification backlog. Runs on every listing refresh and
     * after a silence, whose prunes the repository applies to the seed.
     */
    private fun reloadBacklog() {
        launchResult(
            block = { repository.conversationBacklog() },
            onSuccess = { backlog ->
                serverUnread.clear()
                // A thread open right now was just read; its rows in
                // the backlog predate that.
                val counts = backlog.unread
                serverUnread.putAll(currentState.peer?.userId?.let { counts - it } ?: counts)
                setState { copy(ledgerRooms = backlog.rooms) }
                backlog.windowStart?.let { backlogWindowStart = maxOf(backlogWindowStart, it) }
                // The one line that says where a badge came from: read it before
                // believing a count. Ids, not names, on purpose.
                ZillitLog.d(TAG) {
                    "backlog unread=${counts.entries.joinToString { "${it.key}:${it.value}" }} " +
                        "rooms=${backlog.rooms.size} " +
                        "local=${localSummary()}"
                }
                learnActivity(backlog.activity)
                // The order follows the stamps as much as the counts:
                // a row that just grew a badge from this answer moves
                // to where its message puts it, not where the cache
                // last saw it.
                showRecents(currentState.recents)
                launch { rememberRecents() }
            },
            onError = { },
        )
    }

    /** The cache's unread per conversation, as the seed line prints it. */
    private fun localSummary(): String =
        repository.unreadCounts(serverActivity, backlogWindowStart).entries.joinToString { "${it.key}:${it.value}" }

    /** What the rows show: the larger of the server's word and the cache's. */
    private fun combinedUnread(local: Map<String, Int>): Map<String, Int> =
        (local.keys + serverUnread.keys).associateWith { key ->
            maxOf(local[key] ?: 0, serverUnread[key] ?: 0)
        }.filterValues { it > 0 }

    private fun startFreshProject() {
        // Or the poller keeps asking Firebase about the previous production's
        // peer for as long as the app stays open — both reviewers' finding.
        stopPresence()
        serverUnread.clear()
        serverActivity.clear()
        unsentMedia.clear()
        setState { ChatUiState() }
        launch {
            val stars = loadFavourites()
            setState { copy(favourites = stars) }
            onEvent(ChatEvent.RefreshRecents)
        }
    }

    /**
     * Withdraws one of our own messages, everywhere.
     *
     * The bubble stays until the server confirms — a delete is the one send
     * whose optimism would lie, because a line that reappears after "gone"
     * reads as the peer reposting it.
     */
    private fun deleteMessage(messageId: String) {
        val target = currentState.messages.firstOrNull { it.id == messageId }
        if (target != null && !ChatComposerRules.canRewrite(target.timestampMillis, nowMillis(), isAdmin())) {
            setState { copy(error = ChatComposerRules.REWRITE_WINDOW_CLOSED) }
            return
        }
        launchResult(
            block = {
                repository.deleteMessages(listOf(messageId), currentState.peerIsGroup)
            },
            onSuccess = ::dropDeleted,
            onError = { error -> setState { copy(error = error.localised()) } },
        )
    }

    /**
     * Removes deleted ids from everything on screen.
     *
     * The listing recomputes from the repository — the caches forgot the rows
     * before this ran, so the preview falls back to whatever the thread now
     * ends on rather than echoing the withdrawn line.
     */
    private fun dropDeleted(messageIds: List<String>) {
        val gone = messageIds.toSet()
        // The server's stamps stay: Android's `updateDeletedMessage` marks
        // the line deleted without touching `sorting_activity`, so a row keeps
        // its place when its newest line is withdrawn.
        val activity = knownActivity()
        setState {
            copy(
                messages = messages.filterNot { it.id in gone },
                activity = activity,
                recents = sortedRecents(recents, activity),
                previews = previewsFor(recents),
            )
        }
    }

    /**
     * The toggle every client shares: tapping your current emoji takes it
     * back (the wire's removal is an empty reaction), tapping another
     * replaces it.
     */
    private fun react(messageId: String, emoji: String) {
        val me = repository.selfId() ?: return
        val current = currentState.messages.firstOrNull { it.id == messageId } ?: return
        val mine = current.reactions.firstOrNull { it.userId == me }?.emoji
        val sending = if (mine == emoji) "" else emoji
        launch {
            repository.sendReaction(messageId, sending)?.let { updated ->
                setState {
                    copy(
                        messages = messages.map {
                            if (it.uniqueId == updated.uniqueId) updated else it
                        },
                    )
                }
            }
        }
    }

    // -- the bubble menu's other verbs: edit, forward, read by, translate, image reply --

    /**
     * Opens the Edit dialog on one of our own lines — the web's
     * `EditModal` seeded with the decrypted words. The two-hour clock is
     * checked here, on the click, as the web checks it (`MyMessage.jsx:422-431`):
     * the item stays on the menu and the refusal explains the clock.
     */
    private fun startEdit(messageId: String) {
        val target = currentState.messages.firstOrNull { it.id == messageId && it.isMine } ?: return
        if (!ChatComposerRules.canRewrite(target.timestampMillis, nowMillis(), isAdmin())) {
            setState { copy(error = ChatComposerRules.REWRITE_WINDOW_CLOSED) }
            return
        }
        setState { copy(editing = target, editDraft = target.body) }
    }

    /**
     * The dialog's Save. Unchanged words close it without a round trip
     * (ZL-16986, `MyMessage.jsx:247-251`); an emptied line is refused rather
     * than sent, as the web's `isEmptyString` guard does; the ceiling is the
     * composer's. The bubble changes only on the server's ack — an edit
     * that was refused must not look applied.
     */
    private fun submitEdit() {
        val target = currentState.editing ?: return
        val words = currentState.editDraft.trim()
        if (words == target.body.trim() || words.isEmpty()) {
            setState { copy(editing = null, editDraft = "") }
            return
        }
        if (ChatComposerRules.bodyTooLong(words)) {
            setState { copy(error = ChatComposerRules.BODY_TOO_LONG) }
            return
        }
        setState { copy(editing = null, editDraft = "") }
        launchResult(
            block = { repository.editMessage(target.id, words, currentState.peerIsGroup) },
            onSuccess = { saved -> applyEdit(saved.copy(body = words, isEdited = true)) },
            onError = { error -> setState { copy(error = error.localised()) } },
        )
    }

    /**
     * New words for a line already on screen — from our own ack or the
     * socket's broadcast. Matched on the server id (an edited row may not
     * echo our unique id); everything but the words and the flag is kept
     * from the row we have, since the broadcast's copy can be thinner than
     * the history's (reactions, the expanded quote).
     */
    private fun applyEdit(edited: ChatMessage) {
        val id = edited.id
        val lines = currentState.messages.map { line ->
            if (line.id == id) line.copy(body = edited.body, bodyCipher = edited.bodyCipher, isEdited = true) else line
        }
        // The shelf's preview, when the edited line is the thread's newest.
        val peer = currentState.peer?.userId
        val newest = lines.lastOrNull()
        val previews = if (peer != null && newest != null && newest.id == id) {
            currentState.previews + (peer to previewLine(newest))
        } else {
            currentState.previews
        }
        // The translation was of the old words.
        setState { copy(messages = lines, previews = previews, translations = translations - id) }
    }

    /**
     * One copy per destination, each a plain send with its own unique id —
     * the web's `forwardMessage` (`ForwardMsgModal.jsx:224-261`): the same
     * words, file and place, `reply` dropped, `edited` reset. Sent to the
     * open thread, the copy appears through the repository's ack the way any
     * send does. Failures are counted, not fatal: the web fires every send
     * and toasts success regardless; this toasts what actually went.
     */
    private fun forward(targets: List<ForwardTarget>) {
        val source = currentState.forwarding ?: return
        setState { copy(forwarding = null) }
        if (targets.isEmpty()) return
        launch {
            var sent = 0
            var refusal: String? = null
            targets.forEach { target ->
                val result = repository.send(
                    receiverId = target.id,
                    body = source.body,
                    uniqueId = newUniqueId(),
                    nowMillis = nowMillis(),
                    isGroup = target.isGroup,
                    attachment = source.attachment,
                    location = source.location,
                )
                when (result) {
                    is ZillitResult.Success -> sent++
                    is ZillitResult.Failure -> refusal = result.error.localised()
                }
            }
            setState {
                copy(
                    info = if (sent > 0) FORWARDED else null,
                    error = refusal?.takeIf { sent < targets.size },
                )
            }
            // A copy to the open thread is a new line on it; a copy elsewhere
            // is that conversation's newest word — the shelf learns both.
            onEvent(ChatEvent.RefreshRecents)
        }
    }

    /** The readers panel: open at once, fill when the server answers. */
    private fun showReadBy(messageId: String) {
        val target = currentState.messages.firstOrNull { it.id == messageId } ?: return
        setState { copy(readBy = ReadByView(target)) }
        launchResult(
            block = { repository.readBy(messageId) },
            onSuccess = { report ->
                setState { copy(readBy = readBy?.takeIf { it.message.id == messageId }?.copy(report = report)) }
            },
            onError = { error ->
                setState {
                    copy(readBy = readBy?.takeIf { it.message.id == messageId }?.copy(error = error.localised()))
                }
            },
        )
    }

    /**
     * Someone's words in this computer's language, under the original —
     * the web's `handleTranslateCncMessage`. Nothing is sent: the answer is
     * kept beside the message here alone.
     */
    private fun translate(messageId: String) {
        val engine = translator ?: return
        val target = currentState.messages.firstOrNull { it.id == messageId } ?: return
        if (target.body.isBlank() || messageId in currentState.translating) return
        setState { copy(translating = translating + messageId) }
        launchResult(
            block = { engine.translate(target.body, engine.deviceLanguage()) },
            onSuccess = { translated ->
                setState {
                    copy(
                        translating = translating - messageId,
                        translations = translations + (messageId to translated),
                        info = TRANSLATED,
                    )
                }
            },
            onError = { error ->
                setState { copy(translating = translating - messageId, error = error.localised()) }
            },
        )
    }

    /**
     * The drawn-on picture, straight to the media send — no preview step,
     * the editor was the preview. Rides [unsentMedia] like a picked file so
     * a failed upload keeps its retry.
     */
    private fun imageReply(event: ChatEvent.ImageReply) {
        if (currentState.peer == null) return
        val pending = PendingChatUpload(event.name, event.contentType, event.bytes) { bytes, onProgress ->
            uploadMedia(event.name, event.contentType, bytes, onProgress)
        }
        setState { copy(pendingPreview = pending) }
        sendMedia(PreviewResult(event.name, event.contentType, event.bytes), event.caption)
    }

    private var recordingTicker: kotlinx.coroutines.Job? = null

    private fun startRecording() {
        val mic = voice ?: return
        if (currentState.recordingSeconds != null) return
        launch {
            when (val started = mic.start()) {
                is com.zillit.desktop.core.common.ZillitResult.Failure ->
                    setState { copy(error = started.error.localised()) }
                is com.zillit.desktop.core.common.ZillitResult.Success -> {
                    setState { copy(recordingSeconds = 0, error = null) }
                    recordingTicker = launch {
                        while (currentState.recordingSeconds != null) {
                            kotlinx.coroutines.delay(RECORDING_TICK_MILLIS)
                            setState { copy(recordingSeconds = recordingSeconds?.plus(1)) }
                        }
                    }
                }
            }
        }
    }

    /**
     * Ends the take. Unlike the board — where audio parks in the draft — a
     * chat voice note sends on Stop: the thread is the conversation, and a
     * recorded message waiting behind a second send press is a step nobody
     * expects in a messenger.
     */
    private fun finishRecording(discard: Boolean) {
        val mic = voice ?: return
        if (currentState.recordingSeconds == null) return
        recordingTicker?.cancel()
        setState { copy(recordingSeconds = null) }
        if (discard) {
            mic.cancel()
            return
        }
        launch {
            when (val file = mic.stop()) {
                is com.zillit.desktop.core.common.ZillitResult.Failure ->
                    setState { copy(error = file.error.localised()) }
                is com.zillit.desktop.core.common.ZillitResult.Success ->
                    send(attachment = file.data)
            }
        }
    }

    private fun toggleFavourite(id: String) {
        val next = if (id in currentState.favourites) {
            currentState.favourites - id
        } else {
            currentState.favourites + id
        }
        setState { copy(favourites = next) }
        launch { saveFavourites(next) }
    }

    /**
     * The shelf's previews for [ids] and for every room: a room's newest
     * line is cached under the room id like a person's, but rooms are not
     * in `recents`, so they are added here rather than asked for by each caller.
     */
    private fun previewsFor(ids: List<String>): Map<String, ChatPreview> =
        (ids + currentState.groups.map(GroupRoom::id)).distinct().mapNotNull { id ->
            repository.lastMessageOf(id)?.let { last -> id to previewLine(last) }
        }.toMap()

    /**
     * One line for the shelf. The location branch comes FIRST because a
     * phone-sent place carries a map screenshot as its attachment, and the
     * paperclip rule would have shown the row as "📎 1758…png". Android's own
     * listing marks a location with a pin
     * (`utils/Extensions.kt:295-317`, `provideEmojiContentTypeWise`).
     */
    private fun previewLine(message: ChatMessage): ChatPreview = ChatPreview(
        text = when {
            message.location != null ->
                "📍 " + message.body.ifBlank { message.location.address }.ifBlank { str(S.location) }
            message.attachment != null -> "📎 ${message.attachment.name}"
            else -> message.body
        },
        senderId = message.senderId,
    )

    /**
     * A read-untill receipt covers the whole thread, not one message: every
     * message we sent to that peer moves up to the reported state. Only
     * forwards — a later "delivered" must not un-read what was already read.
     */
    private fun applyReceipt(peerId: String, state: ChatSendState) {
        if (currentState.peer?.userId != peerId) return
        setState {
            copy(
                messages = messages.map { message ->
                    if (message.isMine && !message.sendState.atLeast(state)) {
                        message.copy(sendState = state)
                    } else {
                        message
                    }
                },
            )
        }
    }

    private fun setSendState(uniqueId: String, state: ChatSendState) {
        setState {
            copy(
                messages = messages.map {
                    if (it.uniqueId == uniqueId) it.copy(sendState = state) else it
                },
            )
        }
    }

    /**
     * A socket arrival. The echo of our own send replaces the optimistic
     * bubble by unique id; a peer's message appends when their thread is open.
     */
    /**
     * A live message. Every arrival updates the listing — a conversation with
     * no thread open still moves up the shelf and grows a badge, which is what
     * "synced" means to someone looking at the list rather than a thread.
     * Only the open thread also appends the bubble.
     */
    /**
     * The second tick, for a message that arrived with no thread on screen.
     *
     * Nobody has read it, but it did reach this computer, and the sender is
     * owed that. Only for a message still on rung 1: the web gates the same
     * emit on `status === 1` to stop a flood of acks for messages already
     * delivered or read (`CNC_CHATLIST_SORT_BACKEND_REVIEW.md` §6.2).
     *
     * An open thread is deliberately excluded by the caller — the read emit
     * there is the stronger claim, and racing a 2 behind a 3 would walk the
     * sender's ticks backwards.
     */
    private fun ackDelivered(message: ChatMessage) {
        if (message.isMine || message.sendState != ChatSendState.Sent) return
        val conversation = message.conversationId()
        if (conversation.isBlank()) return
        launch { repository.markDelivered(conversation, message.id, message.isGroup) }
    }

    /**
     * The conversation a line belongs to: the room for a room's line (its
     * sender is a person, its receiver the room), otherwise the other end.
     * Keyed by sender, a room's arrival used to lift, badge and preview the
     * writer's DM row and leave the room's row still.
     */
    private fun ChatMessage.conversationId(): String = when {
        isGroup || isMine -> receiverId
        else -> senderId
    }

    private fun arrived(message: ChatMessage) {
        // The server's echo of our own media send is the ack the store waits
        // for — the retry loop must not send a file the thread already shows.
        if (message.isMine) unsentMedia.remove(message.uniqueId)
        val peer = currentState.peer
        val other = message.conversationId()
        val isOpen = peer != null &&
            (message.senderId == peer.userId || message.receiverId == peer.userId)

        if (isOpen && !message.isMine && peer != null) {
            // Reading it here is what tells the sender it was seen — and,
            // for a room, what clears its badge server-side: the group emit
            // is a different event with a different envelope, and a live
            // arrival that took the DM path left the room's count standing.
            setState { copy(peerTyping = false) }
            repository.markThreadRead(peer.userId, message.timestampMillis)
            val isGroup = currentState.peerIsGroup
            launch {
                repository.markRead(peer.userId, message.id, isGroup)
                onThreadRead(peer.userId)
                applySplit(sectionBadges())
            }
        }

        if (!isOpen) ackDelivered(message)

        // The arrival itself is the newest word on its thread — learned here
        // rather than trusted to the cache, so a row lifts on the message
        // whether or not the repository kept it (a room this desktop never
        // opened, a message the disk cache is not scoped to yet).
        if (other.isNotBlank()) learnActivity(mapOf(other to message.timestampMillis))
        val activity = knownActivity()
        // A line for a thread not on screen is one more the server counts;
        // the cache counts it too, and the row shows whichever is larger.
        if (!isOpen && !message.isMine && other.isNotBlank()) {
            serverUnread[other] = (serverUnread[other] ?: 0) + 1
        }
        val unread = combinedUnread(repository.unreadCounts(serverActivity, backlogWindowStart))
        setState {
            copy(
                recents = sortedRecents(withPeer(recents, other), activity),
                activity = activity,
                unread = unread,
                previews = withPreview(previews, other, message),
                messages = merged(messages, message, isOpen),
            )
        }
        // The server's own count moved with this message — ask it again
        // (a beat later, so a read of the open thread has landed first).
        launch {
            kotlinx.coroutines.delay(SECTION_BADGE_SETTLE_MILLIS)
            applySplit(sectionBadges())
        }
    }

    /** A conversation that just spoke belongs on the shelf, once. */
    private fun withPeer(recents: List<String>, peerId: String): List<String> =
        if (peerId.isBlank() || peerId in recents) recents else recents + peerId

    private fun withPreview(
        previews: Map<String, ChatPreview>,
        peerId: String,
        message: ChatMessage,
    ): Map<String, ChatPreview> {
        if (peerId.isBlank()) return previews
        return previews + (peerId to previewLine(message))
    }

    /** The echo of our own send replaces its optimistic bubble by unique id. */
    private fun merged(
        messages: List<ChatMessage>,
        message: ChatMessage,
        isOpen: Boolean,
    ): List<ChatMessage> = when {
        !isOpen -> messages
        messages.any { it.uniqueId == message.uniqueId } ->
            messages.map { if (it.uniqueId == message.uniqueId) message.keepingQuoteOf(it) else it }
        else -> messages + message
    }

    /**
     * The echo of a reply carries `reply` as the bare id we sent, not the
     * expanded object — so the quote the optimistic bubble already renders
     * would blank until the next history load. The local reference is the
     * fuller truth; keep it.
     */
    private fun ChatMessage.keepingQuoteOf(local: ChatMessage): ChatMessage =
        if (local.replyTo != null && (replyTo == null || replyTo.body.isEmpty())) {
            copy(replyTo = local.replyTo)
        } else {
            this
        }
}

private const val TAG = "Chat"
private const val RECORDING_TICK_MILLIS = 1_000L

/** The web's `forward_successfully` (`utils/language/en.js:6781`). */
internal val FORWARDED: String get() = str(S.desktop_chat_forward_successfully)

/** The web's own sentence, used as its key (`cncUtil.js:1087`). */
internal val TRANSLATED: String get() = str(S.desktop_chat_message_translated)

/** The last DM list this production showed, and its stamps, for when the socket cannot answer. */
private const val RECENTS_CACHE = "chat.recents"

/**
 * What [RECENTS_CACHE] holds: the shelf's ids in their last order and the
 * server's activity per conversation, so an offline open can sort them the
 * same way. Kept as one record — a list of ids alone re-sorted by the local
 * cache, which knows only the threads opened here.
 */
@Serializable
internal data class CachedRecents(
    val ids: List<String> = emptyList(),
    val activity: Map<String, Long> = emptyMap(),
)

/** How long the server gets to apply a read before the tab split is re-asked. */
private const val SECTION_BADGE_SETTLE_MILLIS = 1_800L

/** The file is being prepared (posters, PDF pages) — no bytes moving yet. */
private const val PREPARING = -1

/** The states this device assigns itself; the server's own words never yield to the outbox. */
private fun ChatSendState.isOurs(): Boolean =
    this == ChatSendState.Sending || this == ChatSendState.Queued || this == ChatSendState.Failed

private const val PRESENCE_TAG = "Presence"

/** The shared-place picture's file name and type, as the phones name theirs. */
private const val LOCATION_MAP_NAME = "location-map.png"
private const val LOCATION_MAP_TYPE = "image/png"
