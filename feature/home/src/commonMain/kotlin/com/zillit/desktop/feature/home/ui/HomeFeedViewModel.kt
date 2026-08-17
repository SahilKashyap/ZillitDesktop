package com.zillit.desktop.feature.home.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.BoardRow
import com.zillit.desktop.feature.home.domain.HomeRealtimeEvent
import com.zillit.desktop.feature.home.domain.withDateSeparators
import com.zillit.desktop.feature.home.domain.withPinnedSection
import com.zillit.desktop.feature.home.domain.HomeUnitKind
import com.zillit.desktop.feature.home.domain.applyRealtime
import com.zillit.desktop.feature.home.domain.forDisplay
import com.zillit.desktop.feature.home.domain.NoticeDraft
import com.zillit.desktop.feature.home.domain.AudioRecorder
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.canBeModifiedBy
import com.zillit.desktop.feature.home.domain.nextMatchIndex
import com.zillit.desktop.feature.home.domain.searchMatches
import com.zillit.desktop.feature.home.domain.PickedMedia
import com.zillit.desktop.feature.home.domain.ReadBy
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.domain.NoticeSendState
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.visibleTabs

data class HomeFeedUiState(
    val units: List<HomeUnit> = emptyList(),
    val selectedUnitId: String? = null,
    val notices: List<Notice> = emptyList(),
    val isLoadingUnits: Boolean = false,
    val isLoadingNotices: Boolean = false,
    val draft: NoticeDraft = NoticeDraft(),
    val isSending: Boolean = false,
    /** The notice a reply is being written to, or null for a plain post. */
    val replyTo: Notice? = null,
    /** The reply being rewritten, or null. Mutually exclusive with [replyTo]. */
    val editing: EditingComment? = null,
    /** The post being rewritten, or null. Mutually exclusive with both above. */
    val editingPost: String? = null,
    /** What was in the composer before an edit borrowed it; restored after. */
    val draftBeforeEdit: NoticeDraft? = null,
    /** Who is signed in — what decides which replies offer Edit and Delete. */
    val currentUserId: String? = null,
    /** Seconds on the recording clock; null when not recording. */
    val recordingSeconds: Int? = null,
    /**
     * Upload percent per in-flight post, keyed by local id. 0..99 while bytes
     * move, 100 while the server writes the post — the card's tag reads this
     * as "Uploading n%" then "Processing…". Absent once the post lands.
     */
    val uploadProgress: Map<String, Int> = emptyMap(),
    /** Crew mentioned most recently, newest first — the picker floats these. */
    val recentMentions: List<String> = emptyList(),
    /** The find bar's text; null when the bar is closed. */
    val searchQuery: String? = null,
    /** Which match the board is looking at, 0-based into [searchMatches]. */
    val searchIndex: Int = 0,
    /** The post the unit picker is open for, or null when it is closed. */
    val forwarding: Notice? = null,
    /** The read-receipts panel: which post, and the lists once fetched. */
    val readBy: ReadByView? = null,
    val error: String? = null,
    /** A success note ("Forwarded to…") — floated like [error], toned green. */
    val info: String? = null,
    /** Injected so "Today" is testable and does not drift mid-session. */
    val nowMillis: Long = 0,
    /**
     * Admins may post to any unit regardless of its `posting_access`.
     *
     * The web applies this client-side
     * (`UnitChatMessageBox`: `!posting_access && !is_admin`). The unit list does
     * **not** pre-apply it, even on the `/admin` endpoint — so without this an
     * admin sees a read-only board on every unit.
     */
    val isAdmin: Boolean = false,
    /**
     * The call sheet's history view.
     *
     * The same board in a different mode, as on the web: server data only,
     * ordered by publication rather than last edit, and no composer — history is
     * a record, not a conversation.
     */
    val isHistory: Boolean = false,
) {
    val tabs: List<HomeUnit> get() = units.visibleTabs()

    val selectedUnit: HomeUnit?
        get() = tabs.firstOrNull { it.id == selectedUnitId } ?: tabs.firstOrNull()

    /**
     * The board as rows, with a date separator wherever the day changes.
     *
     * Derived rather than stored: separators are a function of the posts, and
     * keeping them in state would mean recomputing on every socket arrival.
     */
    val rows: List<BoardRow>
        get() = notices.forDisplay(history = isHistory).let { shown ->
            // History reads in pure chronology; the live board floats pins.
            if (isHistory) shown.withDateSeparators(nowMillis) else shown.withPinnedSection(nowMillis)
        }

    /** Matching post ids, in board order. Empty until two characters. */
    val searchMatches: List<String>
        get() = searchQuery?.let { notices.forDisplay(history = isHistory).searchMatches(it) }
            .orEmpty()

    /** The post the find bar is pointed at right now; null when nothing matches. */
    val currentSearchMatch: String?
        get() {
            val matches = searchMatches
            // Checked before clamping: coerceIn(0, -1) on an empty list is an
            // empty range, and an empty range throws — in a getter the board
            // reads on every composition.
            if (matches.isEmpty()) return null
            return matches[searchIndex.coerceIn(0, matches.size - 1)]
        }

    /** Whether this user may edit or delete [comment] — the web's rule. */
    fun canModify(comment: NoticeComment): Boolean =
        comment.canBeModifiedBy(currentUserId, isAdmin, nowMillis)

    /** The same rule for a post. */
    fun canModify(notice: Notice): Boolean =
        notice.canBeModifiedBy(currentUserId, isAdmin, nowMillis)

    /**
     * Whether the composer bar is on screen at all.
     *
     * Not gated on posting rights — iOS shows the bar unconditionally
     * (`textSendView.isHidden = false`) and raises a permission alert when a
     * restricted user acts, and the web does the same. Hiding the bar for
     * rights also raced the profile fetch: an admin whose `is_admin` had not
     * arrived yet saw no bar at all, which read as a missing feature.
     */
    val showsComposer: Boolean
        get() = !isHistory && selectedUnit != null && selectedUnit?.kind != HomeUnitKind.Calendar

    /** Whether a post from this user would actually be accepted. */
    val canCompose: Boolean
        get() = showsComposer && selectedUnit?.let { it.canPost || isAdmin } == true
}

/**
 * What the composer can capture, gathered because they travel together: the
 * picker, the uploader, the microphone, and the video poster extractor. All
 * default to "absent", which quietly removes the affordance they power.
 */
class MediaCapture(
    /** The OS file dialog; null result means the user cancelled. */
    val pick: suspend () -> PickedMedia? = { null },
    /**
     * Puts a picked file into the production's storage (S3 or Box — the app
     * module routes, as mail attachments do). Null disables attaching. The
     * callback reports 0..100 as bytes move, from the uploading coroutine.
     */
    val upload: (suspend (PickedMedia, (Int) -> Unit) -> ZillitResult<UploadedNoticeMedia>)? = null,
    /** The system microphone; null when the platform offers none. */
    val recorder: AudioRecorder? = null,
    /** Names a finished recording; injected so tests are deterministic. */
    val newRecordingName: () -> String = { "voice-message.wav" },
    /**
     * A poster frame for a picked video or PDF; identity when the format is
     * beyond the platform's decoders.
     */
    val videoThumbnail: suspend (PickedMedia) -> PickedMedia = { it },
    /**
     * A static map image for a point, or null — no key in the production's
     * configuration, or no reachable Maps. The location still sends; the
     * receivers' pin fallback carries it.
     */
    val staticMap: suspend (GeoPoint) -> PickedMedia? = { null },
)

/** Which reply the composer is rewriting. */
data class EditingComment(val noticeId: String, val commentId: String)

/** The read-receipts panel's state; [lists] is null while the fetch is out. */
data class ReadByView(val noticeId: String, val lists: ReadBy? = null)

sealed interface HomeFeedEvent {
    data object Load : HomeFeedEvent
    data class SelectUnit(val unitId: String) : HomeFeedEvent
    data object Refresh : HomeFeedEvent
    data class DraftChanged(val text: String) : HomeFeedEvent

    /** The paperclip: open the OS picker and attach what comes back. */
    data object Attach : HomeFeedEvent

    /** A file dragged in from the OS; [extra] counts the ones beyond the first. */
    data class AttachDropped(val picked: PickedMedia, val extra: Int = 0) : HomeFeedEvent

    /** A location chosen in the picker dialog joins the draft like a file. */
    data class AttachLocation(val point: GeoPoint) : HomeFeedEvent

    /** The microphone: record until stopped, then wait as the draft's file. */
    data object StartRecording : HomeFeedEvent
    data object StopRecording : HomeFeedEvent
    data object CancelRecording : HomeFeedEvent
    data object RemoveAttachment : HomeFeedEvent
    data object Send : HomeFeedEvent

    /** Re-send a post the server refused. */
    data class Retry(val localId: String) : HomeFeedEvent

    /** Point the composer at a notice; the next send becomes its reply. */
    data class StartReply(val noticeId: String) : HomeFeedEvent
    data object CancelReply : HomeFeedEvent

    /** Borrow the composer to rewrite a reply; the next send saves it. */
    data class StartEditComment(val noticeId: String, val commentId: String) : HomeFeedEvent
    data object CancelEditComment : HomeFeedEvent
    data class DeleteComment(val noticeId: String, val commentId: String) : HomeFeedEvent

    /** Borrow the composer to rewrite a post; the next send saves it. */
    data class StartEditNotice(val noticeId: String) : HomeFeedEvent
    data object CancelEditNotice : HomeFeedEvent
    data class DeleteNotice(val noticeId: String) : HomeFeedEvent

    /** Pin a post to the top of the board, or take it back down. */
    data class TogglePin(val noticeId: String) : HomeFeedEvent

    /** Open the unit picker for a post; choosing a unit posts a copy there. */
    data class StartForward(val noticeId: String) : HomeFeedEvent
    data class ForwardTo(val unitId: String) : HomeFeedEvent
    data object CancelForward : HomeFeedEvent

    /** Who has read this post — opens the receipts panel. */
    data class ShowReadBy(val noticeId: String) : HomeFeedEvent
    data object DismissReadBy : HomeFeedEvent

    /** Push a reminder notification to everyone still on the unread list. */
    data object NotifyUnread : HomeFeedEvent

    /** Close the success popup. */
    data object DismissInfo : HomeFeedEvent

    /** Close the error popup. */
    data object DismissError : HomeFeedEvent

    /** The find bar over the board. */
    data object OpenSearch : HomeFeedEvent
    data object CloseSearch : HomeFeedEvent
    data class SearchChanged(val query: String) : HomeFeedEvent
    data class StepSearch(val forward: Boolean) : HomeFeedEvent

    /** Toggle the call sheet's history view. */
    data class ShowHistory(val history: Boolean) : HomeFeedEvent

    /** A crew name completed from the mention picker — feeds the recency boost. */
    data class MentionPicked(val name: String) : HomeFeedEvent

    /** Delivered by the socket, not by the user. */
    data class Realtime(val event: HomeRealtimeEvent) : HomeFeedEvent
}

/**
 * Home's tab strip and the board behind the selected tab.
 *
 * Separate from [HomeViewModel], which owns the production's *permissions*.
 * These are different lifetimes: rights are fetched once per production, while
 * the board is refetched whenever the user changes tab.
 */
@Suppress("TooManyFunctions") // One function per board event; splitting the board's own state machine hides the set.
class HomeFeedViewModel(
    private val repository: HomeFeedRepository,
    private val nowMillis: () -> Long,
    private val newLocalId: () -> String = { "local-${nowMillis()}" },
    private val isAdmin: () -> Boolean = { false },
    private val currentUserId: () -> String? = { null },
    /** Everything the composer can capture — see [MediaCapture]. */
    private val media: MediaCapture = MediaCapture(),
    /**
     * Tells the server this user is looking at a unit's board — what feeds
     * "read by" on everyone else's screens and clears the unit's badge. The
     * web emits `notification:read` here; the host wires that in. Called on
     * every board load and again when a post arrives on the open board.
     */
    private val onBoardViewed: suspend (unitId: String) -> Unit = {},
    /**
     * The mention picker's recency memory. Loaded once with the units, saved
     * on every pick; the host wires these to per-project preferences, tests
     * leave them inert.
     */
    private val loadRecentMentions: suspend () -> List<String> = { emptyList() },
    private val saveRecentMentions: suspend (List<String>) -> Unit = {},
) : ZillitViewModel<HomeFeedUiState, HomeFeedEvent, Nothing>(HomeFeedUiState()) {

    /**
     * Uploads that succeeded for posts that then failed, by local id.
     *
     * Retry must not re-upload: the bytes are already in storage, and a second
     * copy per retry would fill the production's bucket with orphans.
     */
    private val uploadedByLocalId = mutableMapOf<String, UploadedNoticeMedia>()

    /**
     * The picked file behind each in-flight media post, by local id.
     *
     * Kept for the *other* failure: when the upload itself died, there is
     * nothing in [uploadedByLocalId], and a retry that forgets the file posts
     * the caption alone — for a media-only post, a blank card on everyone's
     * board. Held until the post lands, then dropped with its sibling.
     */
    private val pickedByLocalId = mutableMapOf<String, PickedMedia>()

    /** Test seam: the pre-fix state where a failed upload's file is gone. */
    internal fun forgetPickedFor(localId: String) {
        pickedByLocalId.remove(localId)
    }

    // No eager load: every call here carries project and user in its header,
    // and neither exists until a production is open.

    // Exhaustive dispatch over a sealed event set — the branch count is the
    // point of the pattern, not a complexity problem (see WorkspaceViewModel).
    @Suppress("CyclomaticComplexMethod")
    override fun onEvent(event: HomeFeedEvent) {
        when (event) {
            HomeFeedEvent.Load -> loadUnits()
            HomeFeedEvent.Refresh -> currentState.selectedUnit?.let { loadNotices(it) }
            is HomeFeedEvent.DraftChanged -> setState {
                copy(draft = NoticeDraft(event.text, draft.media), error = null)
            }
            HomeFeedEvent.Attach -> attach()
            is HomeFeedEvent.AttachDropped ->
                attach(dropped = event.picked, extraDropped = event.extra)
            HomeFeedEvent.StartRecording -> record()
            HomeFeedEvent.StopRecording -> record(discard = false)
            HomeFeedEvent.CancelRecording -> record(discard = true)
            HomeFeedEvent.RemoveAttachment -> setState {
                copy(draft = draft.copy(media = null, location = null))
            }
            HomeFeedEvent.Send -> send()
            is HomeFeedEvent.Retry -> retry(event.localId)
            is HomeFeedEvent.DeleteComment -> delete(event.noticeId, event.commentId)
            is HomeFeedEvent.DeleteNotice -> delete(event.noticeId)
            is HomeFeedEvent.TogglePin -> togglePin(event.noticeId)
            is HomeFeedEvent.StartForward -> setState {
                // Only a post the server holds can be copied elsewhere.
                copy(forwarding = notices.firstOrNull {
                    it.id == event.noticeId && it.sendState == NoticeSendState.Sent
                })
            }
            is HomeFeedEvent.ForwardTo -> forwardTo(event.unitId)
            HomeFeedEvent.CancelForward -> setState { copy(forwarding = null) }
            is HomeFeedEvent.ShowReadBy -> showReadBy(event.noticeId)
            HomeFeedEvent.DismissReadBy -> setState { copy(readBy = null) }
            HomeFeedEvent.DismissError -> setState { copy(error = null) }
            HomeFeedEvent.DismissInfo -> setState { copy(info = null) }
            HomeFeedEvent.OpenSearch -> setState { copy(searchQuery = "", searchIndex = 0) }
            HomeFeedEvent.CloseSearch -> setState { copy(searchQuery = null, searchIndex = 0) }
            is HomeFeedEvent.SearchChanged -> setState {
                // A new needle starts from the first match, not wherever the
                // old one left the pointer.
                copy(searchQuery = event.query, searchIndex = 0)
            }
            is HomeFeedEvent.StepSearch -> setState {
                copy(
                    searchIndex = nextMatchIndex(searchIndex, searchMatches.size, event.forward),
                )
            }
            is HomeFeedEvent.ShowHistory -> setState { withHistory(event.history) }
            // A units change refetches the tab strip — rights may have moved,
            // and a tab the user can no longer see must go. Everything else is
            // a pure merge; `applyRealtime` handles our own post arriving back.
            is HomeFeedEvent.Realtime ->
                if (event.event == HomeRealtimeEvent.UnitsChanged) {
                    loadUnits()
                } else {
                    setState { copy(notices = notices.applyRealtime(event.event, selectedUnitId)) }
                    // A post landing on the open board is read as it lands —
                    // the web emits on `home_message_added` while visible.
                    val added = event.event as? HomeRealtimeEvent.NoticeAdded
                    if (added?.unitId != null && added.unitId == currentState.selectedUnitId) {
                        launch { onBoardViewed(added.unitId) }
                    }
                }
            is HomeFeedEvent.SelectUnit -> selectUnit(event.unitId)
            else -> onComposerModeEvent(event)
        }
    }

    /**
     * The composer's borrowed states: replying, editing a reply, editing a
     * post. Split from the main dispatch because together they are half the
     * event set, and all of them are about which mode the one composer is in.
     */
    // The same exhaustive-dispatch shape as onEvent: branch count is the
    // pattern, not a complexity smell (see the annotation there).
    @Suppress("CyclomaticComplexMethod")
    private fun onComposerModeEvent(event: HomeFeedEvent) {
        when (event) {
            // A chosen location parks in the draft like a file: the pin lands
            // first and the map image joins it when the fetch finishes.
            is HomeFeedEvent.AttachLocation -> {
                if (!requirePostingRights()) return
                val point = event.point
                setState { copy(draft = draft.copy(location = point, media = null), error = null) }
                launch {
                    val map = media.staticMap(point) ?: return@launch
                    setState {
                        if (draft.location == point) copy(draft = draft.copy(media = map)) else this
                    }
                }
            }
            // Entering reply mode drops an attached file, visibly: replies are
            // text on this endpoint, and silently posting the file as its own
            // notice would be the worse surprise.
            is HomeFeedEvent.StartReply -> setState {
                copy(
                    replyTo = notices.firstOrNull { it.id == event.noticeId },
                    editing = null,
                    editingPost = null,
                    draft = draft.copy(media = null),
                    error = null,
                )
            }
            HomeFeedEvent.CancelReply -> setState { copy(replyTo = null) }
            is HomeFeedEvent.StartEditComment -> startEdit(event.noticeId, event.commentId)
            HomeFeedEvent.CancelEditComment -> setState {
                copy(editing = null, draft = draftBeforeEdit ?: NoticeDraft(), draftBeforeEdit = null)
            }
            is HomeFeedEvent.StartEditNotice -> startEdit(event.noticeId, commentId = null)
            HomeFeedEvent.CancelEditNotice -> setState {
                copy(
                    editingPost = null,
                    draft = draftBeforeEdit ?: NoticeDraft(),
                    draftBeforeEdit = null,
                )
            }
            // The receipts panel's notify: a push to everyone still unread.
            // Rights re-checked here even though the UI hides the button —
            // the state that hid it can be stale by the click.
            HomeFeedEvent.NotifyUnread -> {
                val noticeId = currentState.readBy?.noticeId ?: return
                val unit = currentState.selectedUnit ?: return
                if (!requirePostingRights()) return
                launchResult(
                    block = { repository.notifyUnread(unit.id, noticeId) },
                    onSuccess = {
                        setState { copy(info = "Everyone still unread has been notified.") }
                    },
                    onError = { error -> setState { copy(error = error.localised()) } },
                )
            }
            // Newest to the front, no repeats, capped — then to the store.
            is HomeFeedEvent.MentionPicked -> {
                val next = (listOf(event.name) + currentState.recentMentions)
                    .distinct()
                    .take(MAX_RECENT_MENTIONS)
                setState { copy(recentMentions = next) }
                launch { saveRecentMentions(next) }
            }
            else -> Unit
        }
    }

    private fun selectUnit(unitId: String) {
        val unit = currentState.tabs.firstOrNull { it.id == unitId } ?: return
        setState {
            copy(
                selectedUnitId = unit.id,
                notices = emptyList(),
                replyTo = null,
                editing = null,
                editingPost = null,
                draftBeforeEdit = null,
                searchQuery = null,
                searchIndex = 0,
                error = null,
            )
        }
        loadNotices(unit)
    }

    /**
     * Posts the draft, showing it before the server has taken it.
     *
     * The card appears immediately and the composer clears, because waiting for
     * a round trip before either makes typing feel unanswered. If the post
     * fails the card stays with a retry rather than disappearing along with the
     * words the user wrote.
     */
    /**
     * The action-time rights check every composer capture shares.
     *
     * Checked live, not from state: the profile (and its admin flag) can
     * arrive after the board loaded, and rights are the server's to grant
     * between one action and the next. iOS asks at exactly these moments too.
     */
    private fun requirePostingRights(): Boolean {
        val unit = currentState.selectedUnit ?: return false
        if (unit.canPost || isAdmin()) return true
        setState { copy(error = noPostingRights(unit)) }
        return false
    }

    /**
     * Parks something in the draft: a [point] the user chose, or — without
     * one — whatever the OS file picker returns.
     *
     * Either way the chip lands first and its image joins it (the static map
     * for a location, the poster frame for a video) — fetching or decoding
     * before showing anything makes attaching feel broken on slow hardware.
     */
    private fun attach(dropped: PickedMedia? = null, extraDropped: Int = 0) {
        if (!requirePostingRights()) return
        if (media.upload == null || currentState.replyTo != null) return
        val unit = currentState.selectedUnit ?: return

        launch {
            val picked = dropped ?: media.pick() ?: return@launch

            // The call sheet unit takes documents only -- iOS's attach sheet
            // offers nothing else there, and its camera is hidden outright.
            if (unit.kind == HomeUnitKind.CallSheet && picked.kind != NoticeKind.Document) {
                setState { copy(error = "Only documents can be posted to " + unit.label + ".") }
                return@launch
            }

            // The chip appears at once; the poster frame joins it when the
            // extraction finishes. Decoding video before showing anything
            // would make picking a file feel broken.
            setState { copy(draft = draft.copy(media = picked), error = null) }

            // The wire takes one attachment per post; saying so beats
            // silently discarding the rest of a multi-file drag.
            if (extraDropped > 0) {
                setState { copy(info = "One file per post — attached the first.") }
            }

            // Videos get a frame, PDFs their first page — the web's pair.
            if (picked.kind == NoticeKind.Video || picked.isPdf) {
                val withPoster = media.videoThumbnail(picked)
                // Only if this file is still the one attached — the user may
                // have removed or replaced it while frames were decoding.
                setState {
                    if (draft.media === picked) {
                        copy(draft = draft.copy(media = withPoster))
                    } else {
                        this
                    }
                }
            }
        }
    }

    /** Counts the recording up once a second, for the bar's clock. */
    private var recordingTicker: Job? = null

    /**
     * Opens the microphone and starts the clock.
     *
     * The same gates as the paperclip: live posting rights, and not while the
     * composer is borrowed by a reply — iOS hides its mic in exactly those
     * states. Recording replaces any file already attached when it lands, so
     * starting also clears one.
     */
    private fun record(discard: Boolean? = null) {
        val mic = media.recorder ?: return

        // null starts; true or false ends the take, keeping or dropping it.
        if (discard == null) {
            val blocked = currentState.recordingSeconds != null ||
                currentState.replyTo != null || !requirePostingRights()
            if (blocked) return
            launch {
                when (val started = mic.start()) {
                    is ZillitResult.Failure -> setState { copy(error = started.error.localised()) }
                    is ZillitResult.Success -> {
                        setState { copy(recordingSeconds = 0, error = null) }
                        recordingTicker = launch {
                            while (currentState.recordingSeconds != null) {
                                delay(TICK_MILLIS)
                                setState {
                                    copy(recordingSeconds = recordingSeconds?.plus(1))
                                }
                            }
                        }
                    }
                }
            }
        } else if (currentState.recordingSeconds != null) {
            // Ending the take: the clock stops either way; unless discarded
            // the audio parks in the draft like any picked file.
            recordingTicker?.cancel()
            setState { copy(recordingSeconds = null) }
            if (discard) {
                mic.cancel()
            } else {
                launch {
                    when (val captured = mic.stop()) {
                        is ZillitResult.Failure ->
                            setState { copy(error = captured.error.localised()) }
                        is ZillitResult.Success -> setState {
                            copy(
                                draft = draft.copy(
                                    media = captured.data.toPickedMedia(media.newRecordingName()),
                                ),
                                error = null,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Ends the recording: the clock stops either way, and unless [discard] the
     * captured audio parks in the draft like any picked file — same chip, same
     * upload, same retry.
     */


    private fun send() {
        val unit = currentState.selectedUnit ?: return
        val draft = currentState.draft
        if (!draft.canSend || currentState.isSending) return
        if (!requirePostingRights()) return

        val editingPost = currentState.editingPost
        val editingComment = currentState.editing
        val replyTo = currentState.replyTo
        if (editingPost != null || editingComment != null || replyTo != null) {
            when {
                editingPost != null -> saveEditedNotice(editingPost, draft.trimmed)
                editingComment != null -> saveEditedComment(editingComment, draft.trimmed)
                else -> replyTo?.let { sendReply(unit, it, draft.trimmed) }
            }
            return
        }

        val optimistic = optimisticNotice(newLocalId(), draft, nowMillis())

        setState {
            copy(notices = notices + optimistic, draft = NoticeDraft(), isSending = true, error = null)
        }

        deliver(unit.id, optimistic, draft.media, draft.location)
    }

    /**
     * Posts a reply and merges what the server returns.
     *
     * No optimistic copy: the web waits for the response and merges its
     * `comments` array, and a reply that failed quietly would sit inside
     * someone else's bubble looking delivered. The composer stays on "Sending"
     * instead, and keeps the words on failure.
     */
    private fun sendReply(unit: HomeUnit, parent: Notice, text: String) {
        if (text.isEmpty()) return

        setState { copy(isSending = true, error = null) }

        launchResult(
            block = { repository.postComment(parent.id, unit.id, text) },
            onSuccess = { comments ->
                setState {
                    copy(
                        isSending = false,
                        draft = NoticeDraft(),
                        replyTo = null,
                        notices = notices.map { notice ->
                            if (notice.id == parent.id) notice.copy(comments = comments) else notice
                        },
                    )
                }
            },
            onError = { error ->
                // The draft survives: losing a written reply to a blip is the
                // same small theft twice as losing a post.
                setState { copy(isSending = false, error = error.localised()) }
            },
        )
    }

    /**
     * Points the composer at a post's or a reply's current words.
     *
     * One function for both targets because the move is identical: stash
     * whatever was half-typed (kept from the *first* borrow, so hopping
     * between targets cannot lose it), load the existing words, mark which
     * thing the next send rewrites.
     */
    private fun startEdit(noticeId: String, commentId: String?) {
        val notice = currentState.notices.firstOrNull { it.id == noticeId } ?: return
        val comment = commentId?.let { id -> notice.comments.firstOrNull { it.id == id } }
        if (commentId != null && comment == null) return

        setState {
            copy(
                editing = comment?.let { EditingComment(noticeId, it.id) },
                editingPost = if (comment == null) noticeId else null,
                replyTo = null,
                draftBeforeEdit = draftBeforeEdit ?: draft,
                draft = NoticeDraft(comment?.body ?: notice.body),
                error = null,
            )
        }
    }

    /**
     * Saves a rewrite and swaps in the server's copy.
     *
     * The web replaces just the one reply from the response rather than
     * refetching the board, and so does this. If the response named no reply,
     * the local text stands in until the next load.
     */
    private fun saveEditedComment(target: EditingComment, text: String) {
        if (text.isEmpty()) return

        setState { copy(isSending = true, error = null) }

        launchResult(
            block = { repository.editComment(target.noticeId, target.commentId, text) },
            onSuccess = { updated ->
                setState {
                    copy(
                        isSending = false,
                        editing = null,
                        draft = draftBeforeEdit ?: NoticeDraft(),
                        draftBeforeEdit = null,
                        notices = notices.replacingComment(
                            noticeId = target.noticeId,
                            commentId = target.commentId,
                        ) { existing ->
                            updated ?: existing.copy(body = text, isEdited = true)
                        },
                    )
                }
            },
            onError = { error ->
                // The rewrite stays in the composer; losing it would mean
                // retyping a correction that was already made once.
                setState { copy(isSending = false, error = error.localised()) }
            },
        )
    }

    /**
     * Removes a post, or one reply of it when [commentId] is given.
     *
     * Optimistically: the web removes from the store on success only, but a
     * desktop row that lingers after its confirm was clicked reads as a broken
     * button. A failed delete puts it back with the error alongside.
     */
    private fun delete(noticeId: String, commentId: String? = null) {
        // A whole post: off the board at once, back with the error if refused.
        if (commentId == null) {
            val removedNotice = currentState.notices.firstOrNull { it.id == noticeId } ?: return
            setState { copy(notices = notices.filterNot { it.id == noticeId }) }
            launchResult(
                block = { repository.deleteNotice(noticeId) },
                onSuccess = { },
                onError = { error ->
                    setState {
                        copy(
                            error = error.localised(),
                            notices = (notices + removedNotice).forDisplay(),
                        )
                    }
                },
            )
            return
        }
        val removed = currentState.notices
            .firstOrNull { it.id == noticeId }
            ?.comments?.firstOrNull { it.id == commentId }
            ?: return

        setState {
            copy(
                notices = notices.map { notice ->
                    if (notice.id == noticeId) {
                        notice.copy(comments = notice.comments.filterNot { it.id == commentId })
                    } else {
                        notice
                    }
                },
            )
        }

        launchResult(
            block = { repository.deleteComment(noticeId, commentId) },
            onSuccess = { },
            onError = { error ->
                setState {
                    copy(
                        error = error.localised(),
                        notices = notices.map { notice ->
                            if (notice.id == noticeId) {
                                notice.copy(comments = notice.comments + removed)
                            } else {
                                notice
                            }
                        },
                    )
                }
            },
        )
    }

    /**
     * Saves a rewritten post.
     *
     * The server's copy replaces the local one — it carries the new `updated`,
     * which is what reorders the live board, exactly as an edit does on the
     * web. A response that will not read as a message leaves the local text
     * standing, marked edited.
     */
    private fun saveEditedNotice(noticeId: String, text: String) {
        if (text.isEmpty()) return

        setState { copy(isSending = true, error = null) }

        launchResult(
            block = { repository.editNotice(noticeId, text) },
            onSuccess = { updated ->
                setState {
                    copy(
                        isSending = false,
                        editingPost = null,
                        draft = draftBeforeEdit ?: NoticeDraft(),
                        draftBeforeEdit = null,
                        notices = notices.map { notice ->
                            when {
                                notice.id != noticeId -> notice
                                updated != null ->
                                    // The response does not carry the replies.
                                    updated.copy(comments = notice.comments)
                                else -> notice.copy(body = text, isEdited = true)
                            }
                        },
                    )
                }
            },
            onError = { error ->
                setState { copy(isSending = false, error = error.localised()) }
            },
        )
    }

    /**
     * Opens the receipts panel and fetches its lists.
     *
     * The panel shows at once with a loading state — receipts live only
     * server-side, and a menu click that does nothing until a round trip
     * finishes reads as a dead item.
     */
    /**
     * Pins or unpins, optimistically — the post jumps to the Pinned section
     * at once; a refused save flips it back with the error alongside.
     */
    private fun togglePin(noticeId: String) {
        val unit = currentState.selectedUnit ?: return
        val notice = currentState.notices.firstOrNull { it.id == noticeId } ?: return
        if (!requirePostingRights()) return
        val pinned = !notice.isPinned

        setState {
            copy(notices = notices.map { if (it.id == noticeId) it.copy(isPinned = pinned) else it })
        }

        launchResult(
            block = { repository.setPinned(notice, unit.id, pinned) },
            onSuccess = { },
            onError = { error ->
                setState {
                    copy(
                        error = error.localised(),
                        notices = notices.map {
                            if (it.id == noticeId) it.copy(isPinned = !pinned) else it
                        },
                    )
                }
            },
        )
    }

    private fun showReadBy(noticeId: String) {
        setState { copy(readBy = ReadByView(noticeId)) }

        launchResult(
            block = { repository.readBy(noticeId) },
            onSuccess = { lists ->
                setState {
                    // Only if the panel is still open for this post.
                    if (readBy?.noticeId == noticeId) {
                        copy(readBy = ReadByView(noticeId, lists))
                    } else {
                        this
                    }
                }
            },
            onError = { error ->
                setState { copy(readBy = null, error = error.localised()) }
            },
        )
    }

    /**
     * Posts a copy of the picked notice to [unitId].
     *
     * Rights are the web's rules, checked at choose time: the *target* unit
     * must grant posting (`posting_access`, admin excepted), and a call sheet
     * takes only text and documents — the same filter its own composer applies.
     */
    private fun forwardTo(unitId: String) {
        val notice = currentState.forwarding ?: return
        val target = currentState.units.firstOrNull { it.id == unitId } ?: return

        if (!target.canPost && !isAdmin()) {
            setState { copy(forwarding = null, error = noPostingRights(target)) }
            return
        }
        val callSheetTakes = notice.kind == NoticeKind.Text || notice.kind == NoticeKind.Document
        if (target.kind == HomeUnitKind.CallSheet && !callSheetTakes) {
            setState {
                copy(forwarding = null, error = "Only text and documents can go to ${target.label}.")
            }
            return
        }

        setState { copy(forwarding = null) }
        launchResult(
            block = { repository.forwardNotice(unitId, notice, newLocalId()) },
            onSuccess = {
                setState { copy(info = "Forwarded to ${target.label}.") }
                // A copy sent to the board on screen should appear on it.
                if (unitId == currentState.selectedUnit?.id) loadNotices(target)
            },
            onError = { error -> setState { copy(error = error.localised()) } },
        )
    }

    private fun retry(localId: String) {
        val unit = currentState.selectedUnit ?: return
        val failed = currentState.notices.firstOrNull { it.localId == localId } ?: return

        setState { copy(notices = notices.replacing(localId, failed.copy(sendState = NoticeSendState.Sending))) }
        // The file rides again when the UPLOAD was what failed — with null
        // here, that retry posted the caption alone, and a media-only post's
        // caption is nothing: a blank card on everyone's board.
        deliver(unit.id, failed, picked = pickedByLocalId[localId], location = failed.location)
    }

    /**
     * Uploads first when there is a file, then posts.
     *
     * The upload is the long half, and its result is remembered per local id so
     * a retry after a failed *post* goes straight to posting — the file is
     * already in storage, and re-uploading per retry would fill the bucket with
     * orphaned copies.
     */
    /**
     * The upload half of a delivery: the cached result on a retry, nothing
     * for a text post, otherwise the file into storage with live percent.
     */
    private suspend fun uploadFor(
        localId: String,
        picked: PickedMedia?,
    ): ZillitResult<UploadedNoticeMedia?> {
        uploadedByLocalId[localId]?.let { return ZillitResult.Success(it) }
        if (picked == null) return ZillitResult.Success(null)
        val upload = media.upload
            ?: return ZillitResult.Failure(ZillitError.Storage("no uploader wired for notice media"))
        return when (val stored = upload(picked) { percent ->
            setState { copy(uploadProgress = uploadProgress + (localId to percent)) }
        }) {
            is ZillitResult.Failure -> stored
            is ZillitResult.Success -> ZillitResult.Success(stored.data.also { uploadedByLocalId[localId] = it })
        }
    }

    private fun deliver(
        unitId: String,
        optimistic: Notice,
        picked: PickedMedia?,
        location: GeoPoint? = null,
    ) {
        val localId = optimistic.localId ?: return
        picked?.let { pickedByLocalId[localId] = it }

        launchResult(
            block = {
                val uploaded = when (val stored = uploadFor(localId, picked)) {
                    is ZillitResult.Failure -> return@launchResult stored
                    is ZillitResult.Success -> stored.data
                }
                // A media post whose file went missing must fail loudly, not
                // post its empty caption — the blank card the tester saw.
                if (uploaded == null && optimistic.kind != NoticeKind.Text) {
                    return@launchResult ZillitResult.Failure(
                        ZillitError.Storage(
                            technical = "media post retried with no file to upload",
                            userMessage = "The file is no longer attached — pick it again.",
                        ),
                    )
                }
                // Bytes are in storage; what remains is the server writing the
                // post. The card's tag reads 100 as "Processing…".
                if (uploaded != null) {
                    setState {
                        copy(uploadProgress = uploadProgress + (localId to UPLOAD_DONE))
                    }
                }
                repository.postNotice(unitId, optimistic.body, localId, uploaded, location)
            },
            onSuccess = { saved ->
                // Replace rather than append: the optimistic card and the
                // server's copy are the same post, and appending would show it
                // twice.
                uploadedByLocalId.remove(localId)
                pickedByLocalId.remove(localId)
                setState {
                    copy(
                        isSending = false,
                        notices = notices.replacing(localId, saved),
                        uploadProgress = uploadProgress - localId,
                    )
                }
            },
            onError = { error ->
                setState {
                    copy(
                        isSending = false,
                        notices = notices.replacing(
                            localId,
                            optimistic.copy(sendState = NoticeSendState.Failed),
                        ),
                        error = error.localised(),
                        uploadProgress = uploadProgress - localId,
                    )
                }
            },
        )
    }

    private fun loadUnits() {
        setState {
            copy(
                isLoadingUnits = true,
                error = null,
                nowMillis = nowMillis(),
                isAdmin = isAdmin(),
                currentUserId = currentUserId(),
            )
        }

        // The picker's recency memory rides along with the board's context.
        launch {
            val recent = loadRecentMentions()
            if (recent.isNotEmpty()) setState { copy(recentMentions = recent) }
        }

        launchResult(
            block = { repository.loadUnits() },
            onSuccess = { units ->
                setState { copy(isLoadingUnits = false, units = units) }
                // Open the first tab immediately: a tab strip with nothing under
                // it reads as a broken screen.
                currentState.selectedUnit?.let(::loadNotices)
            },
            onError = { setState { copy(isLoadingUnits = false, error = it.localised()) } },
        )
    }

    private fun loadNotices(unit: HomeUnit) {
        // Re-read on every board load, not once at startup: the profile that
        // carries is_admin and the user id loads in parallel with the units,
        // and a value captured before it arrived would stick wrong.
        setState { copy(isAdmin = isAdmin(), currentUserId = currentUserId()) }

        // The calendar tab has no board; asking for one would 404. It has a
        // badge, though — event invites and changes are filed under its unit
        // — and opening the tab is what reads them, exactly as for a board.
        // Without this the calendar's count could never fall from here.
        if (unit.kind == HomeUnitKind.Calendar) {
            setState { copy(selectedUnitId = unit.id, notices = emptyList(), isLoadingNotices = false) }
            launch { onBoardViewed(unit.id) }
            return
        }

        setState { copy(selectedUnitId = unit.id, isLoadingNotices = true, error = null) }

        launchResult(
            // "now" is the newest page — the endpoint reads backwards from a
            // timestamp rather than paging by offset, because a board people are
            // posting to shifts under an offset.
            block = { repository.loadNotices(unit.id, nowMillis()) },
            onSuccess = { notices ->
                // Guard against a slow response for a tab the user has left.
                if (currentState.selectedUnitId == unit.id) {
                    setState { copy(isLoadingNotices = false, notices = notices) }
                    // The board is on screen — that is what "read" means on
                    // this API, as when the web's Notices tab is visible.
                    launch { onBoardViewed(unit.id) }
                }
            },
            onError = { error ->
                if (currentState.selectedUnitId == unit.id) {
                    setState { copy(isLoadingNotices = false, error = error.localised()) }
                }
            },
        )
    }
}

/** Swaps the post carrying [localId] for [replacement], leaving order intact. */
/** Swaps one reply inside one notice, leaving everything else untouched. */
private fun List<Notice>.replacingComment(
    noticeId: String,
    commentId: String,
    transform: (NoticeComment) -> NoticeComment,
): List<Notice> = map { notice ->
    if (notice.id != noticeId) {
        notice
    } else {
        notice.copy(
            comments = notice.comments.map { if (it.id == commentId) transform(it) else it },
        )
    }
}

/**
 * Swaps the optimistic card for the server's copy — and keeps exactly one row
 * with the server's id.
 *
 * The second half exists because of a race the media path made likely: the
 * upload makes the POST slow, the socket echo of our own post lands first,
 * and a media echo carries no `unique_id` — so the echo cannot match the
 * optimistic card and APPENDS the server copy. The POST response then swapped
 * the optimistic card into a second row with the same id, and the board's
 * LazyColumn died on the duplicate key. Text posts never hit it: their echo
 * matches by `unique_id` and replaces.
 */
private fun List<Notice>.replacing(localId: String, replacement: Notice): List<Notice> {
    val swapped = map { if (it.localId == localId || it.id == localId) replacement else it }
    var kept = false
    return swapped.filter { notice ->
        if (notice.id != replacement.id) {
            true
        } else {
            val keep = !kept
            kept = true
            keep
        }
    }
}

private const val TICK_MILLIS = 1_000L

/** The bytes are all in storage; the server is writing the post. */
private const val UPLOAD_DONE = 100

/**
 * Two pickers' worth of memory. Enough that a small production's whole crew
 * can be "recent"; small enough that the boost still means something.
 */
private const val MAX_RECENT_MENTIONS = 12

/**
 * Enters or leaves the call sheet's history mode. Entering drops optimistic
 * and failed posts: history shows what the server holds, and a card stuck
 * "Sending" is not part of the record (ZL-17613).
 */
private fun HomeFeedUiState.withHistory(history: Boolean): HomeFeedUiState = copy(
    isHistory = history,
    notices = if (history) notices.filter { it.sendState == NoticeSendState.Sent } else notices,
)

/** iOS's PostingPermissionPopUp, as a composer error rather than a modal. */
private fun noPostingRights(unit: HomeUnit): String =
    "You do not have posting rights for " + unit.label +
        ". Ask a production admin to grant them."

/** The card shown before the server answers — the send's own local echo. */
private fun optimisticNotice(localId: String, draft: NoticeDraft, now: Long): Notice = Notice(
    id = localId,
    body = draft.trimmed,
    authorName = "You",
    createdAtMillis = now,
    kind = when {
        draft.location != null -> NoticeKind.Location
        else -> draft.media?.kind ?: NoticeKind.Text
    },
    location = draft.location,
    sendState = NoticeSendState.Sending,
    localId = localId,
)
