package com.zillit.desktop.feature.home.ui

import com.zillit.desktop.core.permissions.RightsArea
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.common.SentenceCase
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.media.TrackedSend
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.workspace.forward.ForwardForm
import com.zillit.desktop.core.workspace.forward.ForwardItem
import com.zillit.desktop.core.workspace.forward.ForwardPick
import com.zillit.desktop.core.workspace.forward.ForwardReach
import com.zillit.desktop.core.workspace.forward.ForwardSection
import com.zillit.desktop.core.workspace.forward.ForwardStepOutcome
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.datetime.toLocalDateTime
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.pageStamp
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.BoardRow
import com.zillit.desktop.feature.home.domain.HomeRealtimeEvent
import com.zillit.desktop.feature.home.domain.withDateSeparators
import com.zillit.desktop.feature.home.domain.pinnedForBanner
import com.zillit.desktop.feature.home.domain.HomeUnitKind
import com.zillit.desktop.feature.home.domain.applyRealtime
import com.zillit.desktop.feature.home.domain.matchesUnit
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
import com.zillit.desktop.feature.home.domain.asForwardItem
import com.zillit.desktop.feature.home.domain.replaceTargets
import com.zillit.desktop.feature.home.domain.sameAs
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
    /** The post the forward picker is open for, or null when it is closed. */
    val forwarding: Notice? = null,
    /**
     * Where that post may go: this board's units first, then everything the
     * host can reach — the other boards, the chat halves, the mailbox, the
     * film tools the post's kind and the viewer's rights allow. Empty until
     * the picker opens and the reach has answered.
     */
    val forwardSections: List<ForwardSection> = emptyList(),
    /**
     * The questions a film tool asked before it will take this post — a
     * document's name and colour, a scene, a date. Null when nothing is
     * being asked; [forwardingItem] is what the answers will be sent with.
     */
    val forwardForm: ForwardForm? = null,
    val forwardingItem: ForwardItem? = null,
    /** True while the form's answers are on the wire. */
    val forwardSending: Boolean = false,
    /**
     * The web's selection mode, armed from a post's menu: a batch of posts
     * ticked for one action, with the composer replaced by a bar saying how
     * many are picked (`UnitChatMessageBox` `isSelectionMode`). Null when the
     * board is in its ordinary state.
     */
    val selection: BoardSelection? = null,
    /**
     * The question asked before anything is withdrawn — the web's
     * `openCommonPopup` over Delete, on a batch of posts or on one reply.
     * Null when nothing is being asked.
     */
    val deletePrompt: DeletePrompt? = null,
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
     * The board's history view — the call sheet's and the production report
     * chat's, the two boards whose posts are documents that replace each other.
     *
     * The same board in a different mode, as on the web: the posts the board no
     * longer holds, read with `deleted=` ([HomeFeedRepository.loadHistory]),
     * ordered by publication rather than last edit, and no composer — history is
     * a record, not a conversation.
     */
    val isHistory: Boolean = false,
    /** There may be posts behind the oldest one shown; scrolling up asks. */
    val hasOlder: Boolean = false,
    /** An older page is on its way. */
    val loadingOlder: Boolean = false,
    /**
     * A record to look through, never to add to: the File Cabinet's viewer.
     * No composer, no replying, editing, pinning or deleting, no call sheet
     * history switch — see [readsOnly] for the events that still go through.
     */
    val readOnly: Boolean = false,
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
        get() = notices.forDisplay(history = byCreated)
            .withDateSeparators(nowMillis, history = byCreated)

    /**
     * Ordered by when a post was written, not when it last changed: the call
     * sheet's history, and an archive — the web sorts the File Cabinet's
     * records on `created` too.
     */
    private val byCreated: Boolean get() = isHistory || readOnly

    /**
     * What the banner over the board shows — the pinned posts, newest first.
     * Empty in history: a record has no "keep this in view".
     */
    val pinnedBanner: List<Notice>
        get() = if (isHistory || readOnly) emptyList() else notices.pinnedForBanner()

    /** Matching post ids, in board order. Empty until two characters. */
    val searchMatches: List<String>
        get() = searchQuery?.let { notices.forDisplay(history = byCreated).searchMatches(it) }
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
    fun canAct(comment: NoticeComment): Boolean = !readOnly && comment.isActionableBy(currentUserId, isAdmin)

    /** The same, for a post. */
    fun canAct(notice: Notice): Boolean = !readOnly && notice.isActionableBy(currentUserId, isAdmin)

    /** Whether Edit and Pin belong in this post's menu — the author's, as the server has it. */
    fun canEdit(notice: Notice): Boolean = !readOnly && notice.isEditableBy(currentUserId)

    /**
     * Whether Download is offered on this unit — `download_access`, admins
     * excepted (iOS `getLoginUserAdminAccess() || hasDownloadAccess`).
     */
    val canDownload: Boolean
        get() = isAdmin || selectedUnit?.canDownload == true

    /**
     * Whether the paperclip takes documents only — the call sheet unit, where
     * both phones' attach sheet offers nothing else and hide the microphone
     * (Android `Home.kt:908-911`, `:403`; iOS `ProductionVC.swift:1149`), and
     * the production report chat, which the web gates the same way (its attach
     * menu shows the document row alone for `production_report_tool`, and
     * hides the mic for `production_report_label`).
     */
    val documentsOnly: Boolean
        get() = selectedUnit?.kind?.isDocumentUpload == true

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
        get() = !isHistory && !readOnly && selectedUnit != null && selectedUnit?.kind != HomeUnitKind.Calendar

    /** Whether a post from this user would actually be accepted. */
    val canCompose: Boolean
        get() = showsComposer && selectedUnit?.let { it.canPost || isAdmin } == true
}

/**
 * What the composer can capture, gathered because they travel together: the
 * picker, the uploader, the microphone, and the video poster extractor. All
 * default to "absent", which quietly removes the affordance they power.
 */
@Suppress("LongParameterList") // One seam per platform capability; a holder would rename, not reduce.
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
    /**
     * Binds a post to the production it is sent in ([BoardPin]); asked once
     * per post as Send is pressed. Null keeps the post on the open
     * production's uploader and repository.
     */
    val pin: () -> BoardPin? = { null },
    /** The production open now — what a pinned post is compared with before it touches the screen. */
    val currentProjectId: () -> String? = { null },
    /** The app-wide Uploads tray every post with a file reports to. */
    val tracker: com.zillit.desktop.core.media.MediaSendTracker? = null,
)

/**
 * A board post bound, the moment it is sent, to its production — the
 * storage its file goes into and the production its post is written to —
 * so a video still uploading when the user switches production does not
 * land on the new one's board.
 */
class BoardPin(
    val projectId: String,
    val projectName: String,
    /** Names the production on the post's own request. */
    val options: com.zillit.desktop.core.network.CallOptions,
    val upload: suspend (PickedMedia, (Int) -> Unit) -> ZillitResult<UploadedNoticeMedia>,
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
 * What a batch of ticked posts is gathered *for*.
 *
 * One entry today. The web's mode carries Delete, Share and Distribute
 * (`DROPDOWN_OPTIONS`), and the bar draws a different button per action —
 * the shape is kept so the next one is an entry, not a rewrite.
 */
enum class SelectionAction { Delete }

/**
 * Selection mode: which action, and the posts ticked so far.
 *
 * The web arms this from a post's own menu with that post already ticked
 * (`optionsHandler` → `setSelectedMessages([msgDetails])` +
 * `openMessageSelectionMode(key)`), which is why [ids] is never empty when
 * the mode opens.
 */
data class BoardSelection(val action: SelectionAction, val ids: Set<String> = emptySet())

/** What a delete is about to remove, while the question is on screen. */
sealed interface DeletePrompt {
    /** Posts — one from a card's menu, or every ticked one from the bar. */
    data class Posts(val ids: List<String>) : DeletePrompt

    /** One reply of a post, which the web asks about on its own. */
    data class Reply(val noticeId: String, val commentId: String) : DeletePrompt
}

/**
 * The read-receipts panel's state; [lists] is null while the fetch is out.
 * [commentId] set means the panel is for one reply of the post — its own
 * receipts, on the same route with `?commentId=` (Android `ReadByUserPage`).
 */
data class ReadByView(val noticeId: String, val lists: ReadBy? = null, val commentId: String? = null)

/**
 * The question asked once a document has been chosen for a board that already
 * has posts — the call sheet's and the production report chat's, the web's
 * `ContinuationModal`.
 *
 * Three answers, as cards: *Continuation* appends; *New* replaces every live
 * message with this upload and sends the rest to History; *Replace* swaps one
 * live document, which moves to History with its comments. *New* then asks
 * once more ("everything here goes to History; proceed?") — the phones' second
 * step (Android `Home.kt:889-903`, iOS `ProductionVC.swift:1105-1118`), kept
 * because it is the one irreversible answer.
 *
 * [files] are the documents already in hand, waiting for the answer so they
 * can be attached without asking the OS again.
 */
data class CallSheetPrompt(
    val confirmingReplace: Boolean = false,
    /** The picked or dropped documents this answer applies to. */
    val files: List<PickedMedia> = emptyList(),
    /** The card ticked so far; null until one is. */
    val choice: UploadChoice? = null,
    /** The live document Replace will retire. Seeded with the newest. */
    val replaceNoticeId: String? = null,
    /** The documents Replace may swap — see [replaceTargets]. */
    val targets: List<Notice> = emptyList(),
) {
    /** Replace is the one answer that needs a second decision before Send means anything. */
    val canSend: Boolean
        get() = choice != null && (choice != UploadChoice.Replace || !replaceNoticeId.isNullOrBlank())
}

/** The three answers to the upload question — the web's `ContinuationModal` cards. */
enum class UploadChoice {
    /** Append beside what is already on the board. */
    Continuation,

    /** This upload replaces every live message; the rest go to History. */
    New,

    /** One live document is swapped and moves to History with its comments. */
    Replace,
}

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
     * Whether somebody is looking at this board: its window focused and the
     * board composed. A board read is a "read by" on everyone else's screen
     * and clears the unit's badge, so it waits for this — see
     * [HomeFeedViewModel.readBoard].
     */
    data class Watching(val watching: Boolean) : HomeFeedEvent

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

    /**
     * Selection mode and the delete question — everything between a menu's
     * Delete and the withdrawal itself. Grouped under one marker so the
     * event loop reads them as one concern (and stays inside detekt's
     * sixty lines).
     */
    sealed interface Picking : HomeFeedEvent

    /**
     * A post's menu Delete. Not a delete: it ticks that post and opens
     * selection mode, so more can join it before the one question is asked
     * — the web's `DELETE_MESSAGE` handler, exactly.
     */
    data class SelectForDelete(val noticeId: String) : Picking

    /** A card clicked (or its box ticked) while selection mode is open. */
    data class ToggleSelected(val noticeId: String) : Picking

    /** The bar's X: selection mode closes and nothing is deleted. */
    data object CancelSelection : Picking

    /** The bar's Delete: ask about every ticked post. */
    data object AskDeleteSelected : Picking

    /** A reply's Delete: ask about that one reply. */
    data class AskDeleteComment(val noticeId: String, val commentId: String) : Picking

    /** The question's No. */
    data object CancelDeletePrompt : Picking

    /** The question's Yes — what was asked about is withdrawn. */
    data object ConfirmDelete : Picking

    /** Pin a post to the top of the board, or take it back down. */
    data class TogglePin(val noticeId: String) : HomeFeedEvent

    /** Open the forward picker for a post; each destination gets its own copy. */
    data class StartForward(val noticeId: String) : HomeFeedEvent

    /** One unit of this board — the path every forward took before the picker grew. */
    data class ForwardTo(val unitId: String) : HomeFeedEvent

    /** The picker's Send: every ticked destination, this board's and the host's alike. */
    data class ForwardToAll(val targetIds: List<String>) : HomeFeedEvent

    /**
     * A destination that asks something first — a tool's form, the mailbox
     * composer. The host takes over; the picker closes.
     */
    data class ForwardStepPicked(val targetId: String) : HomeFeedEvent

    /** The tool form's Send, answers by field key. */
    data class SubmitForwardForm(val answers: Map<String, String>) : HomeFeedEvent

    /** The tool form's Cancel. */
    data object CancelForwardForm : HomeFeedEvent
    data object CancelForward : HomeFeedEvent

    /** Who has read this post — or one reply of it — opens the receipts panel. */
    data class ShowReadBy(val noticeId: String, val commentId: String? = null) : HomeFeedEvent
    data object DismissReadBy : HomeFeedEvent

    /** The board was scrolled to its top (or "Show older" clicked): the page behind. */
    data object LoadOlder : HomeFeedEvent

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
     * The upload question — see [CallSheetPrompt]. A card is ticked, then
     * Send acts on it: Continuation and Replace attach straight away, New
     * asks once more and only [CallSheetReplaceConfirmed] goes through.
     * Dismiss drops the whole thing, documents included.
     */
    data class CallSheetChoice(val choice: UploadChoice) : HomeFeedEvent
    /** Which live document a Replace retires. */
    data class CallSheetReplaceOne(val noticeId: String) : HomeFeedEvent
    data object CallSheetSend : HomeFeedEvent
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
    /**
     * The File Cabinet's viewer: the board with its write side switched off.
     * The model refuses every event that is not a read ([readsOnly]) as well
     * as the screen not offering it — a stray key or a stale menu changes
     * nothing in an archive.
     */
    private val readOnly: Boolean = false,
    /**
     * Everywhere a post can go beyond this board's own units — the web's
     * forward modal in full. Null leaves the picker showing this board
     * alone, which is what it showed before the reach existed.
     */
    private val reach: ForwardReach? = null,
    /**
     * Which board this is, in the reach's terms (`home`, `info`,
     * `confidentialinfo`…), so it is not offered its own units twice.
     */
    private val boardKey: String? = null,
) : ZillitViewModel<HomeFeedUiState, HomeFeedEvent, Nothing>(HomeFeedUiState(readOnly = readOnly)) {

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

    /**
     * Every post with a file still on its way, by local id: where it is
     * going and the work doing it — so the app-wide Uploads tray can cancel
     * or retry it from any production, after this board has moved on.
     */
    private class PendingPost(
        val unitId: String,
        val optimistic: Notice,
        val location: GeoPoint?,
        val pin: BoardPin?,
        var job: kotlinx.coroutines.Job? = null,
    )

    private val pendingPosts = mutableMapOf<String, PendingPost>()

    /**
     * The page each tab last showed, by unit id — this session's own memory of
     * the boards behind the strip.
     *
     * Switching tabs used to empty the board and refetch, so going back to a
     * tab read seconds ago was a spinner and a round trip. This is the cheaper
     * answer, and a nearer one than disk: it holds what is *on screen*,
     * socket arrivals and all, not only what the last fetch returned.
     *
     * Emptied with the production ([forgetProject]), like every other map
     * here: one shoot's posts must never appear under another's name. What it
     * held goes into [keptProductions], under that production's id.
     */
    private val boards = mutableMapOf<String, List<Notice>>()

    /**
     * What each production's board last showed — its tab strip and the pages
     * behind every tab — by production id. Not the tab that was open: coming
     * back to a production lands where opening it does, on the landing tab,
     * not wherever the last visit left off.
     *
     * A switch used to drop all of it, so going back to a production read a
     * minute ago was the tab strip from disk and then the board from disk,
     * each behind its own spinner, and each queued behind the hundred-odd
     * answers a switch writes to the same database at once. Kept here, coming
     * back draws at once and the refresh runs behind it.
     *
     * By production, so one shoot's posts can only ever come back under its
     * own name; a few of them, because the disk copy is the record and this
     * is only the fast way back to it.
     */
    private val keptProductions = mutableMapOf<String, KeptProduction>()

    private class KeptProduction(
        val units: List<HomeUnit>,
        val boards: Map<String, List<Notice>>,
    )

    /**
     * The production this board's state belongs to: named by the first load
     * after a switch, cleared by the switch itself. Null when the host does
     * not say which production is open, which keeps nothing across switches.
     */
    private var shownProduction: String? = null

    /**
     * The reads behind the board on screen — tab strip, pages, the seeds from
     * disk. Cancelled with their production: a page still on its way when
     * the switch happens belongs to the production that asked for it, and
     * landing on the next one put that one's tabs or posts under the wrong
     * name (and skipped the next one's disk seed, which only runs over an
     * empty strip).
     */
    private val productionReads = mutableListOf<Job>()

    /**
     * The unit whose board read is out right now, or null when none is.
     *
     * Only [onUnitsLoaded] asks: a cold open seeds the strip from disk and
     * opens the tab under it, and the live strip arriving a moment later must
     * not ask for the same board a second time. It is *not* "has this unit
     * been read" — a units change with no read in flight still re-reads the
     * board, because `chat:archived` arrives that way and does move the posts.
     */
    private var boardReadInFlight: String? = null

    /**
     * Whether the board is on a focused, composed screen — iOS reads a new
     * post only while its page is the one showing (`ProjectObserver.swift:1608-1609`,
     * reset as the view disappears, `InfoPageViewController.swift:514-520`).
     * True until the screen says otherwise, so a host that never reports
     * keeps the old behaviour.
     */
    private var watching = true

    /** A read this board owes from while nobody was looking — sent on [watching]'s return. */
    private var readOwed = false

    /** Whether a post belongs to the production open now — a pinned one may not. */
    private fun isHere(pin: BoardPin?): Boolean = pin == null || pin.projectId == media.currentProjectId()

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
        if (readOnly && !event.readsOnly()) return
        when (event) {
            HomeFeedEvent.Load -> loadUnits()
            is HomeFeedEvent.Watching -> onWatching(event.watching)
            HomeFeedEvent.ProjectChanged -> forgetProject()
            HomeFeedEvent.Refresh -> currentState.selectedUnit?.let {
                if (currentState.isHistory) loadHistoryPage(it) else loadNotices(it)
            }
            HomeFeedEvent.LoadOlder -> loadOlder()
            HomeFeedEvent.RequestPostingRights -> askForPostingRights()
            is HomeFeedEvent.DraftChanged -> setState {
                // `copy`, not a fresh draft: rebuilding it dropped whatever
                // was attached beside the media — a shared place lost its
                // pin the moment a caption was typed and posted as a plain
                // map picture (seen live, 2026-08-25).
                copy(
                    draft = draft.copy(text = SentenceCase.applied(draft.text, event.text)),
                    error = null,
                )
            }
            HomeFeedEvent.Send -> send()
            is HomeFeedEvent.Retry -> retry(event.localId)
            is HomeFeedEvent.DeleteComment -> delete(event.noticeId, event.commentId)
            is HomeFeedEvent.DeleteNotice -> delete(event.noticeId)
            is HomeFeedEvent.Picking -> picking(event)
            is HomeFeedEvent.TogglePin -> togglePin(event.noticeId)
            is HomeFeedEvent.StartForward -> startForward(event.noticeId)
            is HomeFeedEvent.ForwardTo -> forwardTo(event.unitId)
            is HomeFeedEvent.ForwardToAll -> forwardToAll(event.targetIds)
            is HomeFeedEvent.ForwardStepPicked -> forwardStep(event.targetId)
            is HomeFeedEvent.SubmitForwardForm -> submitForwardForm(event.answers)
            HomeFeedEvent.CancelForwardForm -> setState {
                copy(forwardForm = null, forwardingItem = null, forwardSending = false)
            }
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
            is HomeFeedEvent.ShowHistory -> showHistory(event.history)
            // A units change refetches the tab strip — rights may have moved,
            // and a tab the user can no longer see must go. A board change
            // re-reads only the open board. Everything else is a pure merge;
            // `applyRealtime` handles our own post arriving back.
            is HomeFeedEvent.Realtime -> onRealtime(event.event)
            is HomeFeedEvent.SelectUnit -> selectUnit(event.unitId)
            else -> onFileEvent(event)
        }
    }

    /**
     * One socket event, applied.
     *
     * Three costs, deliberately distinguished. A units change is the dear one
     * — it refetches the tab strip *and* the board — and is reserved for the
     * events that really move the strip. A board change re-reads one board,
     * and only when it is the one on screen: a comment on a unit nobody is
     * looking at is the badge's business, and the board is read afresh when
     * that tab is opened anyway. Everything else merges in place.
     */
    private fun onRealtime(event: HomeRealtimeEvent) {
        // A record does not move. Every delta here belongs to the live board
        // behind it, which is re-read the moment History is left — applying
        // them to the record would post today's traffic into the archive.
        if (currentState.isHistory) return
        when (event) {
            HomeRealtimeEvent.UnitsChanged -> loadUnits()

            is HomeRealtimeEvent.ReadByChanged -> refreshReadBy(event.messageId)

            is HomeRealtimeEvent.BoardChanged -> {
                val open = currentState.selectedUnit
                if (open != null && event.matchesUnit(open.id)) loadNotices(open)
            }

            else -> {
                setState { copy(notices = notices.applyRealtime(event, selectedUnitId)) }
                currentState.selectedUnitId?.let { open -> boards[open] = currentState.notices }
                // The offline copy keeps up with the screen. Without this a
                // post that arrived while somebody watched is missing from the
                // next cold open, and worse, a *deleted* one comes back.
                launch { fileRealtime(event) }
                // A post landing on the open board is read as it lands —
                // the web emits on `home_message_added` while visible.
                val added = event as? HomeRealtimeEvent.NoticeAdded
                if (added?.unitId != null && added.unitId == currentState.selectedUnitId) {
                    readBoard(added.unitId)
                }
            }
        }
    }

    /** One socket delta, written through to wherever the board is kept. */
    private suspend fun fileRealtime(event: HomeRealtimeEvent) {
        when (event) {
            is HomeRealtimeEvent.NoticeAdded ->
                repository.rememberNotice(event.unitId, event.notice, event.wire)

            is HomeRealtimeEvent.NoticeEdited ->
                repository.rememberNotice(event.unitId, event.notice, event.wire)

            is HomeRealtimeEvent.NoticeDeleted ->
                repository.forgetNotice(event.unitId, event.noticeId)

            else -> Unit
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
            is HomeFeedEvent.CallSheetChoice -> setState {
                // Replace seeds the newest document, so the common case
                // ("fix the one I just posted") is one click, as on the web.
                val prompt = callSheetPrompt ?: return@setState this
                copy(
                    callSheetPrompt = prompt.copy(
                        choice = event.choice,
                        replaceNoticeId = prompt.replaceNoticeId ?: prompt.targets.firstOrNull()?.id,
                    ),
                )
            }
            is HomeFeedEvent.CallSheetReplaceOne -> setState {
                val prompt = callSheetPrompt ?: return@setState this
                val known = prompt.targets.any { it.id == event.noticeId }
                copy(callSheetPrompt = if (known) prompt.copy(replaceNoticeId = event.noticeId) else prompt)
            }
            HomeFeedEvent.CallSheetSend -> sendWithAnswer()
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
        // A post pinned to the production being left keeps uploading, and
        // keeps what its retry needs; everything else belonged to the board.
        val carried = pendingPosts.filterValues { it.pin != null }.keys
        listOf(uploadedByLocalId, pickedByLocalId, replaceByLocalId, replaceTargetByLocalId).forEach { map ->
            map.keys.retainAll(carried)
        }
        pendingPosts.keys.retainAll(carried)
        keepShownProduction()
        productionReads.forEach { it.cancel() }
        productionReads.clear()
        boards.clear()
        boardReadInFlight = null
        shownProduction = null
        setState { HomeFeedUiState(readOnly = readOnly, nowMillis = nowMillis()) }
    }

    /**
     * Files what is on screen under the production it belongs to, for the
     * next time that production is opened. Posts only the server has: a card
     * still going up is the upload tray's, and a failed one is this session's.
     */
    private fun keepShownProduction() {
        val production = shownProduction ?: return
        val state = currentState
        if (readOnly || state.tabs.isEmpty()) return
        val pages = boards.mapValues { (_, posts) -> posts.sentOnly() }.toMutableMap()
        state.selectedUnitId?.let { open -> pages[open] = state.notices.sentOnly() }
        keptProductions.remove(production)
        keptProductions[production] = KeptProduction(state.units, pages)
        while (keptProductions.size > MAX_KEPT_PRODUCTIONS) keptProductions.remove(keptProductions.keys.first())
    }

    /**
     * Puts a production's kept board back on screen, before any read — the
     * reads that follow refresh it rather than open it. False when this
     * production has not been shown here before.
     */
    private fun restoreKeptProduction(): Boolean {
        if (readOnly || currentState.tabs.isNotEmpty()) return false
        val back = shownProduction?.let(keptProductions::get) ?: return false
        boards.putAll(back.boards)
        // The landing tab, as on a first open — never the one last left open.
        val landing = landingTab(back.units) ?: back.units.visibleTabs().firstOrNull()?.id
        val page = landing?.let(back.boards::get).orEmpty()
        setState {
            copy(
                units = back.units,
                selectedUnitId = landing,
                notices = page,
                // As on a tab switch to a kept page: what lies behind it is
                // learned again by reading on.
                hasOlder = page.size > 1,
            )
        }
        return true
    }

    /**
     * Whether a read asked now is for this board's own production.
     *
     * The first load after a switch names it. After that, a read asked while
     * another production is open — the moment between the switch and the
     * board being told, when socket events from the old connection still
     * arrive — would put one production's answer into the other's board, so
     * it is not asked at all; the switch's own load follows within the frame.
     */
    private fun readsShownProduction(): Boolean {
        val open = media.currentProjectId()
        if (shownProduction == null) shownProduction = open
        return open == shownProduction
    }

    /** [launch], for a read that belongs to the production on screen; see [productionReads]. */
    private fun launchRead(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Job {
        productionReads.removeAll { it.isCompleted }
        return launch(block).also { productionReads += it }
    }

    private fun selectUnit(unitId: String) {
        val unit = currentState.tabs.firstOrNull { it.id == unitId } ?: return
        // What the outgoing tab was showing is worth keeping — it is fresher
        // than any fetch, carrying the socket arrivals and the posts of this
        // session. Coming back to it draws from here instead of from nothing.
        //
        // Unless what it was showing is a record: those posts are not that
        // board's page, and its real one is already here, as it was when
        // History was opened.
        val record = currentState.isHistory
        currentState.selectedUnitId?.let { leaving ->
            if (leaving != unit.id && !record) boards[leaving] = currentState.notices
        }
        leaveHistory(unit)
        setState {
            copy(
                // Neither the tab nor the posts are switched here: `loadNotices`
                // does both, and it needs to know which tab is being *left* to
                // tell "arriving somewhere new" from "re-reading what is open".
                replyTo = null,
                editing = null,
                editingPost = null,
                draftBeforeEdit = null,
                searchQuery = null,
                searchIndex = 0,
                callSheetPrompt = null,
                // Ticked posts belonged to the unit being left, and so did
                // the question about them.
                selection = null,
                deletePrompt = null,
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

        launch {
            val files = dropped.ifEmpty { if (kind == null) media.pick() else media.pickOf(kind) }
                .filterNot { refusedByUnit(unit, it) }
            if (files.isEmpty()) return@launch

            // A call sheet or a production report chat with posts on it asks
            // once the document is in hand — continuation of what is there, a
            // new one that sends the rest to History, or one live document
            // swapped. The web asks in exactly this order: the file is chosen,
            // then `changeHandler` blocks on the modal before the upload is
            // built (`UnitChatMessageBox.jsx`). Asking first, as this used to,
            // made people answer a question about a file they had not picked.
            if (replace == null && uploadAsksFirst(unit)) {
                setState {
                    copy(
                        callSheetPrompt = CallSheetPrompt(files = files, targets = replaceTargets(notices)),
                        error = null,
                    )
                }
                return@launch
            }

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
                // BOTH halves of the answer, or Replace quietly appends and
                // the document it was told to retire stays live. The web
                // carries `replacePreviousChats` and `replaceChatId` together
                // on every file of the burst, ungated by index.
                replacePrevious = pending?.replace,
                replaceChatId = pending?.replaceChatId,
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

    /**
     * The upload question is owed once the board has anything on it — on the
     * call sheet and on the production report chat, the web's two
     * `needsContinuationPrompt` units. An empty board has nothing to continue,
     * so it never asks (the phones' rule; the web asks regardless, and the
     * question reads as nonsense there).
     */
    private fun uploadAsksFirst(unit: HomeUnit): Boolean =
        unit.kind.isDocumentUpload &&
            currentState.notices.any { it.sendState == NoticeSendState.Sent }

    /**
     * The call sheet and production report units take documents only — iOS's
     * attach sheet offers nothing else there, Android's picker set is
     * `PICKER_ITEM_DOCUMENT` alone, and the web's attach menu drops every
     * other row for both. Anything else is refused with a sentence, not
     * silently dropped.
     */
    private fun refusedByUnit(unit: HomeUnit, picked: PickedMedia): Boolean {
        if (!unit.kind.isDocumentUpload || picked.kind == NoticeKind.Document) return false
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
     * The answer arrives: the prompt closes and the documents that have been
     * waiting in it go on to the preview, carrying the flag. Nothing is picked
     * again — the files were chosen before the question was put.
     */
    /**
     * Send, with the card that is ticked: Continuation appends and Replace
     * swaps the chosen document, both straight away; New is the one
     * irreversible answer, so it asks once more before anything is sent.
     */
    private fun sendWithAnswer() {
        val prompt = currentState.callSheetPrompt ?: return
        if (!prompt.canSend) return
        when (prompt.choice) {
            UploadChoice.Continuation -> answerCallSheet(replace = false)
            UploadChoice.Replace -> answerCallSheet(replace = false, replaceChatId = prompt.replaceNoticeId)
            UploadChoice.New -> setState { copy(callSheetPrompt = prompt.copy(confirmingReplace = true)) }
            null -> Unit
        }
    }

    private fun answerCallSheet(replace: Boolean, replaceChatId: String? = null) {
        val prompt = currentState.callSheetPrompt ?: return
        setState { copy(callSheetPrompt = null) }
        attach(dropped = prompt.files, replace = replace, replaceChatId = replaceChatId)
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

    /** Selection mode's own events, in one place — see [HomeFeedEvent.Picking]. */
    private fun picking(event: HomeFeedEvent.Picking) {
        when (event) {
            is HomeFeedEvent.SelectForDelete -> selectForDelete(event.noticeId)
            is HomeFeedEvent.ToggleSelected -> toggleSelected(event.noticeId)
            HomeFeedEvent.CancelSelection -> setState { copy(selection = null) }
            HomeFeedEvent.AskDeleteSelected -> askDeleteSelected()
            is HomeFeedEvent.AskDeleteComment -> setState {
                copy(deletePrompt = DeletePrompt.Reply(event.noticeId, event.commentId))
            }
            HomeFeedEvent.CancelDeletePrompt -> setState { copy(deletePrompt = null) }
            HomeFeedEvent.ConfirmDelete -> confirmDelete()
        }
    }

    /**
     * A post's menu Delete — the web's `DELETE_MESSAGE`: nothing is deleted
     * here. The post is ticked and selection mode opens, so a second and a
     * third can join it before the single question is asked. A post this
     * person may not delete never enters the mode; the refusal is the one
     * the direct delete would have given.
     */
    private fun selectForDelete(noticeId: String) {
        val refusal = deleteRefusal(noticeId) ?: run {
            setState { copy(selection = BoardSelection(SelectionAction.Delete, setOf(noticeId))) }
            return
        }
        setState { copy(error = refusal) }
    }

    /** A card ticked or un-ticked while the mode is open. */
    private fun toggleSelected(noticeId: String) {
        val open = currentState.selection ?: return
        if (noticeId in open.ids) {
            setState { copy(selection = open.copy(ids = open.ids - noticeId)) }
            return
        }
        val refusal = deleteRefusal(noticeId) ?: run {
            setState { copy(selection = open.copy(ids = open.ids + noticeId)) }
            return
        }
        setState { copy(error = refusal) }
    }

    /** The bar's Delete: the question, naming nothing — the batch can be large. */
    private fun askDeleteSelected() {
        val ids = currentState.selection?.ids?.toList().orEmpty()
        if (ids.isEmpty()) return
        setState { copy(deletePrompt = DeletePrompt.Posts(ids)) }
    }

    /** The question's Yes. */
    private fun confirmDelete() {
        when (val prompt = currentState.deletePrompt) {
            null -> Unit
            is DeletePrompt.Reply -> {
                setState { copy(deletePrompt = null) }
                delete(prompt.noticeId, prompt.commentId)
            }
            is DeletePrompt.Posts -> {
                setState { copy(deletePrompt = null, selection = null) }
                deletePosts(prompt.ids)
            }
        }
    }

    /**
     * Why this post cannot be deleted, or null when it can — the phones'
     * rule, and the sentence they show for it. Read both when a post is
     * ticked and again before the batch goes out: the window can close
     * while the question is on screen.
     */
    private fun deleteRefusal(noticeId: String, commentId: String? = null): String? {
        val target = currentState.notices.firstOrNull { it.id == noticeId } ?: return null
        val comment = commentId?.let { id -> target.comments.firstOrNull { it.id == id } }
        val verdict = comment?.deleteVerdict(currentUserId(), isAdmin(), nowMillis())
            ?: target.deleteVerdict(currentUserId(), isAdmin(), nowMillis())
        if (verdict.allowed) return null
        return refusal(verdict, deleting = true, reply = comment != null)
    }

    /**
     * Withdraws every ticked post in one call — `PUT home/chat/delete/chats`
     * is bulk by design and the web's selection mode sends exactly this.
     *
     * Off the board first, as the single delete does, and back together if
     * the server refuses. A post whose window closed between the tick and
     * the Yes is dropped from the batch with its refusal shown, rather than
     * failing the whole call.
     */
    private fun deletePosts(ids: List<String>) {
        val refusals = ids.mapNotNull { deleteRefusal(it) }
        val allowed = ids.filter { deleteRefusal(it) == null }
        val removed = currentState.notices.filter { it.id in allowed }
        if (removed.isEmpty()) {
            refusals.firstOrNull()?.let { only -> setState { copy(error = only) } }
            return
        }
        val gone = removed.map { it.id }
        setState { copy(notices = notices.filterNot { it.id in gone }, error = refusals.firstOrNull()) }
        launchResult(
            block = { repository.deleteNotices(gone) },
            onSuccess = {
                val note = if (gone.size == 1) {
                    str(S.desktop_board_message_deleted)
                } else {
                    str(S.desktop_board_messages_deleted)
                }
                setState { copy(info = note) }
            },
            onError = { error ->
                setState {
                    copy(error = error.localised(), notices = (notices + removed).forDisplay())
                }
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

    /**
     * Opens the picker, and asks the host what else this post can reach.
     *
     * This board's own units are listed at once — they are already in state —
     * and the rest arrive when the reach answers, which is a round trip on
     * the tools call. The web's modal fills the same way.
     */
    private fun startForward(noticeId: String) {
        // Only a post the server holds can be copied elsewhere.
        val notice = currentState.notices.firstOrNull {
            it.id == noticeId && it.sendState == NoticeSendState.Sent
        } ?: return
        setState { copy(forwarding = notice, forwardSections = listOf(ownUnitsSection())) }
        val host = reach ?: return
        launch {
            val elsewhere = host.sections(notice.asForwardItem(), from = boardKey)
            // The picker may have been closed, or opened on another post,
            // while the tools call was out.
            if (currentState.forwarding?.id != noticeId) return@launch
            setState { copy(forwardSections = listOf(ownUnitsSection()) + elsewhere) }
        }
    }

    /**
     * This board's units, as the picker draws them — every visible one, the
     * current included (both live clients allow forwarding back to the same
     * board). Rights are not pre-filtered: choosing a unit without posting
     * access answers with the refusal, exactly as tapping one does on iOS.
     *
     * Under the same heading every other board uses, so the picker shows one
     * list of boards rather than a heading per board.
     */
    private fun ownUnitsSection(): ForwardSection = ForwardSection(
        title = str(S.desktop_forward_boards),
        entries = currentState.tabs
            .filter { it.kind != HomeUnitKind.Calendar }
            .map { unit ->
                ForwardPick(
                    id = "$UNIT_TARGET${unit.id}",
                    label = unit.label,
                    // Nothing under the name but a warning worth reading:
                    // repeating the heading there was the first thing anyone
                    // noticed about this list, and they were right.
                    caption = if (!unit.canPost && !isAdmin()) {
                        str(S.desktop_board_no_posting_rights_chip)
                    } else {
                        null
                    },
                    icon = ZillitIcons.Chat,
                )
            },
    )

    private fun forwardTo(unitId: String) {
        val notice = currentState.forwarding ?: return
        val target = currentState.units.firstOrNull { it.id == unitId } ?: return
        val refusal = unitRefusal(target, notice)
        if (refusal != null) {
            setState { copy(forwarding = null, error = refusal) }
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

    /** Why this unit will not take the post, or null when it will. */
    private fun unitRefusal(target: HomeUnit, notice: Notice): String? {
        if (!target.canPost && !isAdmin()) return noPostingRights(target)
        val callSheetTakes = notice.kind == NoticeKind.Text || notice.kind == NoticeKind.Document
        if (target.kind.isDocumentUpload && !callSheetTakes) {
            return str(S.desktop_board_only_text_and_documents_to, target.label)
        }
        return null
    }

    /**
     * The picker's Send: one copy per ticked destination — this board's units
     * through its own route, everywhere else through the host's reach.
     *
     * One sentence at the end rather than one per destination: a batch of six
     * would otherwise stack six toasts. A destination that refuses is named;
     * the others still go.
     */
    private fun forwardToAll(targetIds: List<String>) {
        val notice = currentState.forwarding ?: return
        setState { copy(forwarding = null) }
        launch {
            val refusals = mutableListOf<String>()
            var sent = 0
            var reloadOpen = false
            targetIds.filter { it.startsWith(UNIT_TARGET) }.forEach { id ->
                val unitId = id.removePrefix(UNIT_TARGET)
                val target = currentState.units.firstOrNull { it.id == unitId } ?: return@forEach
                val refusal = unitRefusal(target, notice)
                if (refusal != null) {
                    refusals += refusal
                    return@forEach
                }
                when (val answer = repository.forwardNotice(unitId, notice, newLocalId())) {
                    is ZillitResult.Success -> {
                        sent++
                        if (unitId == currentState.selectedUnit?.id) reloadOpen = true
                    }
                    is ZillitResult.Failure -> refusals += answer.error.localised()
                }
            }
            val elsewhere = targetIds.filterNot { it.startsWith(UNIT_TARGET) }
            if (elsewhere.isNotEmpty()) {
                when (val answer = reach?.deliver(elsewhere, notice.asForwardItem())) {
                    null -> Unit
                    is ZillitResult.Success -> sent += elsewhere.size
                    is ZillitResult.Failure -> refusals += answer.error.localised()
                }
            }
            // The reload FIRST: `loadNotices` clears `error` as it starts, so
            // a refusal set before it would vanish the moment one of the
            // copies landed on the board already on screen.
            if (reloadOpen) currentState.selectedUnit?.let { loadNotices(it) }
            setState {
                copy(
                    info = forwardedNote(sent),
                    error = refusals.firstOrNull(),
                )
            }
        }
    }

    /** What the toast says once the batch has gone out. */
    private fun forwardedNote(sent: Int): String? = when {
        sent <= 0 -> null
        sent == 1 -> str(S.desktop_message_forwarded_success)
        else -> str(S.desktop_forwarded_to_n, sent)
    }

    /**
     * A destination that asks something first.
     *
     * The host either takes over (the mailbox), refuses, or answers with the
     * form that tool needs — which the board then draws, with the post held
     * as [HomeFeedUiState.forwardingItem] for the Send.
     */
    private fun forwardStep(targetId: String) {
        val notice = currentState.forwarding ?: return
        val item = notice.asForwardItem()
        setState { copy(forwarding = null) }
        val host = reach ?: return
        launch {
            when (val outcome = host.open(targetId, item)) {
                ForwardStepOutcome.Handled -> Unit
                is ForwardStepOutcome.Refused -> setState { copy(error = outcome.message) }
                is ForwardStepOutcome.Asks -> setState {
                    copy(forwardForm = outcome.form, forwardingItem = item)
                }
            }
        }
    }

    /** The tool form's Send: the answers go out with the post they were asked for. */
    private fun submitForwardForm(answers: Map<String, String>) {
        val form = currentState.forwardForm ?: return
        val item = currentState.forwardingItem ?: return
        val host = reach ?: return
        setState { copy(forwardSending = true) }
        launch {
            when (val answer = host.submit(form.targetId, item, answers)) {
                is ZillitResult.Success -> setState {
                    copy(
                        forwardForm = null,
                        forwardingItem = null,
                        forwardSending = false,
                        info = str(S.desktop_message_forwarded_success),
                    )
                }
                is ZillitResult.Failure -> setState {
                    copy(forwardSending = false, error = answer.error.localised())
                }
            }
        }
    }

    /** Lists a post's file in the app-wide Uploads tray, with what its Cancel and Retry do. */
    private fun track(localId: String, unitId: String, picked: PickedMedia, pin: BoardPin?) {
        media.tracker?.begin(
            TrackedSend(
                id = localId,
                projectId = pin?.projectId.orEmpty(),
                projectName = pin?.projectName.orEmpty(),
                destination = currentState.tabs.firstOrNull { it.id == unitId }?.unitName.orEmpty(),
                fileName = picked.name,
                preview = picked.thumbnailBytes,
            ),
            cancel = { cancelPost(localId) },
            retry = { redeliver(localId) },
        )
    }

    /**
     * A picture's thumbnail cut before its upload rather than inside it, so
     * the tray can show the picture while the bytes move.
     */
    private suspend fun withTrayThumbnail(localId: String, picked: PickedMedia?): PickedMedia? =
        picked?.takeIf { it.thumbnailBytes == null && it.kind == NoticeKind.Image }
            ?.let { runCatching { media.videoThumbnail(it) }.getOrNull() }
            ?.also { cut -> cut.thumbnailBytes?.let { media.tracker?.preview(localId, it) } }
            ?: picked

    /** The tray's Retry: the post again, from its own record — the card may not be on screen. */
    private fun redeliver(localId: String) {
        val pending = pendingPosts[localId] ?: return
        if (isHere(pending.pin)) {
            setState {
                copy(
                    notices = notices.map {
                        if (it.localId == localId) it.copy(sendState = NoticeSendState.Sending) else it
                    },
                )
            }
        }
        deliver(
            pending.unitId,
            pending.optimistic,
            picked = pickedByLocalId[localId],
            location = pending.location,
            replacePrevious = replaceByLocalId[localId],
            replaceChatId = replaceTargetByLocalId[localId],
        )
    }

    /** The tray's Cancel: the work stopped, the card and everything its retry kept gone. */
    private fun cancelPost(localId: String) {
        val pending = pendingPosts.remove(localId) ?: return
        pending.job?.cancel()
        listOf(uploadedByLocalId, pickedByLocalId, replaceByLocalId, replaceTargetByLocalId)
            .forEach { it.remove(localId) }
        if (!isHere(pending.pin)) return
        setState {
            copy(notices = notices.filterNot { it.localId == localId }, uploadProgress = uploadProgress - localId)
        }
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
        pin: BoardPin? = null,
    ): ZillitResult<UploadedNoticeMedia?> {
        uploadedByLocalId[localId]?.let { return ZillitResult.Success(it) }
        if (picked == null) return ZillitResult.Success(null)
        val upload = pin?.upload ?: media.upload
            ?: return ZillitResult.Failure(ZillitError.Storage("no uploader wired for notice media"))
        return when (val stored = upload(picked) { progress ->
            media.tracker?.progress(localId, progress)
            if (isHere(pin)) setState { copy(uploadProgress = uploadProgress + (localId to progress)) }
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
        // Pinned once, on the first send: a retry goes where the post was sent.
        val pending = pendingPosts.getOrPut(localId) {
            PendingPost(unitId, optimistic, location, runCatching { media.pin() }.getOrNull())
        }
        val pin = pending.pin
        picked?.let { track(localId, unitId, it, pin) }

        pending.job = launchResult(
            block = {
                val uploaded = when (val stored = uploadFor(localId, withTrayThumbnail(localId, picked), pin)) {
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
                if (uploaded != null && isHere(pin)) {
                    setState {
                        copy(uploadProgress = uploadProgress + (localId to UPLOAD_DONE))
                    }
                }
                // Into the production it was sent in — never whichever is open
                // now. Unpinned, the options name nothing and the open one is used.
                repository.postNoticeAs(
                    pin?.options ?: CallOptions(), unitId, optimistic.body, localId, uploaded, location,
                    replacePrevious, replaceChatId,
                )
            },
            onSuccess = { saved -> posted(localId, unitId, saved, pin, replacePrevious) },
            onError = { error -> postFailed(localId, optimistic, error, pin) },
        )
    }

    /** The server has the post: the card becomes its copy, and the post's retry record goes. */
    private fun posted(localId: String, unitId: String, saved: Notice, pin: BoardPin?, replacePrevious: Boolean?) {
        pendingPosts.remove(localId)
        media.tracker?.finished(localId)
        listOf(uploadedByLocalId, pickedByLocalId, replaceByLocalId, replaceTargetByLocalId)
            .forEach { it.remove(localId) }
        // Posted into a production no longer open: its board is not this one.
        if (!isHere(pin)) return
        // "Replace one document": the retired message leaves the board
        // the moment the server names it, as the web's
        // `removeMessagesFromList` on `replaced_chat_id`.
        saved.replacedNoticeId?.let { gone -> setState { copy(notices = notices.filterNot { it.id == gone }) } }
        // Replace rather than append: the optimistic card and the server's
        // copy are the same post, and appending would show it twice.
        setState {
            copy(
                notices = notices.replacing(localId, saved),
                uploadProgress = uploadProgress - localId,
            )
        }
        // A "New" upload moved every earlier post to History on the server,
        // so the board is now this one document. The web says exactly that —
        // `replacePreviousChatsInUnitChatState` sets the unit's bucket to
        // `[newMessage]` — and saying it here is what makes the old rows go.
        //
        // A refetch alone did NOT: the answer carries only the upload, which
        // is the NEWEST post, so every retired row sits behind its boundary
        // and `pagesBehind` keeps them as "a page that was not asked for".
        // That is right for an ordinary refresh and wrong for this, the one
        // case where the client already knows the rest is gone.
        //
        // Posts still going up are kept: a second upload started while this
        // one was in flight is nobody's history. The read still follows, to
        // pick up the server's own copy and anything that arrived with it.
        if (replacePrevious == true) {
            setState {
                val inFlight = notices.filter { it.sendState != NoticeSendState.Sent && it.id != saved.id }
                copy(notices = listOf(saved) + inFlight)
            }
            boards[unitId] = currentState.notices
            currentState.selectedUnit?.takeIf { it.id == unitId }?.let(::loadNotices)
        }
    }

    /** The post did not land: its card turns red here, and its row in the tray says so. */
    private fun postFailed(localId: String, optimistic: Notice, error: ZillitError, pin: BoardPin?) {
        media.tracker?.failed(localId)
        if (!isHere(pin)) return
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
    }

    private fun loadUnits() {
        if (!readsShownProduction()) return
        setState {
            copy(
                isLoadingUnits = true,
                error = null,
                nowMillis = nowMillis(),
                isAdmin = isAdmin(),
                currentUserId = currentUserId(),
            )
        }

        // Back in a production this board has already shown: its own tabs and
        // pages, now, with no disk and no spinner. The board under them is
        // refreshed at once; the strip is re-read below as on any load.
        if (restoreKeptProduction()) currentState.selectedUnit?.let(::loadNotices)

        // The picker's recency memory rides along with the board's context.
        launchRead {
            val recent = loadRecentMentions()
            if (recent.isNotEmpty()) setState { copy(recentMentions = recent) }
        }

        launchRead {
            // The kept strip first, so the tabs and the board behind them are
            // on screen before the network answers. This is a read of disk,
            // not of the server: it cannot tell us a tab was taken away, which
            // is what the real read below is for.
            seedUnits()

            when (val result = repository.loadUnits()) {
                is ZillitResult.Success -> onUnitsLoaded(result.data)
                is ZillitResult.Failure ->
                    setState { copy(isLoadingUnits = false, error = result.error.localised()) }
            }
        }
    }

    /** The tab strip from disk, and the board under it, while the read is out. */
    private suspend fun seedUnits() {
        // Only ever a head start. Tabs already on screen — a refetch, or a
        // live answer that beat the disk — are never replaced by kept ones.
        if (currentState.tabs.isNotEmpty()) return
        val kept = repository.cachedUnits()?.takeIf { it.isNotEmpty() } ?: return
        if (currentState.tabs.isNotEmpty()) return
        setState { copy(units = kept, selectedUnitId = selectedUnitId ?: landingTab(kept)) }
        currentState.selectedUnit?.let(::loadNotices)
    }

    private fun onUnitsLoaded(units: List<HomeUnit>) {
        setState {
            copy(
                isLoadingUnits = false,
                units = units,
                // The person's chosen landing tab, on first arrival
                // only — a tab they picked since is theirs to keep.
                selectedUnitId = selectedUnitId ?: landingTab(units),
            )
        }
        // Open the tab immediately: a tab strip with nothing under it
        // reads as a broken screen.
        //
        // Unless a read of this very board is already out — the seed above
        // opened it a moment ago — in which case asking again would only cost
        // a round trip for the same page.
        val open = currentState.selectedUnit ?: return
        if (boardReadInFlight != open.id) loadNotices(open)
    }

    /**
     * The tab a board lands on, when the person has not chosen one yet: their
     * saved choice, but only while it is a tab they can still see.
     */
    private fun landingTab(units: List<HomeUnit>): String? =
        defaultUnitId()?.takeIf { wanted -> units.visibleTabs().any { it.id == wanted } }

    /**
     * The board / record switch.
     *
     * The web gives History its own route and reads the posts the board no
     * longer holds (`?deleted=`); here it is a mode of the same board, so the
     * switch is what asks for them. The live page is kept as it is on the way
     * in, which is what makes the way out free.
     */
    private fun showHistory(history: Boolean) {
        if (currentState.isHistory == history) return
        val unit = currentState.selectedUnit ?: return
        if (!history) {
            leaveHistory(unit)
            loadNotices(unit)
            return
        }
        boards[unit.id] = currentState.notices
        setState {
            copy(
                isHistory = true,
                // Nothing on screen is part of the record until the read
                // answers — least of all a card still going up (ZL-17613).
                notices = emptyList(),
                hasOlder = false,
                // The composer is gone in History, and so is anything it was
                // in the middle of.
                replyTo = null,
                editing = null,
                editingPost = null,
                draftBeforeEdit = null,
                searchQuery = null,
                searchIndex = 0,
                selection = null,
                error = null,
            )
        }
        loadHistoryPage(unit)
    }

    /**
     * Steps out of the record and back onto [unit]'s live board.
     *
     * The posts on screen are the record's, so they are not carried into the
     * read that follows: the board's own page is in [boards], put there when
     * History was opened.
     */
    private fun leaveHistory(unit: HomeUnit) {
        if (!currentState.isHistory) return
        val live = boards[unit.id].orEmpty()
        setState { copy(isHistory = false, notices = live, hasOlder = live.size > 1) }
    }

    /**
     * The record's newest window.
     *
     * Nothing is filed and nothing is read: these posts are not the board, so
     * neither the offline copy nor the unit's read receipt has anything to do
     * with them.
     */
    private fun loadHistoryPage(unit: HomeUnit) {
        if (!readsShownProduction()) return
        setState { copy(isLoadingNotices = true, error = null) }
        launchRead {
            val result = repository.loadHistory(unit.id, nowMillis())
            // The switch may have been turned off, or another tab opened,
            // while this was out; the record is then nobody's answer.
            val stale = { !currentState.isHistory || currentState.selectedUnitId != unit.id }
            when (result) {
                is ZillitResult.Success -> setState {
                    if (stale()) {
                        copy(isLoadingNotices = false)
                    } else {
                        copy(
                            isLoadingNotices = false,
                            notices = result.data,
                            hasOlder = result.data.size > 1,
                        )
                    }
                }
                is ZillitResult.Failure -> setState {
                    if (stale()) {
                        copy(isLoadingNotices = false)
                    } else {
                        copy(isLoadingNotices = false, error = result.error.localised())
                    }
                }
            }
        }
    }

    private fun loadNotices(unit: HomeUnit) {
        if (!readsShownProduction()) return
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

        // The calendar tab has no board; asking for one would 404. Its badge
        // is the invitations', and opening the tab reads none of them: iOS
        // skips the calendar unit in `readMessageFromHome` (Ext:299-300) and
        // reads an invitation when it is answered, and the expired ones when
        // that tab is shown (`ReceivedEventsView`). A unit read here cleared
        // every pending invitation the moment the tab was glanced at — and
        // the server does not even recognise it as a calendar read.
        if (unit.kind == HomeUnitKind.Calendar) {
            setState { copy(selectedUnitId = unit.id, notices = emptyList(), isLoadingNotices = false) }
            return
        }

        // Whether this read refreshes the board already on screen or opens a
        // different one. A refresh inherits the reader's scrollback; an
        // arrival computes it afresh.
        val rereading = currentState.selectedUnitId == unit.id

        setState {
            // What stays on screen while the read is out. A refetch of the
            // open board keeps what it is showing; arriving at a tab this
            // session has already read gets that page back out of memory.
            // Only a tab nobody has opened yet starts empty — and even that
            // one reaches for disk, just below.
            //
            // The spinner is not conditional on any of this: `isLoadingNotices`
            // means "a read is out", and the screen draws it only when there is
            // nothing else to show. One flag, one meaning.
            val shown = if (rereading) notices else boards[unit.id].orEmpty()
            copy(
                selectedUnitId = unit.id,
                notices = shown,
                isLoadingNotices = true,
                error = null,
                // Left alone when this is a re-read of the board already open:
                // `hasOlder` is the reader's own knowledge of what lies behind
                // the oldest post they have scrolled to, and only `loadOlder`
                // is entitled to retire it. Recomputing it here is what used
                // to throw away a scrolled-back board every time a comment
                // arrived.
                hasOlder = if (rereading) hasOlder else shown.size > 1,
                loadingOlder = false,
            )
        }

        boardReadInFlight = unit.id
        launchRead {
            seedNotices(unit.id)

            // "now" is the newest page — the endpoint reads backwards from a
            // timestamp rather than paging by offset, because a board people are
            // posting to shifts under an offset.
            val result = repository.loadNotices(unit.id, nowMillis())
            if (boardReadInFlight == unit.id) boardReadInFlight = null
            when (result) {
                is ZillitResult.Success -> onNoticesLoaded(unit.id, result.data, rereading)
                is ZillitResult.Failure ->
                    // Guard against a slow response for a tab the user has left.
                    if (currentState.selectedUnitId == unit.id) {
                        setState { copy(isLoadingNotices = false, error = result.error.localised()) }
                    }
            }
        }
    }

    /**
     * The kept newest page, drawn while the real read is out.
     *
     * Only when there is nothing on screen for this tab: a page already
     * showing is at least as fresh as the disk, and replacing it would be a
     * visible step backwards.
     */
    private suspend fun seedNotices(unitId: String) {
        if (currentState.selectedUnitId != unitId || currentState.notices.isNotEmpty()) return
        val kept = repository.cachedNotices(unitId)?.takeIf { it.isNotEmpty() } ?: return
        if (currentState.selectedUnitId != unitId || currentState.notices.isNotEmpty()) return
        boards[unitId] = kept
        setState { copy(notices = kept, hasOlder = kept.size > 1) }
    }

    private fun onNoticesLoaded(unitId: String, fetched: List<Notice>, rereading: Boolean) {
        if (currentState.selectedUnitId != unitId) {
            // Still worth keeping: the tab was left mid-read, and this is the
            // page it will show when it is opened again.
            boards[unitId] = fetched
            return
        }
        setState {
            // A post still going up exists only here. Its upload may have
            // begun after this read went out, so the server's answer does not
            // contain it — and replacing the list wholesale would take the
            // card out from under a running upload. Chat guards the same race
            // the same way (`ChatViewModel.openThread`).
            val inFlight = notices.filter { local ->
                local.sendState != NoticeSendState.Sent && fetched.none { it.sameAs(local) }
            }
            val behind = if (rereading) pagesBehind(notices, fetched) else emptyList()
            copy(
                isLoadingNotices = false,
                notices = behind + fetched + inFlight,
                // Anything past a lone post may have history behind it; the
                // older-page fetch retires the cue itself. A refresh leaves it
                // as it was — the oldest post shown has not moved, so what
                // lies behind it has not changed either.
                hasOlder = if (rereading) hasOlder else fetched.size > 1,
            )
        }
        boards[unitId] = currentState.notices
        // The board is on screen — that is what "read" means on
        // this API, as when the web's Notices tab is visible.
        readBoard(unitId)
    }

    /**
     * Reads the open board — now if somebody is looking, else once they are.
     *
     * A post landing on a board behind another window, or a board reloaded by
     * a socket event while the app sat in the background, used to read at
     * once: the badge went before anyone saw the post, and everyone else saw
     * "read by" for a person who was not there.
     */
    private fun readBoard(unitId: String) {
        if (watching) {
            launch { onBoardViewed(unitId) }
        } else {
            readOwed = true
        }
    }

    private fun onWatching(value: Boolean) {
        if (watching == value) return
        watching = value
        if (!value || !readOwed) return
        readOwed = false
        val open = currentState.selectedUnit ?: return
        if (open.kind != HomeUnitKind.Calendar) launch { onBoardViewed(open.id) }
    }

    /**
     * The scrolled-back pages a refreshed newest page must not take away.
     *
     * The read only ever asks for the newest window, so a reader who has
     * pressed "show older" four times holds four pages the answer knows
     * nothing about. Replacing the list with the answer collapsed all of them
     * — which a comment arriving on the board was enough to do.
     *
     * The oldest post in the answer is the boundary. Behind it, a post the
     * answer does not mention is simply a page that was not asked for, and is
     * kept. Inside it, a post the answer does not mention is one the server no
     * longer has — deleted, or moved to the call sheet's history — and keeping
     * that would be a deleted post that will not go away. Which side of the
     * boundary a post falls on is the whole distinction, and `pageStamp` is the
     * same `updated` the endpoint windows by, so the two agree by construction.
     *
     * Pending posts are not considered: they are nobody's page, and
     * [onNoticesLoaded] carries them separately.
     */
    private fun pagesBehind(shown: List<Notice>, fetched: List<Notice>): List<Notice> {
        val boundary = fetched
            .filter { it.sendState == NoticeSendState.Sent && it.pageStamp > 0 }
            .minOfOrNull { it.pageStamp }
        // An empty answer names no boundary. It may mean the board was
        // emptied, so nothing is assumed and the answer stands as given.
        if (boundary == null) return emptyList()
        return shown.filter { post ->
            post.sendState == NoticeSendState.Sent &&
                post.pageStamp in 1 until boundary &&
                fetched.none { it.sameAs(post) }
        }
    }

    /**
     * The page behind the oldest post shown, merged in — an arrival during
     * the fetch must not be replaced by the older window. Paged from the
     * oldest `updated`, as Android does. Stops when a page brings nothing
     * new: the server's window includes its boundary post.
     */
    private fun loadOlder() {
        val unit = currentState.selectedUnit ?: return
        if (currentState.loadingOlder || !currentState.hasOlder) return
        if (!readsShownProduction()) return
        // An archive pages from `created`, as the web's viewer does (the first
        // row's `created`); the live board pages from `updated`, as Android does.
        // A record is an archive — it is ordered by `created` on screen too.
        val record = currentState.isHistory
        val stamp: (Notice) -> Long = if (readOnly || record) Notice::createdAtMillis else Notice::pageStamp
        val oldest = currentState.notices
            .filter { it.sendState == NoticeSendState.Sent && stamp(it) > 0 }
            .minOfOrNull(stamp) ?: return
        setState { copy(loadingOlder = true) }
        launchRead {
            val older = if (record) {
                repository.loadHistory(unit.id, oldest)
            } else {
                repository.loadOlderNotices(unit.id, oldest)
            }
            when (val page = older) {
                is ZillitResult.Success -> setState {
                    if (selectedUnitId != unit.id) {
                        copy(loadingOlder = false)
                    } else {
                        // What is on screen wins a duplicate: it may carry an
                        // edit or a reply the older page predates.
                        val merged = (notices + page.data).distinctBy { it.id }
                        copy(notices = merged, loadingOlder = false, hasOlder = merged.size > notices.size)
                    }
                }
                is ZillitResult.Failure ->
                    setState { copy(loadingOlder = false, error = page.error.localised()) }
            }
        }
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
 * How many productions' boards are kept in memory across switches. Enough
 * for somebody moving between a handful of shoots; the rest come back from
 * disk, as before.
 */
private const val MAX_KEPT_PRODUCTIONS = 4

/** The posts the server has — what a kept board may hold. */
private fun List<Notice>.sentOnly(): List<Notice> = filter { it.sendState == NoticeSendState.Sent }

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

/**
 * The prefix on a target id that means "a unit of the board asking".
 *
 * The picker's ids are opaque strings, agreed between the surface that
 * builds an entry and whoever delivers to it: this board builds its own
 * units' ids, so it is the one that recognises them, and everything else
 * goes to the host's reach untouched.
 */
private const val UNIT_TARGET = "unit:"
