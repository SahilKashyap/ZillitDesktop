package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRepository
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.EmailTemplate
import com.zillit.desktop.feature.documentdistribution.domain.LibraryGrouping
import com.zillit.desktop.feature.documentdistribution.domain.LibraryPage
import com.zillit.desktop.feature.documentdistribution.domain.LibraryQuery
import com.zillit.desktop.feature.documentdistribution.domain.LibrarySort
import com.zillit.desktop.feature.documentdistribution.domain.NewDistribution
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate

/**
 * The Document Distribution tool's one view model.
 *
 * ## Loading is per destination, not per screen
 *
 * [load] decides what a page needs and fetches exactly that. Screens never
 * fetch, which is what lets a send from the composer correct the History count
 * behind it: every mutation ends by reloading the current destination, and
 * arriving at a stale page reloads it.
 *
 * ## The viewer is resolved before anything else
 *
 * Rights decide which tabs exist and whether the tool renders at all, so
 * [start] reads them first. The view model is built once for the whole app —
 * before any production is open — so the viewer is a lambda resolved in
 * [start], never a constructor snapshot. Capturing it at construction fixes
 * every user as "rights unknown" for the life of the process.
 */
@Suppress("TooManyFunctions") // One handler per user action; see detekt.yml.
class DocDistViewModel(
    private val repository: DocDistRepository,
    /** Who is looking, read at start rather than at construction. See the class doc. */
    private val viewer: () -> DocDistViewer,
    /**
     * Today, in the machine's zone.
     *
     * Injected because "Today ·" and "Yesterday ·" are wall-clock questions and
     * this is common code with no clock in it — and because a grouping function
     * that reads the clock itself cannot be pinned by a test.
     */
    private val today: () -> LocalDate,
) : ZillitViewModel<DocDistUiState, DocDistEvent, DocDistEffect>(
    DocDistUiState(viewer = viewer()),
) {

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var started = false

    /**
     * Resolves who this is, then opens their landing page.
     *
     * Called when the tool is first shown rather than from `init`, and
     * idempotent because a torn-off window and the tab in the frame are the
     * same view model shown twice.
     */
    fun start() {
        if (started) return
        started = true
        val identity = viewer()
        setState {
            copy(viewer = identity, destination = DocDistDestination.landing(identity))
        }
        if (!identity.isBlocked) load(currentState.destination)
    }

    /** Re-reads rights when the open production changes. */
    fun onProjectChanged() {
        started = false
        setState { DocDistUiState(viewer = viewer()) }
        start()
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One branch per user action.
    override fun onEvent(event: DocDistEvent) {
        when (event) {
            DocDistEvent.Refresh -> load(currentState.destination)
            DocDistEvent.ClearNotice -> setState { copy(notice = null) }
            DocDistEvent.DismissPrompt -> setState { copy(prompt = null) }
            DocDistEvent.ConfirmPrompt -> currentState.prompt?.let { prompt ->
                setState { copy(prompt = null) }
                onEvent(prompt.event)
            }

            is DocDistEvent.Open -> {
                setState { copy(destination = event.destination, error = null) }
                load(event.destination)
            }

            // -- library ---------------------------------------------------

            is DocDistEvent.OpenFolder -> {
                setState {
                    copy(
                        currentFolderId = event.folderId,
                        // Selection is per folder: carrying it across a
                        // navigation means a bulk delete hits rows the user can
                        // no longer see.
                        selectedDocumentIds = emptySet(),
                        search = "",
                    )
                }
                loadLibrary()
            }

            is DocDistEvent.CreateFolder -> mutate(
                { repository.createFolder(event.name, event.parentId) },
                "Folder created",
            )

            is DocDistEvent.RenameFolder -> mutate(
                { repository.renameFolder(event.folderId, event.name) },
                "Folder renamed",
            )

            is DocDistEvent.DeleteFolder -> mutate(
                { repository.deleteFolder(event.folderId) },
                "Folder deleted",
            ) {
                // Standing inside a folder that no longer exists lists nothing
                // and offers no way out but the breadcrumb, so step up first.
                if (currentState.currentFolderId == event.folderId) {
                    val parent = currentState.currentFolder?.parentId
                    setState { copy(currentFolderId = parent) }
                }
            }

            is DocDistEvent.Search -> {
                setState { copy(search = event.text) }
                debounced(::loadLibrary)
            }

            is DocDistEvent.FilterByDate -> {
                setState { copy(dateFilter = event.isoDate) }
                loadLibrary()
            }

            is DocDistEvent.SortBy -> {
                setState { copy(sort = event.sort) }
                loadLibrary()
            }

            DocDistEvent.LoadMore -> loadMore()

            is DocDistEvent.ToggleDocument -> setState {
                copy(
                    selectedDocumentIds = selectedDocumentIds.toggled(event.documentId),
                )
            }

            DocDistEvent.ClearSelection -> setState { copy(selectedDocumentIds = emptySet()) }

            is DocDistEvent.SelectAll -> setState {
                copy(
                    selectedDocumentIds =
                    if (event.selected) documents.map { it.id }.toSet() else emptySet(),
                )
            }

            is DocDistEvent.DeleteDocument -> mutate(
                { repository.deleteDocument(event.documentId) },
                "Document deleted",
            )

            is DocDistEvent.MoveSelection -> {
                val ids = currentState.selectedDocumentIds.toList()
                if (ids.isEmpty()) return
                mutate({ repository.moveDocuments(ids, event.folderId) }, "Moved ${ids.size}") {
                    setState { copy(selectedDocumentIds = emptySet()) }
                }
            }

            is DocDistEvent.OpenDocument -> openUrl(event.documentId)
            is DocDistEvent.DownloadDocument -> openUrl(event.documentId)

            // -- composer --------------------------------------------------

            DocDistEvent.Compose -> openComposer()
            DocDistEvent.CloseComposer -> setState { copy(composer = ComposerState()) }
            is DocDistEvent.ComposeSubject -> composer { copy(subject = event.text) }
            is DocDistEvent.ComposeBody -> composer { copy(bodyHtml = event.html) }
            is DocDistEvent.ComposeRecipientDraft ->
                composer { copy(recipientDraft = event.text) }

            DocDistEvent.CommitRecipientDraft -> commitRecipients()
            is DocDistEvent.RemoveRecipient -> composer {
                copy(to = to.filterNot { it.email.equals(event.email, ignoreCase = true) })
            }

            is DocDistEvent.AddList -> addList(event.listId)
            is DocDistEvent.ApplyTemplate -> applyTemplate(event.templateId)
            is DocDistEvent.ToggleWatermark -> composer {
                copy(watermarked = watermarked.toggled(event.documentId))
            }

            is DocDistEvent.SetWatermark -> composer { copy(watermark = event.style) }
            is DocDistEvent.RemoveAttachment -> composer {
                copy(
                    attachments = attachments.filterNot { it.id == event.documentId },
                    watermarked = watermarked - event.documentId,
                )
            }

            DocDistEvent.Send -> send()

            // -- history ---------------------------------------------------

            is DocDistEvent.SearchHistory -> {
                setState { copy(historySearch = event.text) }
                debounced(::loadHistory)
            }

            is DocDistEvent.ExpandDistribution -> {
                setState { copy(expandedDistributionId = event.distributionId) }
                event.distributionId?.let(::refreshOpenStatus)
            }

            is DocDistEvent.DuplicateDistribution -> duplicate(event.distributionId)

            // -- lists, contacts, templates --------------------------------

            is DocDistEvent.SaveList -> saveList(event.list)
            is DocDistEvent.DeleteList -> mutate(
                { repository.deleteList(event.listId) },
                "List deleted",
            )

            is DocDistEvent.SaveContact -> mutate(
                { repository.saveContact(event.contact) },
                "Contact saved",
            )

            is DocDistEvent.DeleteContact -> mutate(
                { repository.deleteContact(event.email) },
                "Contact removed",
            )

            is DocDistEvent.SaveTemplate -> mutate(
                { repository.saveTemplate(event.template) },
                "Template saved",
            )

            is DocDistEvent.DeleteTemplate -> mutate(
                { repository.deleteTemplate(event.templateId) },
                "Template deleted",
            )
        }
    }

    // -- loading -----------------------------------------------------------

    private fun load(destination: DocDistDestination) {
        when (destination) {
            DocDistDestination.Library -> loadLibrary()
            DocDistDestination.History -> loadHistory()
            DocDistDestination.Lists -> loadLists()
            DocDistDestination.AddressBook -> loadContacts()
            DocDistDestination.Templates -> loadTemplates()
        }
    }

    /**
     * Folders and the first page of documents, together.
     *
     * Cancels whatever was in flight first: typing in the search box fires one
     * of these per keystroke, and without cancellation an early reply can land
     * after a late one and repopulate the listing with a stale query's rows.
     * The web guards the same race with a request-id counter.
     */
    private fun loadLibrary() {
        loadJob?.cancel()
        setState { copy(loading = true, error = null) }
        loadJob = launch {
            val folders = repository.folders()
            val page = repository.documents(
                LibraryQuery(
                    folderId = currentState.currentFolderId,
                    search = currentState.search,
                    documentDate = currentState.dateFilter,
                    sort = currentState.sort,
                    page = 0,
                ),
            )
            setState {
                copy(
                    loading = false,
                    // A folder call that failed keeps the tree that was there:
                    // emptying it would collapse the breadcrumb the user is
                    // standing in and make the failure look like an empty
                    // library rather than a failed refresh.
                    folders = folders.orKeep(this.folders),
                    error = (page as? ZillitResult.Failure)?.error?.userMessage,
                )
            }
            (page as? ZillitResult.Success)?.let { applyPage(it.data, append = false) }
        }
    }

    private fun loadMore() {
        val state = currentState
        if (state.loadingMore || state.loading || !state.hasMore) return
        setState { copy(loadingMore = true) }
        launch {
            // Loaded rows are always whole pages — the last, partial page sets
            // `hasMore` false so this never fires past it — so the next page
            // index is simply the count over the page size.
            val next = state.documents.size / LibraryQuery.PAGE_LIMIT
            val page = repository.documents(
                LibraryQuery(
                    folderId = state.currentFolderId,
                    search = state.search,
                    documentDate = state.dateFilter,
                    sort = state.sort,
                    page = next,
                ),
            )
            setState { copy(loadingMore = false) }
            when (page) {
                is ZillitResult.Success -> applyPage(page.data, append = true)
                is ZillitResult.Failure -> report(page.error)
            }
        }
    }

    private fun applyPage(page: LibraryPage, append: Boolean) {
        setState {
            val rows = if (append) documents + page.documents else page.documents
            copy(
                documents = rows,
                totalDocuments = page.total,
                dateCounts = page.dateCounts,
                groups = if (sort.groupsByDate) {
                    LibraryGrouping.group(
                        documents = rows,
                        counts = page.dateCounts,
                        today = today(),
                        ascending = sort == LibrarySort.DateAsc,
                    )
                } else {
                    emptyList()
                },
            )
        }
    }

    /**
     * Newest first, sorted here rather than trusted from the server.
     *
     * `/distributions` does not reliably return in date order — a send made
     * minutes ago came back below rows from a fortnight earlier (verified live
     * 2026-08-11). One page of fifty is cheap to sort, and a History whose top
     * row is not the last thing you sent is a History nobody believes.
     *
     * Rows with no timestamp sort last rather than first: an absent stamp is
     * unknown, and floating it to the top puts the least informative row where
     * the most recent one belongs.
     */
    private fun loadHistory() = fetch(
        { repository.history(page = 0, search = currentState.historySearch) },
    ) { rows -> copy(history = rows.sortedByDescending { it.sentAt ?: Long.MIN_VALUE }) }

    /**
     * Runs [block] once the user stops typing.
     *
     * Every keystroke in a search box otherwise fires its own request: typing
     * "Desktop client test" sent nineteen, each cancelling the last. The
     * cancellation kept the *results* correct — a stale reply cannot land after
     * a fresh one — but the server still saw all nineteen. The web debounces
     * the same 300ms.
     */
    private fun debounced(block: () -> Unit) {
        searchJob?.cancel()
        searchJob = launch {
            delay(SEARCH_DEBOUNCE_MS)
            block()
        }
    }

    private fun loadLists() = fetch({ repository.lists() }) { copy(lists = it) }

    private fun loadContacts() = fetch({ repository.contacts() }) { copy(contacts = it) }

    private fun loadTemplates() = fetch({ repository.templates() }) { copy(templates = it) }

    private fun <T> fetch(
        block: suspend () -> ZillitResult<T>,
        apply: DocDistUiState.(T) -> DocDistUiState,
    ) {
        loadJob?.cancel()
        setState { copy(loading = true, error = null) }
        loadJob = launch {
            when (val result = block()) {
                is ZillitResult.Success -> setState { apply(result.data).copy(loading = false) }
                is ZillitResult.Failure -> setState {
                    copy(loading = false, error = result.error.userMessage)
                }
            }
        }
    }

    // -- mutations ---------------------------------------------------------

    /**
     * Runs a write, then reloads the page it changed.
     *
     * Reloading rather than patching the list in place: the server assigns
     * ordering, date buckets and per-day counts, and a locally-patched row is
     * one that disagrees with all three until the next refresh.
     */
    private fun mutate(
        block: suspend () -> ZillitResult<Unit>,
        success: String,
        onDone: () -> Unit = {},
    ) {
        launch {
            when (val result = block()) {
                is ZillitResult.Success -> {
                    onDone()
                    setState { copy(notice = success) }
                    load(currentState.destination)
                }

                is ZillitResult.Failure -> report(result.error)
            }
        }
    }

    private fun saveList(list: DistributionList) = mutate(
        {
            if (list.id.isBlank()) {
                repository.createList(list.name, list.recipients)
            } else {
                repository.updateList(list.id, list.name, list.recipients)
            }
        },
        if (list.id.isBlank()) "List created" else "List updated",
    )

    // -- composer ----------------------------------------------------------

    /**
     * Opens the composer over whatever the library has selected.
     *
     * Every watermark-capable attachment starts stamped. That is the web's
     * default and it is not cosmetic: a production issuing an unstamped script
     * because the sender did not notice a checkbox is the failure this tool
     * exists to prevent.
     */
    private fun openComposer() {
        val chosen = currentState.selectedDocuments
        setState {
            copy(
                composer = ComposerState(
                    open = true,
                    attachments = chosen,
                    watermarked = chosen.filter { it.isWatermarkable }.map { it.id }.toSet(),
                ),
            )
        }
        // The composer needs both to be useful, and neither is loaded unless
        // the user has already visited those tabs.
        if (currentState.lists.isEmpty()) launch { primeLists() }
        if (currentState.templates.isEmpty()) launch { primeTemplates() }
    }

    private suspend fun primeLists() {
        (repository.lists() as? ZillitResult.Success)?.let { loaded ->
            setState { copy(lists = loaded.data) }
        }
    }

    private suspend fun primeTemplates() {
        (repository.templates() as? ZillitResult.Success)?.let { loaded ->
            setState { copy(templates = loaded.data) }
        }
    }

    private fun commitRecipients() = composer {
        val parsed = parseRecipients(recipientDraft)
        if (parsed.isEmpty()) return@composer this
        copy(to = (to + parsed).distinctBy { it.email.lowercase() }, recipientDraft = "")
    }

    private fun addList(listId: String) {
        val list = currentState.lists.firstOrNull { it.id == listId } ?: return
        composer {
            copy(
                to = (to + list.recipients).distinctBy { it.email.lowercase() },
                listId = listId,
            )
        }
    }

    private fun applyTemplate(templateId: String) {
        val template = currentState.templates.firstOrNull { it.id == templateId } ?: return
        composer { copy(subject = template.subject, bodyHtml = template.bodyHtml) }
    }

    private fun send() {
        val state = currentState
        val draft = NewDistribution(
            subject = state.composer.subject,
            bodyHtml = state.composer.bodyHtml,
            to = state.composer.to,
            cc = state.composer.cc,
            bcc = state.composer.bcc,
            folderId = state.currentFolderId,
            listId = state.composer.listId,
            attachmentIds = state.composer.attachments.map { it.id },
            watermarks = state.composer.effectiveWatermarks,
            replyTo = state.viewer.userEmail.takeIf { it.isNotBlank() },
        )
        draft.validationError()?.let { reason ->
            sendEffect(DocDistEffect.Failed(reason))
            return
        }
        // Rights are checked here as well as by the server: the button is
        // hidden without posting rights, but a keyboard shortcut or a
        // duplicated send would otherwise reach this with no gate at all.
        if (!state.viewer.canPost) {
            sendEffect(DocDistEffect.Failed("You do not have permission to send from this tool."))
            return
        }
        composer { copy(sending = true) }
        launch {
            when (val result = repository.send(draft)) {
                is ZillitResult.Success -> {
                    setState {
                        copy(
                            composer = ComposerState(),
                            selectedDocumentIds = emptySet(),
                            notice = "Sent to ${draft.to.size} recipient" +
                                if (draft.to.size == 1) "" else "s",
                        )
                    }
                }

                is ZillitResult.Failure -> {
                    composer { copy(sending = false) }
                    report(result.error)
                }
            }
        }
    }

    private fun duplicate(distributionId: String) {
        val past = currentState.history.firstOrNull { it.id == distributionId } ?: return
        setState {
            copy(
                destination = DocDistDestination.Library,
                composer = ComposerState(
                    open = true,
                    subject = past.subject,
                    to = past.recipients.map { it.recipient },
                ),
            )
        }
    }

    /**
     * Re-asks the email service whether one send's copies have been opened.
     *
     * Only for the expanded row, and only on expand: open status is a separate
     * service and asking for every row in History would be one call per send on
     * every page load, for a figure nobody is looking at.
     */
    private fun refreshOpenStatus(distributionId: String) {
        val row = currentState.history.firstOrNull { it.id == distributionId } ?: return
        val ids = row.recipients.mapNotNull { it.uniqueId }
        if (ids.isEmpty()) return
        launch {
            val statuses = repository.openStatus(ids).getOrNull() ?: return@launch
            setState {
                copy(
                    history = history.map { entry ->
                        if (entry.id != distributionId) {
                            entry
                        } else {
                            entry.copy(
                                recipients = entry.recipients.map { delivery ->
                                    val fresh = delivery.uniqueId?.let(statuses::get)
                                        ?: return@map delivery
                                    // The recipient is kept from the row we
                                    // already have — the status service answers
                                    // with ids, not people.
                                    delivery.copy(
                                        state = fresh.state,
                                        openedAt = fresh.openedAt,
                                        openCount = fresh.openCount,
                                    )
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    // -- shared ------------------------------------------------------------

    private fun openUrl(documentId: String) {
        if (!currentState.viewer.canDownload) {
            sendEffect(DocDistEffect.Failed("You do not have download rights for this tool."))
            return
        }
        launch {
            when (val url = repository.downloadUrl(documentId)) {
                is ZillitResult.Success -> sendEffect(DocDistEffect.OpenUrl(url.data))
                is ZillitResult.Failure -> report(url.error)
            }
        }
    }

    private fun composer(reducer: ComposerState.() -> ComposerState) =
        setState { copy(composer = composer.reducer()) }

    private fun report(error: ZillitError) {
        sendEffect(DocDistEffect.Failed(error.userMessage))
    }
}

/** What the web waits, and long enough that a fast typist sends one request. */
private const val SEARCH_DEBOUNCE_MS = 300L

/** Adds or removes, whichever the current membership implies. */
private fun <T> Set<T>.toggled(value: T): Set<T> =
    if (value in this) this - value else this + value

/** Keeps the last good value when a refresh fails, rather than emptying it. */
private fun <T> ZillitResult<T>.orKeep(previous: T): T =
    (this as? ZillitResult.Success)?.data ?: previous

/** Contacts and lists are addressed by email everywhere; this is the one comparison. */
internal fun Recipient.sameAs(other: Contact): Boolean =
    email.equals(other.email, ignoreCase = true)

/** Templates carry no id until saved; blank means "new". */
internal val EmailTemplate.isNew: Boolean get() = id.isBlank()
