package com.zillit.desktop.feature.home.ui

import com.zillit.desktop.core.permissions.RightsArea
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.datetime.toLocalDateTime
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.BoardRow
import com.zillit.desktop.feature.home.domain.HomeRealtimeEvent
import com.zillit.desktop.feature.home.domain.withDateSeparators
import com.zillit.desktop.feature.home.domain.pinnedForBanner
import com.zillit.desktop.feature.home.domain.HomeUnitKind
import com.zillit.desktop.feature.home.domain.applyRealtime
import com.zillit.desktop.feature.home.domain.forDisplay
import com.zillit.desktop.feature.home.domain.NoticeDraft
import com.zillit.desktop.feature.home.domain.AudioRecorder
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.NoticeComment
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.ModifyVerdict
import com.zillit.desktop.feature.home.domain.deleteVerdict
import com.zillit.desktop.feature.home.domain.editVerdict
import com.zillit.desktop.feature.home.domain.isActionableBy
import com.zillit.desktop.feature.home.domain.isEditableBy
import com.zillit.desktop.feature.home.domain.nextMatchIndex
import com.zillit.desktop.feature.home.domain.searchMatches
import com.zillit.desktop.feature.home.domain.PickedMedia
import com.zillit.desktop.feature.home.domain.ReadBy
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.domain.NoticeSendState
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.replaceTargets
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
    /** The unit's Media / Docs / Links library is open — Android's Gallery. */
    val libraryOpen: Boolean = false,
    /**
     * The call sheet's "continuation or new?" question, while it is being
     * asked; null otherwise. See [CallSheetPrompt].
     */
    val callSheetPrompt: CallSheetPrompt? = null,
    /**
     * The file just picked or dropped, waiting in the preview dialog for a
     * caption and — for a picture — edits, before it joins the draft. The
     * phones' gallery viewer step between the picker and the composer; see
     * [PendingPreview]. Null when nothing is being previewed.
     */
    val pendingPreview: PendingPreview? = null,
    /**
     * A file the board should hand to the host to save and open — set once
     * the right rendition is known (a call sheet's watermarked copy takes a
     * round trip to name), consumed by the screen, then cleared.
     */
    val pendingOpen: PendingOpen? = null,
    /**
     * A post the board should scroll to — the pinned banner's click. Carries a
     * nonce so the same post can be jumped to twice.
     */
    val jumpTo: JumpTarget? = null,
    /**
     * Whether the menu offers "Publish to Doc Distribution" at all — the
     * hand-off is wired. Rights are checked at the click, with Android's
     * sentence when they are missing, so the item is discoverable either way.
     */
    val canPublishToDistribution: Boolean = false,
    /** The post whose publish is being confirmed, or null. */
    val distributionPrompt: Notice? = null,
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
        get() = notices.forDisplay(history = isHistory)
            .withDateSeparators(nowMillis, history = isHistory)

    /**
     * What the banner over the board shows — the pinned posts, newest first.
     * Empty in history: a record has no "keep this in view".
     */
    val pinnedBanner: List<Notice>
        get() = if (isHistory) emptyList() else notices.pinnedForBanner()

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

    /**
     * Whether Edit and Delete belong in this reply's menu at all — the
     * author's, or an admin's. Untimed: the window is enforced at the click,
     * with a sentence saying why (the phones' toast), rather than by an item
     * that silently vanishes at minute thirty-one.
     */
    fun canAct(comment: NoticeComment): Boolean = comment.isActionableBy(currentUserId, isAdmin)

    /** The same, for a post. */
    fun canAct(notice: Notice): Boolean = notice.isActionableBy(currentUserId, isAdmin)

    /** Whether Edit and Pin belong in this post's menu — the author's, as the server has it. */
    fun canEdit(notice: Notice): Boolean = notice.isEditableBy(currentUserId)

    /**
     * Whether Download is offered on this unit — `download_access`, admins
     * excepted (iOS `getLoginUserAdminAccess() || hasDownloadAccess`).
     */
    val canDownload: Boolean
        get() = isAdmin || selectedUnit?.canDownload == true

    /**
     * Whether the paperclip takes documents only — the call sheet unit, where
     * both phones' attach sheet offers nothing else and hide the microphone
     * (Android `Home.kt:908-911`, `:403`; iOS `ProductionVC.swift:1149`).
     */
    val documentsOnly: Boolean
        get() = selectedUnit?.kind == HomeUnitKind.CallSheet

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
    /** The OS file dialog; an empty result means the user cancelled. */
    val pick: suspend () -> List<PickedMedia> = { emptyList() },
    /**
     * The same dialog, filtered to one kind from the attach sheet. Defaults
     * to the untyped picker so a host (or test) wiring only that still works.
     */
    val pickOf: suspend (PreviewKind) -> List<PickedMedia> = { pick() },
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

/**
 * The board's hand-off to Document Distribution — "Publish to Doc
 * Distribution" on a call sheet's documents. The host wires it to the
 * library's from-tool route; null removes the item.
 *
 * [canPublish] is the phones' gate: posting rights on the Document
 * Distribution tool (`document_distribution_tool`), admin or not
 * (Android `hasDistributionToolPermission`, `DocDistRights.canPost`).
 * [publish] registers the post's file in the library under [folderPath],
 * dated the day it was posted (`yyyy-MM-dd`), without re-uploading it.
 */
class DistributionHook(
    val canPublish: () -> Boolean,
    val publish: suspend (notice: Notice, folderPath: List<String>, folderDate: String) -> ZillitResult<Unit>,
)

/** Which reply the composer is rewriting. */
data class EditingComment(val noticeId: String, val commentId: String)

/**
 * The read-receipts panel's state; [lists] is null while the fetch is out.
 * [commentId] set means the panel is for one reply of the post — its own
 * receipts, on the same route with `?commentId=` (Android `ReadByUserPage`).
 */
data class ReadByView(val noticeId: String, val lists: ReadBy? = null, val commentId: String? = null)

/**
 * The call sheet's two-step question, asked when a document is about to be
 * attached to a board that already has posts.
 *
 * Step one: "in continuation of the existing call sheet, or a new one?" —
 * *Continuation* appends; *New* asks step two, "this sends everything here to
 * History; proceed?" — and only a *Yes* there marks the upload as a
 * replacement. Both phones ask exactly this, in this order (Android
 * `Home.kt:862-903`, iOS `ProductionVC.swift:1087-1123`).
 *
 * [dropped] carries a file that arrived by drag rather than the picker, so
 * the answer can attach it without asking the OS again.
 */
data class CallSheetPrompt(
    val confirmingReplace: Boolean = false,
    val dropped: List<PickedMedia> = emptyList(),
    /** Which kind was chosen on the sheet, so the answer opens the right dialog. */
    val kind: PreviewKind? = null,
    /** The "Replace one document" picker is showing. */
    val picking: Boolean = false,
    /** What that picker offers — see [replaceTargets]. */
    val targets: List<Notice> = emptyList(),
)

/**
 * A file the host should save and open. [nonce] makes two opens of the same
 * file two distinct requests, so the screen's effect fires for each.
 */
/**
 * A picked file waiting in the preview dialog — the phones' gallery viewer
 * between the picker and the composer (`GalleryViewer.kt:1990-2030`): a
 * caption, and for a picture the three edit tools, before it joins the draft.
 *
 * [replace] rides along because the call sheet's continuation answer is given
 * before the picker opens, and the post it belongs to is only built once the
 * preview is sent.
 */
data class PendingPreview(
    /** Everything picked or dropped — the wire takes one per post, so N files become N posts. */
    val files: List<PickedMedia>,
    val replace: Boolean? = null,
    /** "Replace one document": the live message the upload retires. */
    val replaceChatId: String? = null,
)

data class PendingOpen(val attachment: NoticeAttachment, val nonce: Long)

/** A post to scroll to; [nonce] as for [PendingOpen]. */
data class JumpTarget(val noticeId: String, val nonce: Long)

sealed interface HomeFeedEvent {
    /**
     * "Ask for posting rights" under a board this person can only read.
     *
     * The board is the unit, so the request names the unit rather than the
     * module: an admin granting "Home" wholesale is not what was asked for.
     */
    data object RequestPostingRights : HomeFeedEvent

    data object Load : HomeFeedEvent

    /**
     * A different production is open. Everything here belonged to the last
     * one — the board, the tab, the reply target, and the half-typed draft —
     * so it all goes. The board loads again when it is next shown ([Load]);
     * this only forgets.
     */
    data object ProjectChanged : HomeFeedEvent
    data class SelectUnit(val unitId: String) : HomeFeedEvent
    data object Refresh : HomeFeedEvent
    data class DraftChanged(val text: String) : HomeFeedEvent

    /** The paperclip: open the OS picker and attach what comes back. */
    data object Attach : HomeFeedEvent

    /**
     * The attach sheet's answer — Photo, Video, Document or Audio — as both
     * phones offer them before the OS dialog opens. The kind filters that
     * dialog and is checked again after the choice.
     */
    data class AttachKind(val kind: PreviewKind) : HomeFeedEvent

    /** A file dragged in from the OS; [extra] counts the ones beyond the first. */
    data class AttachDropped(val files: List<PickedMedia>) : HomeFeedEvent

    /** The preview dialog's Send: the file as edited, plus its caption. */
    data class PreviewSent(val files: List<PickedMedia>, val caption: String) : HomeFeedEvent

    /** The preview dialog's Cancel — nothing is attached. */
    data object PreviewCancelled : HomeFeedEvent

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

    /** Who has read this post — or one reply of it — opens the receipts panel. */
    data class ShowReadBy(val noticeId: String, val commentId: String? = null) : HomeFeedEvent
    data object DismissReadBy : HomeFeedEvent

    /** Gallery on any post's menu: the unit's Media / Docs / Links library. */
    data object ShowLibrary : HomeFeedEvent
    data object DismissLibrary : HomeFeedEvent

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

    /**
     * The call sheet's question, answered — see [CallSheetPrompt].
     * Continuation and a confirmed replace both go on to attach; New asks
     * the second question; Dismiss drops the whole thing.
     */
    data object CallSheetContinuation : HomeFeedEvent
    /** "Replace one document": show the live documents to pick from. */
    data object CallSheetPickReplacement : HomeFeedEvent
    data class CallSheetReplaceOne(val noticeId: String) : HomeFeedEvent
    data object CallSheetNew : HomeFeedEvent
    data object CallSheetReplaceConfirmed : HomeFeedEvent
    data object CallSheetDismiss : HomeFeedEvent

    /**
     * The phones' "Image Reply": a post's picture, marked up in the editor,
     * posted as a new picture with [caption]. Rides the ordinary media send.
     */
    data class PostImageReply(val picked: PickedMedia, val caption: String) : HomeFeedEvent

    /**
     * Save-and-open a post's file. Through the model rather than straight to
     * the host because a call sheet's document opens as its watermarked copy,
     * which the server names on request; other files pass straight through.
     * [download] marks the menu's explicit Download, which the phones gate on
     * the unit's `download_access` — a click to *read* a file is not gated.
     */
    data class OpenAttachment(
        val noticeId: String,
        val attachment: NoticeAttachment,
        val download: Boolean = false,
    ) : HomeFeedEvent

    /** The screen has handed [HomeFeedUiState.pendingOpen] to the host. */
    data object OpenHandled : HomeFeedEvent

    /** Scroll the board to a post — the pinned banner's click. */
    data class JumpToPost(val noticeId: String) : HomeFeedEvent

    /** "Publish to Doc Distribution": ask first, then register the file in the library. */
    data class StartPublish(val noticeId: String) : HomeFeedEvent
    data object ConfirmPublish : HomeFeedEvent
    data object DismissPublish : HomeFeedEvent

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
// One function per board event, one class per board: splitting the board's
// own state machine across files hides the set without shrinking it. The
// constructor is the host's seams, one per capability, each defaulted to
// "absent" — a holder object would rename the list, not shorten it.
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList")
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
    /**
     * The tab to land on when none is chosen yet — the profile's
     * `default_unit_id`, set from the phones' preferences. Null or a unit
     * this user cannot see falls back to the first tab (Android
     * `handleDefaultUnitSelection`). Read at load time: the profile lands
     * beside the units, and a value captured earlier could be stale.
     */
    private val defaultUnitId: () -> String? = { null },
    /** The library hand-off; null hides the menu item. See [DistributionHook]. */
    private val distribution: DistributionHook? = null,
    /**
     * Carries a refused press to the app frame, which offers to ask an admin.
     *
     * Boards are granted under Home in the rights grid rather than under
     * Tools, which is why the request names [RightsArea.Home] — an admin sent
     * to the wrong half of the grid finds nothing to switch on.
     */
    private val rights: RightsRequestBus? = null,
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

    /**
     * The call sheet's replace answer behind each in-flight post, by local
     * id — a retry must carry the same flag, or a "New" that failed once
     * would land as a "Continuation" on the second try.
     */
    private val replaceByLocalId = mutableMapOf<String, Boolean>()
    private val replaceTargetByLocalId = mutableMapOf<String, String>()

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
            HomeFeedEvent.ProjectChanged -> forgetProject()
            HomeFeedEvent.Refresh -> currentState.selectedUnit?.let { loadNotices(it) }
            HomeFeedEvent.RequestPostingRights -> askForPostingRights()
            is HomeFeedEvent.DraftChanged -> setState {
                // `copy`, not a fresh draft: rebuilding it dropped whatever
                // was attached beside the media — a shared place lost its
                // pin the moment a caption was typed and posted as a plain
                // map picture (seen live, 2026-08-25).
                copy(draft = draft.copy(text = event.text), error = null)
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
            is HomeFeedEvent.ShowReadBy -> showReadBy(event.noticeId, event.commentId)
            HomeFeedEvent.ShowLibrary -> setState { copy(libraryOpen = true) }
            HomeFeedEvent.DismissLibrary -> setState { copy(libraryOpen = false) }
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
                } else if (event.event is HomeRealtimeEvent.ReadByChanged) {
                    refreshReadBy(event.event.messageId)
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
            else -> onFileEvent(event)
        }
    }

    /**
     * Files and the microphone: picking, dropping, recording, the call
     * sheet's question about a pick, the image reply's picture, and opening
     * what a post carries. Split from the main dispatch as the composer's
     * mode events are — the same exhaustive-dispatch shape (see onEvent).
     */
    @Suppress("CyclomaticComplexMethod")
    private fun onFileEvent(event: HomeFeedEvent) {
        when (event) {
            HomeFeedEvent.Attach -> attach()
            is HomeFeedEvent.AttachKind -> attach(kind = event.kind)
            is HomeFeedEvent.AttachDropped -> attach(dropped = event.files)
            HomeFeedEvent.StartRecording -> record()
            HomeFeedEvent.StopRecording -> record(discard = false)
            HomeFeedEvent.CancelRecording -> record(discard = true)
            // The replace answer leaves with the file it was given for.
            HomeFeedEvent.RemoveAttachment -> setState {
                copy(draft = draft.copy(media = null, location = null, replacePrevious = null))
            }
            is HomeFeedEvent.PostImageReply -> postImageReply(event.picked, event.caption)
            is HomeFeedEvent.OpenAttachment -> open(event.noticeId, event.attachment, event.download)
            HomeFeedEvent.OpenHandled -> setState { copy(pendingOpen = null) }
            is HomeFeedEvent.JumpToPost -> setState { copy(jumpTo = JumpTarget(event.noticeId, ++openCounter)) }
            HomeFeedEvent.CallSheetContinuation -> answerCallSheet(replace = false)
            HomeFeedEvent.CallSheetPickReplacement -> setState {
                copy(callSheetPrompt = callSheetPrompt?.copy(picking = true))
            }
            is HomeFeedEvent.CallSheetReplaceOne -> answerCallSheet(replace = false, replaceChatId = event.noticeId)
            HomeFeedEvent.CallSheetNew -> setState {
                copy(callSheetPrompt = callSheetPrompt?.copy(confirmingReplace = true))
            }
            HomeFeedEvent.CallSheetReplaceConfirmed -> answerCallSheet(replace = true)
            HomeFeedEvent.CallSheetDismiss -> setState { copy(callSheetPrompt = null) }
            is HomeFeedEvent.StartPublish -> startPublish(event.noticeId)
            HomeFeedEvent.ConfirmPublish -> publishToDistribution()
            HomeFeedEvent.DismissPublish -> setState { copy(distributionPrompt = null) }
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
            is HomeFeedEvent.PreviewSent -> previewSent(event.files, event.caption)
            HomeFeedEvent.PreviewCancelled -> setState { copy(pendingPreview = null) }
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
                val panel = currentState.readBy ?: return
                val unit = currentState.selectedUnit ?: return
                if (!requirePostingRights()) return
                launchResult(
                    block = { repository.notifyUnread(unit.id, panel.noticeId, panel.commentId) },
                    onSuccess = {
                        setState { copy(info = str(S.desktop_board_everyone_notified)) }
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

    /**
     * Drops everything the previous production left here. A recording in
     * progress is abandoned (its take belonged to that production's board);
     * the in-flight bookkeeping goes with it — a retry across productions
     * would post the old file to the new board.
     */
    private fun forgetProject() {
        if (currentState.recordingSeconds != null) {
            recordingTicker?.cancel()
            media.recorder?.cancel()
        }
        uploadedByLocalId.clear()
        pickedByLocalId.clear()
        replaceByLocalId.clear()
        setState { HomeFeedUiState(nowMillis = nowMillis()) }
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
                callSheetPrompt = null,
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
    private fun attach(
        dropped: List<PickedMedia> = emptyList(),
        /** The call sheet's answer; null when the question has not been put. */
        replace: Boolean? = null,
        /** The attach sheet's kind; null opens the untyped dialog. */
        kind: PreviewKind? = null,
        /** "Replace one document": the live message the upload retires. */
        replaceChatId: String? = null,
    ) {
        if (!requirePostingRights()) return
        if (media.upload == null || currentState.replyTo != null) return
        val unit = currentState.selectedUnit ?: return

        // A call sheet with posts on it asks first — continuation of what is
        // there, or a new sheet that sends the rest to History? Both phones
        // put the question before the picker opens; dropped files wait in
        // the prompt so the answer can attach them.
        if (replace == null && callSheetAsksFirst(unit)) {
            setState {
                val prompt = CallSheetPrompt(dropped = dropped, kind = kind, targets = replaceTargets(notices))
                copy(callSheetPrompt = prompt)
            }
            return
        }

        launch {
            val files = dropped.ifEmpty { if (kind == null) media.pick() else media.pickOf(kind) }
                .filterNot { refusedByCallSheet(unit, it) }
            if (files.isEmpty()) return@launch

            // Straight into the preview, not the draft: the phones put their
            // gallery viewer between the picker and the composer, and a
            // picture only reaches the board once it has been looked at (and
            // possibly drawn on). The replace answer rides with it — the
            // posts are built when Send is pressed, one per file.
            setState {
                copy(
                    pendingPreview = PendingPreview(files, replace, replaceChatId),
                    error = null,
                )
            }
        }
    }

    /**
     * The preview's Send: what came back is what is posted.
     *
     * The caption typed in the dialog becomes the draft's words — the phones'
     * caption field is the message body, not a second line — unless the
     * composer already had something in it, which is kept.
     */
    private fun previewSent(files: List<PickedMedia>, caption: String) {
        val pending = currentState.pendingPreview
        val unit = currentState.selectedUnit ?: return
        if (files.isEmpty()) return
        if (!requirePostingRights()) return

        setState { copy(pendingPreview = null, error = null) }

        // The dialog's Send posts each file as its own post — the wire takes
        // one attachment per message, so the phones send a multi-pick as a
        // burst of messages (QA #6). The caption belongs to the first; a
        // caption repeated under every file would read as a stutter. The
        // composer below stays untouched — the phones' GalleryViewer
        // contract (QA #12).
        files.forEachIndexed { index, picked ->
            val draft = NoticeDraft(
                text = if (index == 0) caption else "",
                media = picked,
                replacePrevious = pending?.replace,
            )
            // Videos get a frame, PDFs their first page — the web's pair.
            // Before the post, so the card and the upload carry the poster.
            if (picked.kind == NoticeKind.Video || picked.isPdf) {
                launch {
                    post(unit, draft.copy(media = media.videoThumbnail(picked)), clearComposer = false)
                }
            } else {
                post(unit, draft, clearComposer = false)
            }
        }
    }

    /** The call sheet's question is owed once the board has anything on it. */
    private fun callSheetAsksFirst(unit: HomeUnit): Boolean =
        unit.kind == HomeUnitKind.CallSheet &&
            currentState.notices.any { it.sendState == NoticeSendState.Sent }

    /**
     * The call sheet unit takes documents only — iOS's attach sheet offers
     * nothing else there, Android's picker set is `PICKER_ITEM_DOCUMENT`
     * alone. Anything else is refused with a sentence, not silently dropped.
     */
    private fun refusedByCallSheet(unit: HomeUnit, picked: PickedMedia): Boolean {
        if (unit.kind != HomeUnitKind.CallSheet || picked.kind == NoticeKind.Document) return false
        setState { copy(error = str(S.desktop_board_only_documents_to, unit.label)) }
        return true
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

        post(unit, draft, clearComposer = true)
    }

    /**
     * Puts a draft on the board and sends it — the composer's send, and the
     * image reply's. [clearComposer] is false for the latter: the picture
     * came from a dialog, and whatever was half-typed below stays.
     */
    private fun post(unit: HomeUnit, draft: NoticeDraft, clearComposer: Boolean) {
        val optimistic = optimisticNotice(newLocalId(), draft, nowMillis())

        // No isSending here: posts queue like a chat's messages do. Each card
        // narrates its own upload, and holding the composer shut until a
        // 30 MB video finished was QA #7.
        setState {
            copy(
                notices = notices + optimistic,
                draft = if (clearComposer) NoticeDraft() else this.draft,
                error = null,
            )
        }

        deliver(unit.id, optimistic, draft.media, draft.location, draft.replacePrevious, draft.replaceChatId)
    }

    /**
     * The phones' "Image Reply": the marked-up picture posts as a new image
     * with its caption, through the same upload-then-post path as any file —
     * Android's `handleImageReply` hands the editor's output to
     * `uploadFilesInDb`, the ordinary media send.
     */
    private fun postImageReply(picked: PickedMedia, caption: String) {
        val unit = currentState.selectedUnit ?: return
        if (!requirePostingRights()) return
        post(unit, NoticeDraft(text = caption, media = picked), clearComposer = false)
    }

    /**
     * The call sheet's answer arrives: the prompt closes and attaching goes
     * ahead with the flag — from the picker, or with the file that was
     * dropped and has been waiting in the prompt.
     */
    private fun answerCallSheet(replace: Boolean, replaceChatId: String? = null) {
        val prompt = currentState.callSheetPrompt ?: return
        setState { copy(callSheetPrompt = null) }
        attach(dropped = prompt.dropped, replace = replace, kind = prompt.kind, replaceChatId = replaceChatId)
    }

    /**
     * Registers the confirmed post's file in the Document Distribution
     * library — the phones' "Publish to Doc Distribution". Filed under the
     * unit's tool name (`Call Sheet` for the call sheet, the board's own
     * name otherwise) and dated the day the post was made, exactly as
     * Android's `setDistributeActionToDD` files it. The file is not
     * re-uploaded; the library takes the storage keys.
     */
    /**
     * The gate comes before the question, as on Android (`Home.kt:2171`):
     * no rights on the Distribution tool, no dialog — the sentence instead.
     */
    private fun startPublish(noticeId: String) {
        val hook = distribution ?: return
        if (!hook.canPublish()) {
            setState { copy(error = NO_DISTRIBUTION_RIGHTS) }
            return
        }
        setState { copy(distributionPrompt = notices.firstOrNull { it.id == noticeId && it.attachment != null }) }
    }

    private fun publishToDistribution() {
        val hook = distribution ?: return
        val notice = currentState.distributionPrompt ?: return
        val unit = currentState.selectedUnit ?: return
        setState { copy(distributionPrompt = null) }
        if (!hook.canPublish()) {
            setState { copy(error = NO_DISTRIBUTION_RIGHTS) }
            return
        }
        val folder = if (unit.kind == HomeUnitKind.CallSheet) CALL_SHEET_FOLDER else unit.label
        launchResult(
            block = { hook.publish(notice, listOf(folder), notice.createdAtMillis.toIsoDate()) },
            onSuccess = { setState { copy(info = str(S.desktop_board_published_to_docdist)) } },
            onError = { error -> setState { copy(error = error.localised()) } },
        )
    }

    /**
     * Names the rendition to save-and-open. A call sheet's PDF opens as the
     * server's watermarked copy — the reader's name stamped on every page, so
     * a leaked sheet says who leaked it (Android `Home.kt:1783-1815`, iOS
     * `ProductionVC+Ext.swift:602-651`); if the server cannot produce one the
     * original opens, as it does on both phones. Everything else passes through.
     */
    private fun open(noticeId: String, attachment: NoticeAttachment, download: Boolean) {
        // The explicit Download is a right of its own on both phones; the
        // sentence is Android's (`download_permission_alert`).
        if (download && !currentState.canDownload) {
            val unit = currentState.selectedUnit?.label ?: str(S.desktop_board_this_unit)
            setState { copy(error = str(S.desktop_board_no_download_rights, unit)) }
            return
        }
        val notice = currentState.notices.firstOrNull { it.id == noticeId }
        // A reply's file rides its parent's id; only the parent's own
        // document is the one the server stamps.
        val watermarks = currentState.selectedUnit?.kind == HomeUnitKind.CallSheet &&
            notice?.kind == NoticeKind.Document && notice.attachment?.media == attachment.media &&
            attachment.isPdf
        if (!watermarks) {
            setState { copy(pendingOpen = PendingOpen(attachment, ++openCounter)) }
            return
        }
        launch {
            val resolved = when (val stamped = repository.watermarkedAttachment(noticeId)) {
                is ZillitResult.Success -> stamped.data
                is ZillitResult.Failure -> attachment
            }
            setState { copy(pendingOpen = PendingOpen(resolved, ++openCounter)) }
        }
    }

    /** Distinguishes two opens of the same file — see [PendingOpen.nonce]. */
    private var openCounter = 0L

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

        // Checked at the moment of the act, not just when the menu was drawn:
        // a menu opened at 29:50 and clicked at 30:10 must not open the editor.
        val now = nowMillis()
        val verdict = comment?.editVerdict(currentUserId(), now) ?: notice.editVerdict(currentUserId(), now)
        if (!verdict.allowed) {
            setState { copy(error = refusal(verdict, deleting = false, reply = comment != null), nowMillis = now) }
            return
        }

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
        val target = currentState.notices.firstOrNull { it.id == noticeId } ?: return
        val comment = commentId?.let { id -> target.comments.firstOrNull { it.id == id } }
        if (commentId != null && comment == null) return

        // The phones' rule, at the click: an admin removes anything, anyone
        // else only their own and only inside the window — and the refusal
        // says which of those it was.
        val now = nowMillis()
        val verdict = comment?.deleteVerdict(currentUserId(), isAdmin(), now)
            ?: target.deleteVerdict(currentUserId(), isAdmin(), now)
        if (!verdict.allowed) {
            setState { copy(error = refusal(verdict, deleting = true, reply = comment != null), nowMillis = now) }
            return
        }

        // A whole post: off the board at once, back with the error if refused.
        if (comment == null) {
            val removedNotice = target
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
        val removed = comment

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

    /**
     * Somebody else read a post while its read-by panel is open.
     *
     * Only the panel on screen is refetched, and only when the receipt names
     * the post it is showing — the same gate both phones apply
     * (`ReadByUserPage.kt:455`). A receipt for anything else is a message the
     * user is not looking at.
     *
     * The reply case reads the same way: a panel opened on a comment names
     * that comment, and the server sends the receipt under the post's id, so
     * matching on the post is what refreshes either view of it.
     */
    private fun refreshReadBy(messageId: String) {
        val open = currentState.readBy ?: return
        if (open.noticeId != messageId) return
        showReadBy(open.noticeId, open.commentId)
    }

    private fun showReadBy(noticeId: String, commentId: String? = null) {
        setState { copy(readBy = ReadByView(noticeId, commentId = commentId)) }

        launchResult(
            block = { repository.readBy(noticeId, commentId) },
            onSuccess = { lists ->
                setState {
                    // Only if the panel is still open for this post (and reply).
                    if (readBy?.noticeId == noticeId && readBy.commentId == commentId) {
                        copy(readBy = ReadByView(noticeId, lists, commentId))
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
    /**
     * Asks an admin for the right to post to the board being read.
     *
     * Named for the unit rather than for Home: the rights grid grants boards
     * one at a time, and "give me Home" is not a row anyone can switch on.
     */
    private fun askForPostingRights() {
        val unit = currentState.selectedUnit ?: return
        rights?.ask(unit.label, RightsKind.Post, RightsArea.Home)
        setState {
            copy(
                error = if (rights == null) {
                    noPostingRights(unit)
                } else {
                    str(S.desktop_board_asking_for_posting_rights, unit.label)
                },
            )
        }
    }

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
                copy(forwarding = null, error = str(S.desktop_board_only_text_and_documents_to, target.label))
            }
            return
        }

        setState { copy(forwarding = null) }
        launchResult(
            block = { repository.forwardNotice(unitId, notice, newLocalId()) },
            onSuccess = {
                setState { copy(info = str(S.desktop_board_forwarded_to, target.label)) }
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
        // caption is nothing: a blank card on everyone's board. The call
        // sheet's replace answer rides with it, for the same reason.
        deliver(
            unit.id,
            failed,
            picked = pickedByLocalId[localId],
            location = failed.location,
            replacePrevious = replaceByLocalId[localId],
            replaceChatId = replaceTargetByLocalId[localId],
        )
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
        replacePrevious: Boolean? = null,
        replaceChatId: String? = null,
    ) {
        val localId = optimistic.localId ?: return
        picked?.let { pickedByLocalId[localId] = it }
        replacePrevious?.let { replaceByLocalId[localId] = it }
        replaceChatId?.let { replaceTargetByLocalId[localId] = it }

        launchResult(
            block = {
                val uploaded = when (val stored = uploadFor(localId, picked)) {
                    is ZillitResult.Failure -> return@launchResult stored
                    is ZillitResult.Success -> stored.data
                }
                // A media post whose file went missing must fail loudly, not
                // post its empty caption — the blank card the tester saw.
                //
                // A **location** post is exempt: it is a place, not a file.
                // Its map image is decoration the production may not even be
                // able to make — `staticMap` answers null with no Google key
                // in the production's configuration, or with Maps unreachable
                // — and the point is the post. Without this exemption every
                // location shared on a keyless production died on
                // "The file is no longer attached", which is not what
                // happened and not something the user can act on.
                if (uploaded == null && optimistic.kind != NoticeKind.Text && location == null) {
                    return@launchResult ZillitResult.Failure(
                        ZillitError.Storage(
                            technical = "media post retried with no file to upload",
                            userMessage = str(S.desktop_board_file_no_longer_attached),
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
                repository.postNotice(
                    unitId, optimistic.body, localId, uploaded, location, replacePrevious, replaceChatId,
                )
            },
            onSuccess = { saved ->
                // Replace rather than append: the optimistic card and the
                // server's copy are the same post, and appending would show it
                // twice.
                uploadedByLocalId.remove(localId)
                pickedByLocalId.remove(localId)
                replaceByLocalId.remove(localId)
                replaceTargetByLocalId.remove(localId)
                // "Replace one document": the retired message leaves the board
                // the moment the server names it, as the web's
                // `removeMessagesFromList` on `replaced_chat_id`.
                saved.replacedNoticeId?.let { gone -> setState { copy(notices = notices.filterNot { it.id == gone }) } }
                setState {
                    copy(
                        notices = notices.replacing(localId, saved),
                        uploadProgress = uploadProgress - localId,
                    )
                }
                // A "New" call sheet moved every earlier post to History on
                // the server; the board on screen still shows them until it
                // is read again. Android learns this from a socket
                // multi-delete and refetches — the refetch is the part that
                // matters.
                if (replacePrevious == true) {
                    currentState.selectedUnit?.takeIf { it.id == unitId }?.let(::loadNotices)
                }
            },
            onError = { error ->
                setState {
                    copy(
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
                setState {
                    copy(
                        isLoadingUnits = false,
                        units = units,
                        // The person's chosen landing tab, on first arrival
                        // only — a tab they picked since is theirs to keep.
                        selectedUnitId = selectedUnitId
                            ?: defaultUnitId()?.takeIf { wanted -> units.visibleTabs().any { it.id == wanted } },
                    )
                }
                // Open the tab immediately: a tab strip with nothing under it
                // reads as a broken screen.
                currentState.selectedUnit?.let(::loadNotices)
            },
            onError = { setState { copy(isLoadingUnits = false, error = it.localised()) } },
        )
    }

    private fun loadNotices(unit: HomeUnit) {
        // Re-read on every board load, not once at startup: the profile that
        // carries is_admin and the user id loads in parallel with the units,
        // and a value captured before it arrived would stick wrong.
        setState {
            copy(
                isAdmin = isAdmin(),
                currentUserId = currentUserId(),
                canPublishToDistribution = distribution != null,
            )
        }

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

/**
 * The sentence for a refused edit or delete — the phones' own copy, so a
 * crew member who knows the app from their phone reads the same rule here.
 * Android's edit toast, iOS's delete-window alert (Android's says "2 Hours"
 * for a 30-minute rule — a stale string, not the rule), Android's ownership
 * and not-yet-delivered toasts.
 */
internal fun refusal(verdict: ModifyVerdict, deleting: Boolean, reply: Boolean): String? {
    val key = when (verdict) {
        ModifyVerdict.Allowed -> return null
        ModifyVerdict.NotSent -> S.message_not_delivered_yet
        ModifyVerdict.NotOwner -> refusalKey(
            deleting, reply,
            deleteReply = S.desktop_board_cannot_delete_others_replies,
            deleteMessage = S.desktop_board_cannot_delete_others_messages,
            editReply = S.desktop_board_cannot_edit_others_replies,
            editMessage = S.desktop_board_cannot_edit_others_messages,
        )
        ModifyVerdict.WindowClosed -> refusalKey(
            deleting, reply,
            deleteReply = S.desktop_board_delete_window_closed_reply,
            deleteMessage = S.desktop_board_delete_window_closed_message,
            editReply = S.desktop_board_edit_window_closed_reply,
            editMessage = S.desktop_board_edit_window_closed_message,
        )
    }
    return str(key)
}

/** The one of four sentences for an action (edit or delete) on a kind of post (message or reply). */
@Suppress("LongParameterList") // Four keys, named at every call site.
private fun refusalKey(
    deleting: Boolean,
    reply: Boolean,
    deleteReply: String,
    deleteMessage: String,
    editReply: String,
    editMessage: String,
): String = when {
    deleting && reply -> deleteReply
    deleting -> deleteMessage
    reply -> editReply
    else -> editMessage
}


/** The bytes are all in storage; the server is writing the post. */
private const val UPLOAD_DONE = 100

/** Where the library files a call sheet — Android's `DocDistTool.CALL_SHEET`. */
private const val CALL_SHEET_FOLDER = "Call Sheet"

/** Android's `you_dont_have_distribution_rights` (`strings.xml:3306`). */
private val NO_DISTRIBUTION_RIGHTS: String get() = str(S.you_dont_have_distribution_rights_on_this_unit)

/** `yyyy-MM-dd` in the viewer's zone — the library's `folder_date`; today when the post has no clock. */
private fun Long.toIsoDate(): String {
    val millis = if (this > 0) this else kotlin.time.Clock.System.now().toEpochMilliseconds()
    val date = kotlinx.datetime.Instant.fromEpochMilliseconds(millis)
        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
    return date.toString()
}

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
private fun noPostingRights(unit: HomeUnit): String = str(S.desktop_board_no_posting_rights_ask_admin, unit.label)

/** The card shown before the server answers — the send's own local echo. */
private fun optimisticNotice(localId: String, draft: NoticeDraft, now: Long): Notice = Notice(
    id = localId,
    // A bare location share posts its address as the body — the card here has
    // to say the same thing the server will echo back, or the address appears
    // the moment the echo replaces this card. See `HomeFeedRepositoryImpl`.
    body = draft.trimmed.ifBlank { draft.location?.address.orEmpty() },
    authorName = str(S.you),
    createdAtMillis = now,
    kind = when {
        draft.location != null -> NoticeKind.Location
        else -> draft.media?.kind ?: NoticeKind.Text
    },
    location = draft.location,
    sendState = NoticeSendState.Sending,
    localId = localId,
    // The picked file rides the card so its preview shows while the bytes
    // travel — an image as itself, a video or PDF as its poster frame.
    attachment = draft.media?.let { picked ->
        NoticeAttachment(
            media = "",
            fileName = picked.name,
            contentType = picked.contentType,
            durationMillis = picked.durationMillis,
            sizeBytes = picked.bytes.size.toLong(),
            widthPx = picked.thumbnailWidth,
            heightPx = picked.thumbnailHeight,
            localBytes = if (picked.kind == NoticeKind.Image) picked.bytes else picked.thumbnailBytes,
        )
    },
)
