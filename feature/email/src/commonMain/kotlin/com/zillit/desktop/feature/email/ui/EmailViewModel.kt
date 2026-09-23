package com.zillit.desktop.feature.email.ui

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.email.data.FolderSyncResult
import com.zillit.desktop.feature.email.data.Mailbox
import com.zillit.desktop.feature.email.domain.ActiveMailbox
import com.zillit.desktop.feature.email.domain.ConversationViewRepository
import com.zillit.desktop.feature.email.domain.DraftRepository
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.EmailFilters
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.EmailRealtimeEvent
import com.zillit.desktop.feature.email.domain.EmailRepository
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.domain.MailboxDirectory
import com.zillit.desktop.feature.email.domain.MailboxKind
import com.zillit.desktop.feature.email.domain.MailboxPreferences
import com.zillit.desktop.feature.email.domain.SELECTION_LIMIT
import com.zillit.desktop.feature.email.domain.asThread
import com.zillit.desktop.feature.email.domain.printableHtml
import com.zillit.desktop.feature.email.domain.selectionMembers
import com.zillit.desktop.feature.email.domain.threadMembersByFolder
import com.zillit.desktop.feature.email.domain.toSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Drives the mailbox: the switcher and folders down the left, the list in
 * the middle, the open conversation on the right.
 *
 * Reads from the cache first and syncs behind it — the whole mailbox, folder
 * by folder, the way the web's `useEmailSync` does on open — so a folder the
 * user has visited before renders immediately and conversations can count
 * the replies that live in other folders. Composing lives in
 * [ComposeViewModel]; the bulk actions on ticked rows in [MailActions].
 */
@Suppress("LongParameterList", "TooManyFunctions") // Every collaborator the mailbox talks to; a handler per event.
class EmailViewModel(
    private val mailbox: Mailbox,
    private val repository: EmailRepository,
    private val draftRepository: DraftRepository,
    /** The folder dialog and its requests — see [FolderEditor]. */
    val folderEditor: FolderEditor,
    /**
     * The drafts cursor. Required, not defaulted: a wrong clock here asks the
     * server for drafts saved before the epoch and gets nothing back.
     */
    private val nowMillis: () -> Long,
    /** The badge ledger's hooks for mail — see [MailBadges]. */
    private val badges: MailBadges = MailBadges(),
    /** Attachment downloads, which keep their own state — see [AttachmentDownloader]. */
    val downloader: AttachmentDownloader = AttachmentDownloader(repository, store = null),
    /** Which of the two mailboxes every call addresses; flipped by the switcher. */
    val activeMailbox: ActiveMailbox = ActiveMailbox(),
    /** Where the two mailboxes come from; null on a build with one mailbox. */
    private val directory: MailboxDirectory? = null,
    private val preferences: MailboxPreferences = MailboxPreferences.None,
    /** The conversation-view setting of the active mailbox; null hides the dialog. */
    private val conversationView: ConversationViewRepository? = null,
) : ZillitViewModel<EmailUiState, EmailEvent, EmailEffect>(EmailUiState()) {

    /** Set when mail arrives mid-sync; drained when that sync finishes. */
    private var pendingResync: Set<String> = emptySet()

    /** The full pass in flight, if any — folder events queue behind it. */
    private var syncJob: Job? = null

    /** Which folder was auto-opened, so a folder opens its newest row once, not on every batch. */
    private var autoOpenedFolder: String? = null

    /** The bulk actions, kept out of this class for its size. */
    private val actions = MailActions(this)

    /** Messages other screens ask the mailbox to start; the window collects them. */
    val composeRequests: ComposeRequests = ComposeRequests()

    @Suppress("CyclomaticComplexMethod") // One branch per event; every one delegates.
    override fun onEvent(event: EmailEvent) {
        when (event) {
            EmailEvent.Load -> load()
            EmailEvent.ProjectChanged -> {
                // The mailbox belongs to the production: folders, the open
                // message and every selection are the last one's.
                autoOpenedFolder = null
                syncJob?.cancel()
                setState { EmailUiState() }
                load()
            }
            EmailEvent.Refresh -> refresh()
            is EmailEvent.SelectFolder -> selectFolder(event.folderName)
            is EmailEvent.QueryChanged -> setState { copy(searchTerm = event.value).regrouped() }
            EmailEvent.ToggleSearchAllFolders -> setState { copy(searchAllFolders = !searchAllFolders).regrouped() }
            is EmailEvent.FiltersChanged -> setState { copy(filters = event.filters).regrouped() }
            EmailEvent.ClearFilters -> setState { copy(filters = EmailFilters.None).regrouped() }
            is EmailEvent.Selection -> actions.onSelection(event)
            is EmailEvent.Realtime -> onRealtime(event.event)
            is EmailEvent.Message -> onMessage(event)
            is EmailEvent.Folder -> onFolder(event)
            is EmailEvent.Mailbox -> onMailbox(event)
            EmailEvent.OpenConversationDialog -> setState { copy(conversationDialog = true) }
            EmailEvent.DismissConversationDialog -> setState { copy(conversationDialog = false) }
            is EmailEvent.ConversationViewChanged -> setConversationView(event.enabled)
            EmailEvent.DismissError -> setState { copy(error = null) }
            EmailEvent.DismissNotice -> setState { copy(notice = null) }
        }
    }

    // -- opening -------------------------------------------------------------

    /**
     * Opens the mailbox: which mailboxes exist and which is active, then the
     * cached folders and rows at once, then the full sync behind them.
     *
     * Asked again every time the window comes back — a workspace tab switch
     * disposes the mailbox's composition, not this view model — and then it
     * resumes where the user was (see [loadFolders]), unless the mailbox that
     * opens is no longer the one whose mail was on screen.
     */
    private fun load() {
        launch {
            val before = currentState.mailboxes
            resolveMailboxes()
            if (movedMailbox(before, currentState.mailboxes)) {
                // Accounts membership lost while the user was in another tool,
                // say: nothing on screen belongs to the mailbox that opens, so
                // it starts over, as a flip of the switcher does.
                syncJob?.cancel()
                autoOpenedFolder = null
                setState {
                    EmailUiState(mailboxes = mailboxes, conversationView = conversationView, tourOpen = tourOpen)
                }
            }
            loadFolders()
        }
    }

    /**
     * Whether [after] opens another mailbox than the one [before] showed — by
     * kind, or by address once both are known (a failed ask leaves it blank).
     */
    private fun movedMailbox(before: MailboxSwitch, after: MailboxSwitch): Boolean {
        if (before.active != after.active) return true
        val was = before.activeAddress
        val now = after.activeAddress
        return was.isNotBlank() && now.isNotBlank() && !was.equals(now, ignoreCase = true)
    }

    /**
     * The two identities, and the remembered choice between them.
     *
     * Read fresh every open: the web refetches the project on entry because
     * Accounts-department membership changes while the user is elsewhere,
     * and a stale gate would show a mailbox the user can no longer open —
     * or hide one they just gained. A remembered Accounts choice with no
     * Accounts mailbox any more falls back to personal, as the web does.
     */
    private suspend fun resolveMailboxes() {
        val source = directory ?: return
        val personal = (source.personal() as? ZillitResult.Success)?.data
        val accounts = (source.accounts() as? ZillitResult.Success)?.data
        val remembered = preferences.activeMailbox() ?: MailboxKind.Personal
        val active = if (remembered == MailboxKind.Accounts && accounts != null) remembered else MailboxKind.Personal
        activeMailbox.switch(active, if (active == MailboxKind.Accounts) accounts else personal)
        setState {
            copy(
                mailboxes = mailboxes.copy(personal = personal, accounts = accounts, active = active),
                conversationView = (if (active == MailboxKind.Accounts) accounts else personal)
                    ?.conversationView ?: (active == MailboxKind.Personal),
            )
        }
        refreshMailboxUnread()
        if (accounts != null && !preferences.hasSeenMailboxTour()) {
            setState { copy(tourOpen = true) }
        }
    }

    private fun loadFolders() {
        val cached = mailbox.cachedFolders()
        // A folder still open means the window is coming back to a mailbox it
        // was showing; a first open, a new production and a switched mailbox
        // all arrive here with none.
        val reopened = currentState.selectedFolderName
        val resuming = reopened != null && cached.any { it.name == reopened }
        setState {
            copy(
                isLoadingFolders = cached.isEmpty(),
                folders = cached,
                everything = mailbox.cachedMessages(),
                error = null,
            ).regrouped()
        }

        // Open whatever is cached straight away; a mail client that shows an
        // empty pane while it talks to the server makes the user wait to see
        // mail this machine already has.
        if (resuming) resumeFolder() else currentState.selectedFolder?.let { showFolder(it.name) }

        refreshFolderBadges()
        launchResult(
            block = { mailbox.syncFolders() },
            onSuccess = { folders ->
                setState { copy(isLoadingFolders = false, folders = folders) }
                if (currentState.selectedFolderName == null) {
                    currentState.selectedFolder?.let { showFolder(it.name) }
                }
                syncEverything(showProgress = true)
            },
            onError = { setState { copy(isLoadingFolders = false, error = it.localised()) } },
        )
    }

    private fun refresh() {
        if (currentState.isRefreshing) return
        setState { copy(isRefreshing = true) }
        launchResult(
            block = { mailbox.syncFolders() },
            onSuccess = { folders ->
                setState { copy(folders = folders) }
                if (currentState.isViewingDrafts) loadDrafts()
                syncEverything(showProgress = true) { setState { copy(isRefreshing = false) } }
            },
            onError = { setState { copy(isRefreshing = false, error = it.localised()) } },
        )
    }

    // -- folders ---------------------------------------------------------------

    private fun selectFolder(folderName: String) {
        val folder = currentState.folders.firstOrNull { it.name == folderName } ?: return
        if (folder.name == currentState.selectedFolderName) return
        showFolder(folder.name)
    }

    /**
     * Puts a folder on screen from the cache, closing what was open: the web
     * clears the reading pane, the selection and the search on a folder
     * click, then opens the folder's newest row.
     */
    private fun showFolder(folderName: String) {
        setState {
            copy(
                selectedFolderName = folderName,
                messages = mailbox.cachedMessages(folderName),
                openRowId = null,
                thread = emptyList(),
                selectedIds = emptySet(),
                searchTerm = "",
                error = null,
            ).regrouped()
        }
        if (currentState.isViewingDrafts) {
            loadDrafts()
        } else {
            openFirstIfNone()
        }
    }

    /**
     * Puts the open folder back as the user left it — the message in the
     * pane, the ticks, the search — with its rows re-read from the cache the
     * syncs kept current meanwhile.
     *
     * Not [showFolder]: that is a folder being *chosen*, and closes all of it.
     * Coming back to the mailbox from another tab is not choosing a folder,
     * and the web keeps its open email across a remount too
     * (`currentEmailData`, whose auto-open stands down while it is listed).
     */
    private fun resumeFolder() {
        if (currentState.isViewingDrafts) {
            loadDrafts()
            return
        }
        val folder = currentState.selectedFolderName ?: return
        setState { copy(messages = mailbox.cachedMessages(folder)).regrouped() }
        openFirstIfNone()
    }

    /**
     * The web opens a folder's newest row as soon as its rows are in
     * (`useEmailListData`'s auto-open), once per folder: a mailbox that opens
     * onto an empty pane looks broken, but re-opening the first row on every
     * batch would fight the user's own clicks.
     */
    private fun openFirstIfNone() {
        val state = currentState
        val folder = state.selectedFolderName ?: return
        if (state.isViewingDrafts || state.openRowId != null || autoOpenedFolder == folder) return
        val first = state.rows.firstOrNull() ?: return
        autoOpenedFolder = folder
        openRow(first.id)
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
                            drafts = drafts,
                            messages = drafts.map(EmailDraft::toSummary),
                        ).regrouped()
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

    // -- syncing ---------------------------------------------------------------

    /**
     * The full pass — every folder, the open one first so it fills before
     * the rest. One at a time: a second request while one runs is queued
     * behind it as a resync of whatever folders asked.
     */
    private fun syncEverything(showProgress: Boolean, onDone: () -> Unit = {}) {
        if (syncJob?.isActive == true) {
            pendingResync = pendingResync + currentState.folders.map { it.name }
            return
        }
        syncFolders(currentState.folders.map { it.name }, showProgress, onDone)
    }

    /**
     * Re-syncs the named folders — the web's `syncFolders`, run for a socket
     * event that names a folder — or every folder for the full pass.
     */
    private fun syncFolders(folderNames: List<String>, showProgress: Boolean, onDone: () -> Unit = {}) {
        val open = currentState.selectedFolderName
        val ordered = currentState.folders
            .filter { it.name in folderNames }
            .sortedBy { if (it.name == open) 0 else 1 }
        if (ordered.isEmpty()) {
            onDone()
            return
        }
        if (showProgress) setState { copy(sync = SyncProgress(0), isLoadingMessages = messages.isEmpty()) }

        syncJob = launch {
            try {
                mailbox.syncEverything(
                    folders = ordered,
                    onProgress = { percent -> if (showProgress) setState { copy(sync = SyncProgress(percent)) } },
                    onFolderChanged = ::onFolderSynced,
                    nowMillis = nowMillis,
                )
            } finally {
                setState { copy(sync = null, isLoadingMessages = false) }
                onDone()
                drainPendingResync()
            }
        }
    }

    private fun drainPendingResync() {
        val queued = pendingResync
        pendingResync = emptySet()
        if (queued.isNotEmpty()) syncFolders(queued.toList(), showProgress = false)
    }

    /**
     * A batch landed: the open folder's rows refresh, every conversation's
     * count with them, and the badge ledger is squared with what the server
     * said the folder holds — off the sync's own path so a slow ledger never
     * holds the list back.
     */
    private suspend fun onFolderSynced(result: FolderSyncResult) {
        setState {
            val open = selectedFolderName == result.folderName && !isViewingDrafts
            copy(
                messages = if (open) mailbox.cachedMessages(result.folderName) else messages,
                everything = mailbox.cachedMessages(),
                isLoadingMessages = false,
            ).regrouped()
        }
        openFirstIfNone()
        launch {
            badges.onFolderSynced(
                MailFolderSync(
                    folderName = result.folderName,
                    serverUids = result.serverUids,
                    messages = mailbox.cachedMessages(result.folderName),
                    complete = result.complete,
                    listedAt = result.listedAt,
                    mailboxAddress = currentState.activeMailboxAddress.takeIf { it.isNotBlank() },
                ),
            )
            refreshFolderBadges()
        }
    }

    /**
     * Re-reads the open folder and every conversation's members from the
     * cache — after a move, a delete, or a sync — and re-syncs the folders
     * named so the server's view replaces the optimistic one.
     */
    internal fun reloadAfterChange(folderNames: List<String?>) {
        setState {
            copy(
                messages = if (isViewingDrafts) messages else mailbox.cachedMessages(selectedFolderName.orEmpty()),
                everything = mailbox.cachedMessages(),
            ).regrouped()
        }
        val named = folderNames.filterNotNull().filter { it.isNotBlank() }.distinct()
        if (syncJob?.isActive == true) {
            pendingResync = pendingResync + named
        } else {
            syncFolders(named, showProgress = false)
        }
        refreshFolders()
    }

    internal fun refreshFolders() {
        launchResult(
            block = { mailbox.syncFolders() },
            onSuccess = { folders -> setState { copy(folders = folders) } },
            // Silent: a failed background refresh must not put an error banner
            // over mail the user is reading.
            onError = { },
        )
        refreshFolderBadges()
    }

    private fun refreshFolderBadges() {
        // Null is a failed ask; the last split stands rather than dropping the
        // folder list back onto IMAP counts mid-session.
        launch {
            badges.folderBadges(currentState.activeMailboxAddress.takeIf { it.isNotBlank() })
                ?.let { split -> setState { copy(folderBadges = split) } }
        }
        refreshMailboxUnread()
    }

    private fun refreshMailboxUnread() {
        launch {
            badges.mailboxUnread()?.let { unread ->
                setState { copy(mailboxes = mailboxes.copy(unreadByAddress = unread)) }
            }
        }
    }

    // -- realtime --------------------------------------------------------------

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

            // `email:read` — opened on another of this person's devices. The
            // row, the cache and the badge ledger all learn it, as for a click
            // here (the web's `email_read` listener does the same). A uid the
            // open folder does not hold waits for its own folder's sync, and
            // one already read here was handed to the ledger by that click.
            is EmailRealtimeEvent.ReadChanged -> {
                val folder = currentState.selectedFolder ?: return
                val message = currentState.messages.firstOrNull { it.uid == event.uid } ?: return
                if (message.isRead) return
                mailbox.markRead(folder.name, message.id)
                setState {
                    copy(messages = messages.map { if (it.uid == event.uid) it.copy(isRead = true) else it })
                        .regrouped()
                }
                launch {
                    badges.onMessageRead(
                        MailRead(folder.name, event.uid, message.id, activeMailboxAddress()),
                    )
                    refreshFolderBadges()
                }
            }

            EmailRealtimeEvent.DraftsChanged -> if (currentState.isViewingDrafts) loadDrafts()

            is EmailRealtimeEvent.FolderChanged -> {
                refreshFolders()
                // A named folder re-syncs on its own; an unnamed change — a
                // move, which touches two — re-syncs the open folder and
                // Inbox, the two the user would notice being stale.
                val named = event.folderName?.let { listOf(it) }
                    ?: listOfNotNull(currentState.selectedFolderName, EmailFolder.INBOX).distinct()
                reloadAfterChange(named)
            }
        }
    }

    // -- messages --------------------------------------------------------------

    /** Opening, closing and acting on a single message. */
    private fun onMessage(event: EmailEvent.Message) {
        when (event) {
            is EmailEvent.SelectMessage -> openRow(event.rowId)
            EmailEvent.OpenFirst -> currentState.rows.firstOrNull()?.let { openRow(it.id) }
            is EmailEvent.EditDraft -> sendEffect(EmailEffect.OpenDraft(event.draftId))
            is EmailEvent.Compose ->
                sendEffect(EmailEffect.OpenComposer(event.mode, event.replyTo, event.addressedTo, event.about))
            EmailEvent.CloseMessage -> setState { copy(openRowId = null, thread = emptyList()) }
            is EmailEvent.DownloadAttachment -> launch {
                downloader.download(event.attachment, event.messageId, event.folderName)
            }
            is EmailEvent.Print -> printOpen(event.message)
            EmailEvent.PopOut -> {
                val newest = currentState.newestOpen ?: return
                sendEffect(EmailEffect.PopOutThread(newest.subject, currentState.thread))
            }
            is EmailEvent.AddToContacts -> sendEffect(EmailEffect.AddToContacts(event.address))
        }
    }

    /**
     * Prints the open conversation as the web's toolbar does — every message
     * with conversation view on, only the newest without it — or one message
     * from its own menu.
     */
    private fun printOpen(one: EmailMessage?) {
        val state = currentState
        val messages = when {
            one != null -> listOf(one)
            state.conversationView -> state.thread
            else -> listOfNotNull(state.newestOpen)
        }
        val subject = messages.maxByOrNull { it.receivedAtMillis }?.subject.orEmpty()
        if (messages.isNotEmpty()) {
            // A fresh nonce per page: the print call is the one script the
            // page's policy admits, and a body cannot guess it.
            val nonce = kotlin.uuid.Uuid.random().toString().replace("-", "")
            sendEffect(EmailEffect.Print(subject, printableHtml(subject, messages, nonce)))
        }
    }

    /**
     * Opens a row and loads its conversation.
     *
     * The row highlights and marks read immediately; the thread follows. Waiting
     * for the fetch before showing the selection makes the click feel
     * unregistered. With conversation view on, every message of the thread
     * this machine holds is fetched — one call per folder, in parallel, the
     * web's `fetchCurrentEmailFromDb` — and each is marked read as it opens.
     */
    private fun openRow(rowId: String) {
        val state = currentState
        // A draft opens for editing rather than reading — there is no thread to
        // fetch, and `get-emails` does not know about drafts anyway.
        if (state.isViewingDrafts) {
            sendEffect(EmailEffect.OpenDraft(rowId))
            return
        }
        if (state.openRowId == rowId && state.thread.isNotEmpty()) return
        val row = state.rows.firstOrNull { it.id == rowId } ?: return
        val folder = state.selectedFolder ?: return

        val members = if (state.conversationView && !state.searchAllFolders) {
            threadMembersByFolder(row.threadId, state.everything, folder.name)
                .ifEmpty { mapOf(row.message.folderName.ifBlank { folder.name } to listOf(row.message)) }
        } else {
            mapOf(row.message.folderName.ifBlank { folder.name } to listOf(row.message))
        }

        markRead(members)
        setState {
            copy(
                openRowId = rowId,
                thread = emptyList(),
                isLoadingThread = true,
                messages = messages.map { if (members.containsMessage(it.id)) it.copy(isRead = true) else it },
                everything = everything.map { if (members.containsMessage(it.id)) it.copy(isRead = true) else it },
            ).regrouped()
        }

        launch {
            val fetched = coroutineScope {
                members.map { (folderName, rows) ->
                    async { repository.trail(folderName, rows.map { it.id }) }
                }.awaitAll()
            }
            if (currentState.openRowId != rowId) return@launch
            val messages = fetched.filterIsInstance<ZillitResult.Success<List<EmailMessage>>>()
                .flatMap { it.data }
                .distinctBy { it.id }
                .asThread()
            val failure = fetched.filterIsInstance<ZillitResult.Failure>().firstOrNull()
            setState {
                copy(
                    isLoadingThread = false,
                    thread = messages,
                    error = if (messages.isEmpty()) failure?.error?.localised() else error,
                )
            }
        }
    }

    private fun Map<String, List<EmailSummary>>.containsMessage(id: String): Boolean =
        values.any { rows -> rows.any { it.id == id } }

    /** Every unread copy the click opens is read now: cache, and badge ledger. */
    private fun markRead(members: Map<String, List<EmailSummary>>) {
        members.forEach { (folderName, rows) ->
            rows.forEach { row ->
                mailbox.markRead(folderName, row.id)
                // Only mail that was unread has a badge record to clear — an
                // emit per click on already-read mail cost a settle wait and
                // three requests. (Mail read elsewhere but still badged is
                // the folder sync's job.)
                if (!row.isRead) {
                    launch {
                        badges.onMessageRead(MailRead(folderName, row.uid, row.id, activeMailboxAddress()))
                        refreshFolderBadges()
                    }
                }
            }
        }
    }

    internal fun activeMailboxAddress(): String? = currentState.activeMailboxAddress.takeIf { it.isNotBlank() }

    // -- folder management -----------------------------------------------------

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
                        val notice =
                            if (renamed == null) S.desktop_drive_activity_folder_created else S.dd_folder_renamed
                        setState { copy(notice = str(notice)) }
                        if (renamed != null && renamed == currentState.selectedFolderName) {
                            setState { copy(selectedFolderName = saved.data) }
                        }
                    }
                }
            }

            is EmailEvent.DeleteFolder -> {
                val folder = event.folder ?: folderEditor.folderToDelete() ?: return
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

    internal fun deleteFolder(folder: EmailFolder) {
        launchResult(
            block = { folderEditor.delete(folder) },
            onSuccess = {
                // Dropped from the cache as well as the sidebar. Left behind, it
                // stays readable on disk under a folder that no longer exists —
                // and comes back if someone recreates the name.
                mailbox.forget(folder.name)
                if (currentState.selectedFolderName.equals(folder.name, ignoreCase = true)) {
                    setState {
                        copy(selectedFolderName = null, messages = emptyList(), openRowId = null, thread = emptyList())
                    }
                    currentState.selectedFolder?.let { showFolder(it.name) }
                }
                setState { copy(notice = str(S.desktop_drive_activity_folder_deleted)) }
                refreshFolders()
            },
            onError = { setState { copy(error = it.localised()) } },
        )
    }

    // -- the switcher ----------------------------------------------------------

    private fun onMailbox(event: EmailEvent.Mailbox) {
        when (event) {
            is EmailEvent.SwitchMailbox -> switchMailbox(event.kind)
            EmailEvent.ShowTour -> setState { copy(tourOpen = true) }
            EmailEvent.DismissTour -> {
                setState { copy(tourOpen = false) }
                launch { preferences.markMailboxTourSeen() }
            }
        }
    }

    /**
     * Flips the module onto the other mailbox — the web remounts it, which is
     * what starting over here amounts to: folders, rows, the open message and
     * the selection all belong to the mailbox they were read from.
     */
    private fun switchMailbox(kind: MailboxKind) {
        val state = currentState
        if (kind == state.mailboxes.active) return
        val identity = state.mailboxes.identity(kind) ?: return
        syncJob?.cancel()
        autoOpenedFolder = null
        activeMailbox.switch(kind, identity)
        launch { preferences.setActiveMailbox(kind) }
        setState {
            EmailUiState(
                mailboxes = mailboxes.copy(active = kind),
                conversationView = identity.conversationView ?: (kind == MailboxKind.Personal),
            )
        }
        loadFolders()
    }

    /**
     * Optimistic, and reverted on failure — Android's exact sequence: flip,
     * PATCH, put it back if the server said no. The list regroups at once;
     * the web reloads the page for the same effect.
     */
    private fun setConversationView(enabled: Boolean) {
        val target = conversationView ?: return
        val previous = currentState.conversationView
        setState {
            copy(conversationView = enabled, isSavingConversationView = true, conversationDialog = false).regrouped()
        }
        launchResult(
            block = { target.setEnabled(enabled) },
            onSuccess = {
                setState {
                    val notice =
                        if (enabled) S.desktop_email_conversation_view_on else S.desktop_email_conversation_view_off
                    copy(isSavingConversationView = false, notice = str(notice))
                }
            },
            onError = { error ->
                setState {
                    copy(conversationView = previous, isSavingConversationView = false, error = error.localised())
                        .regrouped()
                }
            },
        )
    }

    // -- what the actions need -------------------------------------------------

    internal fun forgetOpen(ids: Collection<String>) {
        val gone = ids.toSet()
        setState {
            copy(
                messages = messages.filterNot { it.id in gone },
                everything = everything.filterNot { it.id in gone },
                selectedIds = selectedIds - gone,
                openRowId = openRowId?.takeUnless { it in gone },
                thread = if (openRowId in gone) emptyList() else thread.filterNot { it.id in gone },
            ).regrouped()
        }
    }

    /** The messages a ticked selection stands for, conversation view honoured. */
    internal fun selectionTargets(ids: Set<String> = currentState.selectedIds): List<EmailSummary> {
        val state = currentState
        val ticked = state.rows.filter { it.id in ids }.map { it.message }
        return selectionMembers(ticked, state.everything, state.conversationView, state.selectedFolder?.name.orEmpty())
    }

    /** Runs one mail call, reports how it went, and re-syncs the [folders] it touched. */
    internal fun runOnMailbox(
        block: suspend () -> ZillitResult<Unit>,
        done: String,
        folders: List<String?> = emptyList(),
    ) {
        launch {
            when (val result = block()) {
                is ZillitResult.Success -> setState { copy(notice = done) }
                is ZillitResult.Failure -> setState { copy(error = result.error.localised()) }
            }
            reloadAfterChange(folders)
        }
    }

    internal fun reloadDrafts() = loadDrafts()

    internal fun update(reducer: EmailUiState.() -> EmailUiState) = setState(reducer)

    internal fun ledgerRead(folder: String, uid: Int, messageId: String) {
        launch { badges.onMessageRead(MailRead(folder, uid, messageId, activeMailboxAddress())) }
    }

    internal fun warn(message: String) = ZillitLog.w(TAG) { message }

    internal val drafts: DraftRepository get() = draftRepository
    internal val mail: EmailRepository get() = repository
    internal val store: Mailbox get() = mailbox

    private companion object {
        const val TAG = "Email"
    }
}

/** The web's `EMAIL_SELECTION_MAX_LIMIT`, re-exported for the screen's copy. */
internal const val MAX_SELECTION = SELECTION_LIMIT
