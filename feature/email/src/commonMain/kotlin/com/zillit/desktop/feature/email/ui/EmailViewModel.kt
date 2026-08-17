package com.zillit.desktop.feature.email.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.email.data.Mailbox
import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.DeleteIntent
import com.zillit.desktop.feature.email.domain.DraftRepository
import com.zillit.desktop.feature.email.domain.EmailAttachment
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.EmailRealtimeEvent
import com.zillit.desktop.feature.email.domain.EmailRepository
import com.zillit.desktop.feature.email.domain.EmailQuery
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.domain.defaultFolder
import com.zillit.desktop.feature.email.domain.deleteIntentFor
import com.zillit.desktop.feature.email.domain.moveTargets
import com.zillit.desktop.feature.email.domain.forSidebar
import com.zillit.desktop.feature.email.domain.toSummary

data class EmailUiState(
    val folders: List<EmailFolder> = emptyList(),
    val selectedFolderName: String? = null,
    val messages: List<EmailSummary> = emptyList(),
    val selectedMessageId: String? = null,
    val thread: List<EmailMessage> = emptyList(),
    val isLoadingThread: Boolean = false,
    val isLoadingFolders: Boolean = false,
    val isLoadingMessages: Boolean = false,
    /** A background batch is in flight — shown as a footer, not a blank list. */
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    /**
     * Saved drafts, held whole rather than as summaries.
     *
     * The composer needs the real thing when one is reopened, and the Drafts
     * folder is small enough that keeping it in memory costs nothing.
     */
    val drafts: List<EmailDraft> = emptyList(),
    /** Rows ticked for a bulk action. */
    val selectedIds: Set<String> = emptySet(),
    /** A destructive action waiting on the user to confirm it. */
    val pendingConfirm: PendingConfirm? = null,
    val error: String? = null,
) {
    val sidebar: List<EmailFolder> get() = folders.forSidebar()

    val selectedFolder: EmailFolder?
        get() = folders.firstOrNull { it.name == selectedFolderName } ?: folders.defaultFolder()

    /**
     * What the list shows when nothing is being searched.
     *
     * A live search replaces this with its own results — see [MailSearch]. It
     * deliberately leaves the folder behind: someone looking for a call sheet
     * does not know which folder it is in, which is the point of searching, and
     * a search confined to the open folder is the one that finds nothing and
     * gets blamed for it.
     */
    val visibleMessages: List<EmailSummary> get() = messages

    val openMessage: EmailSummary?
        get() = messages.firstOrNull { it.id == selectedMessageId }

    /** True when the open folder is Drafts, which behaves differently throughout. */
    val isViewingDrafts: Boolean
        get() = selectedFolder?.name.equals(EmailFolder.DRAFTS, ignoreCase = true)

    fun draft(id: String): EmailDraft? = drafts.firstOrNull { it.id == id }

    /** True when the open folder is Trash, where delete destroys rather than moves. */
    val isViewingTrash: Boolean
        get() = selectedFolder?.name.equals(EmailFolder.TRASH, ignoreCase = true)

    val selectedMessages: List<EmailSummary>
        get() = messages.filter { it.id in selectedIds }

    /** Where the selection could be moved to. */
    val moveTargets: List<EmailFolder> get() = folders.moveTargets(selectedFolderName)

    /**
     * Selecting rows takes over the toolbar.
     *
     * Drafts are excluded: they are not IMAP messages, so moving or trashing
     * them would address ids the mail endpoints have never heard of.
     */
    val isSelecting: Boolean get() = selectedIds.isNotEmpty() && !isViewingDrafts
}

/** A destructive action the user has been asked to confirm. */
sealed interface PendingConfirm {

    /** Permanently destroying the selection. Only ever raised from Trash. */
    data class Destroy(val messageIds: List<String>) : PendingConfirm

    data object EmptyTrash : PendingConfirm

    /**
     * Deleting a folder, and whatever is in it.
     *
     * [cachedCount] is what this machine holds, which is a floor rather than a
     * total — a folder only partly synced holds more on the server. Worded to
     * say so, because "delete 12 messages" when it is really 400 is worse than
     * saying nothing.
     */
    data class DeleteFolder(val folder: EmailFolder, val cachedCount: Int) : PendingConfirm
}

sealed interface EmailEvent {
    data object Load : EmailEvent

    /** A different production opened; the mailbox starts over. */
    data object ProjectChanged : EmailEvent
    data object Refresh : EmailEvent

    /** Anything that acts on one message. */
    sealed interface Message : EmailEvent

    data object CloseMessage : Message

    /** The list reached its end — fetch the next batch. */
    data object LoadMore : EmailEvent

    data class SelectFolder(val folderName: String) : EmailEvent
    data class SelectMessage(val messageId: String) : Message
    data class QueryChanged(val value: String) : EmailEvent

    /** Narrows the search: fields, folders, read state, attachments. */
    data class SearchFiltersChanged(val query: EmailQuery) : EmailEvent

    /** Opens the composer. [replyTo] is null for a new message. */
    data class Compose(val mode: ComposeMode, val replyTo: EmailMessage? = null) : Message

    /** Something changed on the server. */
    data class Realtime(val event: EmailRealtimeEvent) : EmailEvent

    /** Managing the folder list itself. */
    sealed interface Folder : EmailEvent

    /** Opens the folder dialog. [folder] is null to create a new one. */
    data class EditFolder(val folder: EmailFolder? = null) : Folder

    data class FolderNameChanged(val value: String) : Folder

    /** Creates or renames, whichever the dialog was opened for. */
    data object SaveFolder : Folder

    /** Asks to delete the folder the dialog is editing. */
    data object DeleteFolder : Folder
    data object DismissFolderEdit : Folder

    /** Everything to do with ticked rows and acting on them. */
    sealed interface Selection : EmailEvent

    /** Ticks or unticks a row. */
    data class ToggleSelection(val messageId: String) : Selection
    data object ClearSelection : Selection

    /** Trash the selection — or destroy it, when already in Trash. */
    data object DeleteSelected : Selection

    /** One row's hover action: trash this message without a selection. */
    data class TrashMessage(val messageId: String) : Selection

    data class MoveSelected(val folderName: String) : Selection

    data object EmptyTrash : Selection

    /** Goes ahead with whatever [EmailUiState.pendingConfirm] holds. */
    data object ConfirmPending : Selection
    data object DismissConfirm : Selection

    /** Reopens a saved draft in the composer. */
    data class EditDraft(val draftId: String) : Message

    /** Fetches an attachment and writes it to the user's Downloads folder. */
    data class DownloadAttachment(
        val attachment: EmailAttachment,
        val messageId: String,
    ) : Message
}

sealed interface EmailEffect {
    /** Hand off to the composer, which is its own ViewModel and its own window. */
    data class OpenComposer(val mode: ComposeMode, val replyTo: EmailMessage?) : EmailEffect

    /** Reopen a saved draft in a composer window. */
    data class OpenDraft(val draftId: String) : EmailEffect
}

/**
 * Drives the mailbox: folders down the left, messages in the middle, the open
 * conversation on the right.
 *
 * Reads from the cache first and syncs behind it, so a folder the user has
 * visited before renders immediately. Composing lives in [ComposeViewModel] —
 * this class is already the busiest thing in the module.
 */
class EmailViewModel(
    private val mailbox: Mailbox,
    private val repository: EmailRepository,
    private val draftRepository: DraftRepository,
    /** The folder dialog and its requests — see [FolderEditor]. */
    val folderEditor: FolderEditor,
    /** The search box, which spans every synced folder — see [MailSearch]. */
    val search: MailSearch = MailSearch { emptyList() },
    /**
     * The drafts cursor. Required, not defaulted: a wrong clock here asks the
     * server for drafts saved before the epoch and gets nothing back.
     */
    private val nowMillis: () -> Long,
    /**
     * Told when a message is opened for reading. Hosts hang the badge-service
     * read here (`notification:read`, segment `email_label`) — the mailbox's
     * own markRead is IMAP state, not the badge ledger, and the two clear
     * independently.
     */
    private val onMessageRead: suspend (String) -> Unit = {},
    /** Attachment downloads, which keep their own state — see [AttachmentDownloader]. */
    val downloader: AttachmentDownloader = AttachmentDownloader(repository, store = null),
    /** The composers standing on the mailbox's bottom edge — see [ComposerDeck]. */
    val composers: ComposerDeck = ComposerDeck(),
) : ZillitViewModel<EmailUiState, EmailEvent, EmailEffect>(EmailUiState()) {

    /** Set when mail arrives mid-sync; drained when that sync finishes. */
    private var pendingResync = false

    /**
     * Syncs currently running.
     *
     * Its own counter rather than the `isLoading*` flags: those say which
     * spinner to show, and once a folder has mail on screen both are false
     * *while a sync is running* — so guarding on them let a burst of socket
     * events start a sync each.
     */
    private var inFlight = 0

    // No eager load: every call carries project and user in its header.

    override fun onEvent(event: EmailEvent) {
        when (event) {
            EmailEvent.Load -> loadFolders()
            EmailEvent.ProjectChanged -> {
                // The mailbox belongs to the production: folders, the open
                // message and every selection are the last one's.
                setState { EmailUiState() }
                loadFolders()
            }
            EmailEvent.Refresh -> currentState.selectedFolder?.let { syncFolder(it, more = false) }
            EmailEvent.LoadMore -> loadMore()
            is EmailEvent.QueryChanged -> search.term(event.value)
            is EmailEvent.SearchFiltersChanged -> search.filters(event.query)
            is EmailEvent.SelectFolder -> selectFolder(event.folderName)
            is EmailEvent.Selection -> onSelection(event)
            is EmailEvent.Realtime -> onRealtime(event.event)
            is EmailEvent.Message -> onMessage(event)
            is EmailEvent.Folder -> onFolder(event)
        }
    }

    private fun selectFolder(folderName: String) {
        val folder = currentState.folders.firstOrNull { it.name == folderName } ?: return

        setState {
            // Cached mail shows at once. Unlike the old paged version there is
            // no empty moment, because the folder's rows are already on disk.
            copy(
                selectedFolderName = folder.name,
                messages = mailbox.cachedMessages(folder.name),
                selectedMessageId = null,
                thread = emptyList(),
                error = null,
            )
        }
        // Opening a folder ends the search: the list is about to show that
        // folder, and leaving the box filled would say otherwise.
        search.clear()
        syncFolder(folder, more = false)
    }

    private fun loadFolders() {
        val cached = mailbox.cachedFolders()
        setState { copy(isLoadingFolders = cached.isEmpty(), folders = cached, error = null) }

        // Open whatever is cached straight away; a mail client that shows an
        // empty pane while it talks to the server makes the user wait to see
        // mail this machine already has.
        currentState.selectedFolder?.let { selectFolder(it.name) }

        launchResult(
            block = { mailbox.syncFolders() },
            onSuccess = { folders ->
                setState { copy(isLoadingFolders = false, folders = folders) }
                if (currentState.selectedFolderName == null) {
                    currentState.selectedFolder?.let { selectFolder(it.name) }
                }
            },
            onError = { setState { copy(isLoadingFolders = false, error = it.localised()) } },
        )
    }

    private fun loadMore() {
        val state = currentState
        if (state.isLoadingMore || !state.hasMore) return
        state.selectedFolder?.let { syncFolder(it, more = true) }
    }

    /**
     * Fetches the next uncached batch for a folder.
     *
     * The same call serves first load, refresh and scroll — see `Mailbox`, where
     * paging and caching are one mechanism. [more] only decides which spinner
     * the user sees.
     */
    private fun syncFolder(folder: EmailFolder, more: Boolean) {
        // Drafts are not IMAP: they live in Zillit's own store and are paged by
        // timestamp, so the uid diff below would sync an empty IMAP folder and
        // show nothing while the drafts sat elsewhere. See `EmailDraft`.
        if (folder.name.equals(EmailFolder.DRAFTS, ignoreCase = true)) {
            loadDrafts()
            return
        }

        inFlight++
        setState {
            copy(
                isLoadingMessages = !more && messages.isEmpty(),
                isLoadingMore = more,
                error = null,
            )
        }

        launchResult(
            block = { mailbox.syncNext(folder.name) },
            onSuccess = { page ->
                inFlight--
                // Guard against a slow folder the user has already left.
                if (currentState.selectedFolderName == folder.name) {
                    setState {
                        copy(
                            isLoadingMessages = false,
                            isLoadingMore = false,
                            messages = page.messages,
                            hasMore = page.hasMore,
                        )
                    }
                    if (pendingResync) {
                        pendingResync = false
                        syncFolder(folder, more = false)
                    }
                }
            },
            onError = { error ->
                inFlight--
                if (currentState.selectedFolderName == folder.name) {
                    setState {
                        copy(
                            isLoadingMessages = false,
                            isLoadingMore = false,
                            // Cached mail stays on screen behind the message:
                            // a failed refresh should not empty a readable list.
                            error = error.localised(),
                        )
                    }
                    // Not retried on failure: a server that just refused this
                    // folder will refuse it again, and a queued retry would
                    // become a loop.
                    pendingResync = false
                }
            },
        )
    }

    /**
     * Reacts to a server-side change.
     *
     * Coarse by design — see [EmailRealtimeEvent]. The folder list is refreshed
     * on any mail event because the sidebar's unread counts come from it, and
     * a count that lags behind the list is the thing users notice first.
     */
    private fun onRealtime(event: EmailRealtimeEvent) {
        when (event) {
            EmailRealtimeEvent.FoldersChanged -> refreshFolders()

            is EmailRealtimeEvent.ReadChanged -> setState {
                copy(messages = messages.map { if (it.uid == event.uid) it.copy(isRead = true) else it })
            }

            EmailRealtimeEvent.DraftsChanged -> if (currentState.isViewingDrafts) loadDrafts()

            is EmailRealtimeEvent.FolderChanged -> {
                refreshFolders()
                val open = currentState.selectedFolder ?: return
                // A change in a folder that is not open needs no work: it will
                // sync when the user opens it, and its unread count has just
                // been refreshed above.
                if (event.folderName == null || event.folderName.equals(open.name, ignoreCase = true)) {
                    requestSync(open)
                }
            }
        }
    }

    private fun loadDrafts() {
        setState { copy(isLoadingMessages = messages.isEmpty(), error = null) }

        launchResult(
            block = { draftRepository.drafts(nowMillis()) },
            onSuccess = { drafts ->
                if (currentState.isViewingDrafts) {
                    setState {
                        copy(
                            isLoadingMessages = false,
                            isLoadingMore = false,
                            drafts = drafts,
                            messages = drafts.map(EmailDraft::toSummary),
                            hasMore = false,
                        )
                    }
                }
            },
            onError = { error ->
                if (currentState.isViewingDrafts) {
                    setState { copy(isLoadingMessages = false, error = error.localised()) }
                }
            },
        )
    }

    /** Opening, closing and acting on a single message. */
    private fun onMessage(event: EmailEvent.Message) {
        when (event) {
            is EmailEvent.SelectMessage -> openMessage(event.messageId)
            is EmailEvent.EditDraft -> sendEffect(EmailEffect.OpenDraft(event.draftId))
            is EmailEvent.Compose -> sendEffect(EmailEffect.OpenComposer(event.mode, event.replyTo))
            EmailEvent.CloseMessage -> setState {
                copy(selectedMessageId = null, thread = emptyList())
            }
            is EmailEvent.DownloadAttachment -> launch {
                currentState.selectedFolder?.let { folder ->
                    downloader.download(event.attachment, event.messageId, folder.name)
                }
            }
        }
    }

    /**
     * Selecting rows, and what can be done with a selection.
     *
     * Split out of [onEvent] to keep the main dispatcher readable — these seven
     * cases are one feature, not seven.
     */
    private fun onSelection(event: EmailEvent.Selection) {
        when (event) {
            is EmailEvent.ToggleSelection -> setState {
                copy(
                    selectedIds = if (event.messageId in selectedIds) {
                        selectedIds - event.messageId
                    } else {
                        selectedIds + event.messageId
                    },
                )
            }
            EmailEvent.ClearSelection -> setState { copy(selectedIds = emptySet()) }
            EmailEvent.DeleteSelected -> {
                val folder = currentState.selectedFolder
                val selected = currentState.selectedMessages
                if (folder != null && selected.isNotEmpty()) trash(selected, folder.name)
            }
            is EmailEvent.TrashMessage -> {
                val folder = currentState.selectedFolder
                val message = currentState.messages.firstOrNull { it.id == event.messageId }
                if (folder != null && message != null) trash(listOf(message), folder.name)
            }
            is EmailEvent.MoveSelected -> moveSelected(event.folderName)
            EmailEvent.EmptyTrash -> setState { copy(pendingConfirm = PendingConfirm.EmptyTrash) }
            EmailEvent.ConfirmPending -> runPendingConfirm()
            EmailEvent.DismissConfirm -> setState { copy(pendingConfirm = null) }
        }
    }

    /**
     * The folder dialog, and what happens after it.
     *
     * [FolderEditor] owns the dialog and the requests; the consequences stay
     * here, because they are the mailbox's: a renamed folder is a different
     * folder to every mail call, and a deleted one takes its cached mail.
     */
    private fun onFolder(event: EmailEvent.Folder) {
        when (event) {
            is EmailEvent.EditFolder -> folderEditor.open(event.folder)
            is EmailEvent.FolderNameChanged -> folderEditor.nameChanged(event.value)
            EmailEvent.DismissFolderEdit -> folderEditor.dismiss()

            EmailEvent.SaveFolder -> launch {
                val renamed = folderEditor.state.value?.renaming?.name
                when (val saved = folderEditor.save(currentState.folders)) {
                    null -> Unit
                    is ZillitResult.Failure -> setState { copy(error = saved.error.localised()) }
                    is ZillitResult.Success -> {
                        // The sidebar is server-owned; re-reading it is how the
                        // folder gets its flags and its place in the order.
                        refreshFolders()
                        if (renamed != null && renamed == currentState.selectedFolderName) {
                            setState { copy(selectedFolderName = saved.data) }
                        }
                    }
                }
            }

            EmailEvent.DeleteFolder -> folderEditor.folderToDelete()?.let { folder ->
                // Asked, never done directly: this is the only action here that
                // can destroy mail the user did not choose to delete.
                setState {
                    copy(
                        pendingConfirm = PendingConfirm.DeleteFolder(
                            folder = folder,
                            cachedCount = mailbox.cachedMessages(folder.name).size,
                        ),
                    )
                }
            }
        }
    }

    private fun deleteFolder(folder: EmailFolder) {
        launchResult(
            block = { folderEditor.delete(folder) },
            onSuccess = {
                // Dropped from the cache as well as the sidebar. Left behind, it
                // stays readable on disk under a folder that no longer exists —
                // and comes back if someone recreates the name.
                mailbox.forget(folder.name)

                if (currentState.selectedFolderName.equals(folder.name, ignoreCase = true)) {
                    setState { copy(selectedFolderName = null, messages = emptyList()) }
                }
                refreshFolders()
                currentState.selectedFolder?.let { syncFolder(it, more = false) }
            },
            onError = { setState { copy(error = it.localised()) } },
        )
    }

    // -- move and delete ---------------------------------------------------

    /**
     * Trashes the selection, or asks before destroying it.
     *
     * The rule about which lives in [deleteIntentFor]. Destroying is the one
     * action in this module that cannot be undone, so it is the one that stops
     * to ask.
     */
    /** One hovered row or the whole tick set — the same trash rules. */
    private fun trash(selected: List<EmailSummary>, folderName: String) {
        when (val intent = deleteIntentFor(selected, folderName)) {
            is DeleteIntent.Destroy ->
                setState { copy(pendingConfirm = PendingConfirm.Destroy(intent.messageIds)) }

            is DeleteIntent.MoveToTrash -> {
                forget(selected.map { it.id })
                launch {
                    // One call per source folder: a move names a single source,
                    // and a selection can span folders.
                    intent.byFolder.forEach { (source, ids) ->
                        applyOrReload { repository.move(ids, source, EmailFolder.TRASH) }
                    }
                }
            }
        }
    }

    private fun moveSelected(target: String) {
        val state = currentState
        val folder = state.selectedFolder ?: return
        val selected = state.selectedMessages.ifEmpty { return }

        forget(selected.map { it.id })
        launch {
            selected.groupBy { it.folderName.ifBlank { folder.name } }
                .forEach { (source, group) ->
                    applyOrReload { repository.move(group.map { it.id }, source, target) }
                }
        }
    }

    private fun runPendingConfirm() {
        val pending = currentState.pendingConfirm ?: return
        setState { copy(pendingConfirm = null) }

        when (pending) {
            is PendingConfirm.Destroy -> {
                forget(pending.messageIds)
                launch { applyOrReload { repository.deletePermanently(pending.messageIds) } }
            }
            PendingConfirm.EmptyTrash -> {
                forget(currentState.messages.map { it.id })
                launch { applyOrReload { repository.emptyTrash() } }
            }
            is PendingConfirm.DeleteFolder -> deleteFolder(pending.folder)
        }
    }

    /**
     * Drops messages from the list at once, before the server has answered.
     *
     * Mail actions have to feel immediate — a row that lingers for a round trip
     * reads as a click that did not register. [applyOrReload] puts anything the
     * server rejected back.
     */
    private fun forget(ids: List<String>) {
        val gone = ids.toSet()
        setState {
            copy(
                messages = messages.filterNot { it.id in gone },
                selectedIds = selectedIds - gone,
                selectedMessageId = selectedMessageId?.takeUnless { it in gone },
                thread = if (selectedMessageId in gone) emptyList() else thread,
            )
        }
    }

    /**
     * Runs a mailbox change, re-syncing either way.
     *
     * On success because the cache still holds the old rows; on failure because
     * the optimistic removal above has to be undone, and re-reading the folder
     * is both simpler and more truthful than trying to restore what was there.
     */
    private suspend fun applyOrReload(block: suspend () -> ZillitResult<Unit>) {
        val result = block()

        // A plain re-sync is enough either way: `syncNext` diffs the server's
        // uid list against the cache and drops whatever is no longer there, so
        // a successful move removes the rows and a failed one restores them.
        currentState.selectedFolder?.let { syncFolder(it, more = false) }
        refreshFolders()

        // Reported *after* the reload, not before: `syncFolder` clears the
        // error as it starts, so setting it first meant a failed move silently
        // undid itself — the row vanished, came back, and said nothing.
        if (result is ZillitResult.Failure) {
            setState { copy(error = result.error.localised()) }
        }
    }

    private fun refreshFolders() {
        launchResult(
            block = { mailbox.syncFolders() },
            onSuccess = { folders -> setState { copy(folders = folders) } },
            // Silent: a failed background refresh must not put an error banner
            // over mail the user is reading.
            onError = { },
        )
    }

    /**
     * Syncs, or queues one if a sync is already running.
     *
     * Without the queue a mail that lands *during* a sync is lost until the
     * next event: the running sync may already have read the uid list. Without
     * the guard, ten mails arriving at once would start ten syncs.
     */
    private fun requestSync(folder: EmailFolder) {
        if (inFlight > 0) {
            pendingResync = true
            return
        }
        syncFolder(folder, more = false)
    }

    /**
     * Opens a message and loads its conversation.
     *
     * The row highlights and marks read immediately; the thread follows. Waiting
     * for the fetch before showing the selection makes the click feel
     * unregistered.
     */
    private fun openMessage(messageId: String) {
        val state = currentState
        // A draft opens for editing rather than reading — there is no thread to
        // fetch, and `email-trail` does not know about drafts anyway.
        if (state.isViewingDrafts) {
            sendEffect(EmailEffect.OpenDraft(messageId))
            return
        }
        if (state.selectedMessageId == messageId && state.thread.isNotEmpty()) return

        val folder = state.selectedFolder ?: return
        val wasUnread = state.messages.firstOrNull { it.id == messageId }?.isRead == false
        mailbox.markRead(folder.name, messageId)
        // Only mail that was unread has a badge record to clear — an emit per
        // click on already-read mail cost a settle wait and three requests.
        if (wasUnread) launch { onMessageRead(messageId) }

        setState {
            copy(
                selectedMessageId = messageId,
                thread = emptyList(),
                isLoadingThread = true,
                messages = messages.map { if (it.id == messageId) it.copy(isRead = true) else it },
            )
        }

        launchResult(
            block = { repository.trail(folder.name, listOf(messageId)) },
            onSuccess = { thread ->
                if (currentState.selectedMessageId == messageId) {
                    setState { copy(isLoadingThread = false, thread = thread) }
                }
            },
            onError = { error ->
                if (currentState.selectedMessageId == messageId) {
                    setState { copy(isLoadingThread = false, error = error.localised()) }
                }
            },
        )
    }
}
