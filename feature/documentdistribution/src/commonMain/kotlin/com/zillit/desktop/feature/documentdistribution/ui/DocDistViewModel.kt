package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.DistributionSender
import com.zillit.desktop.feature.documentdistribution.domain.PublishDraft
import com.zillit.desktop.feature.documentdistribution.domain.PublishTarget
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRefresh
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
@Suppress("TooManyFunctions", "LargeClass") // One handler per user action; see detekt.yml.
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
    /**
     * Where "ask an admin for this right" goes.
     *
     * Null in tests and in any host with no chat to send on; the tool then
     * simply says what is missing, as it did before.
     */
    private val rights: RightsRequestBus? = null,
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
        listenOnce()
    }

    /**
     * Reloads a destination when the socket says another client changed its
     * listing, and only while that destination is on screen — the web
     * screens' own silent refetches, ported as a targeted reload (opening
     * a destination loads it anyway, so an event missed while elsewhere is
     * corrected on arrival). Guarded separately from [started]: a project
     * switch resets [started] but must not stack a second collector.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.collect { kind ->
                val here = currentState.destination
                if (kind.destination == here && !currentState.viewer.isBlocked) load(here)
            }
        }
    }

    private var listening = false

    private val DocDistRefresh.destination: DocDistDestination
        get() = when (this) {
            DocDistRefresh.Library -> DocDistDestination.Library
            DocDistRefresh.History -> DocDistDestination.History
            DocDistRefresh.Lists -> DocDistDestination.Lists
            DocDistRefresh.Templates -> DocDistDestination.Templates
        }

    /** Re-reads rights when the open production changes. */
    fun onProjectChanged() {
        started = false
        setState { DocDistUiState(viewer = viewer()) }
        start()
    }

    /**
     * Swaps in the real rights once the tool grid has answered.
     *
     * `projectId` flips the moment a production is chosen, but the rights that
     * gate this screen arrive with the Home load a beat later — so the viewer
     * resolved at open is the "not yet known" one, and nothing used to replace
     * it. Seen live 2026-08-27: Document Distribution offered no publish
     * destination at all on a production with 42 tools switched on. Only the
     * viewer changes here; the open page and its data are already right.
     */
    fun onRightsChanged() {
        // Read outside the state lambda: inside it, `viewer` is the
        // state's own viewer property rather than the supplier.
        val resolved = viewer()
        setState { copy(viewer = resolved) }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One branch per user action.
    override fun onEvent(event: DocDistEvent) {
        when (event) {
            is DocDistEvent.RequestRights -> askForRights(event.kind)
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

            is DocDistEvent.ToggleFolder -> setState {
                copy(selectedFolderIds = selectedFolderIds.toggled(event.folderId))
            }

            DocDistEvent.OpenPublish -> openPublish()

            DocDistEvent.ClosePublish -> setState { copy(publish = null) }

            is DocDistEvent.ChoosePublishTarget -> choosePublishTarget(event.category)

            is DocDistEvent.EditPublishDraft -> setState {
                copy(publish = publish?.copy(draft = event.draft))
            }

            is DocDistEvent.ToggleReplaceTarget -> setState {
                val open = publish ?: return@setState this
                val chosen = open.draft.replaceChatIds.toSet().toggled(event.chatId)
                copy(publish = open.copy(draft = open.draft.copy(replaceChatIds = chosen.toList())))
            }

            DocDistEvent.ConfirmPublish -> confirmPublish()

            DocDistEvent.OpenMove ->
                setState { copy(moveTarget = MoveTargetState(destinationId = currentFolderId)) }

            DocDistEvent.CloseMove -> setState { copy(moveTarget = null) }

            is DocDistEvent.ChooseMoveDestination -> setState {
                copy(moveTarget = moveTarget?.copy(destinationId = event.folderId))
            }

            DocDistEvent.ConfirmMove ->
                moveSelection(currentState.moveTarget?.destinationId, closesDialog = true)

            is DocDistEvent.MoveSelection -> moveSelection(event.folderId, closesDialog = false)

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

            is DocDistEvent.ToggleHistorySender -> reloadHistory { togglingSender(event.senderId) }
            DocDistEvent.ClearHistorySenders -> reloadHistory { clearingSenders() }
            is DocDistEvent.SearchHistorySenders -> setState { copy(historySenderQuery = event.text) }
            is DocDistEvent.HistorySenderMenu -> setState { copy(historySenderMenuOpen = event.open) }
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
    private fun loadHistory() {
        val ids = currentState.historySenderIds
        fetch({ repository.history(page = 0, search = currentState.historySearch, senderIds = ids) }) { rows ->
            historyLoaded(rows, ids)
        }
        senders.askOnce { fetched -> setState { copy(historySenders = mergedSenders(fetched, history)) } }
    }

    private fun reloadHistory(change: DocDistUiState.() -> DocDistUiState) {
        setState(change)
        loadHistory()
    }

    private val senders = HistorySenderSource(repository, ::launch)

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
                    copy(loading = false, error = result.error.localised())
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
    /**
     * Moves the ticked folders and documents into one destination.
     *
     * Folders go first: reparenting a folder rewrites the tree the documents
     * are being placed into, and doing it the other way round can land a
     * document in a folder that is about to move out from under it.
     *
     * A folder cannot be dropped into itself or its own subtree — see
     * [DocDistUiState.moveDestinations], which never offers those.
     */
    // -- publishing --------------------------------------------------------

    private fun openPublish() {
        if (refusesWrite()) return
        val documents = currentState.selectedDocumentIds.toList()
        if (documents.isEmpty()) {
            report(ZillitError.Unknown("Choose at least one document to publish."))
            return
        }
        setState { copy(publish = PublishState(draft = PublishDraft(documentIds = documents))) }
    }

    /**
     * Switching destination clears the fields the previous one collected.
     *
     * They are not interchangeable — a scene number typed for Pages is not a
     * D.O.D name — and carrying them across is how a stale value gets sent
     * to an endpoint that reads a different key.
     */
    private fun choosePublishTarget(category: String) {
        val target = PublishTarget.of(category) ?: return
        val open = currentState.publish ?: return
        setState {
            copy(
                publish = open.copy(
                    target = target,
                    draft = PublishDraft(documentIds = open.draft.documentIds),
                    alreadyPublished = emptyList(),
                    loadingPublished = target.republishable,
                ),
            )
        }
        if (!target.republishable) return
        launch {
            val published = repository.publishedFiles(category)
            setState {
                copy(
                    publish = publish?.copy(
                        loadingPublished = false,
                        alreadyPublished = published.getOrNull().orEmpty(),
                    ),
                )
            }
        }
    }

    private fun confirmPublish() {
        val open = currentState.publish ?: return
        val target = open.target ?: return
        val problem = open.problem(currentState.viewer.isTelevision)
        if (problem != null) {
            report(ZillitError.Unknown(problem))
            return
        }
        val draft = if (open.offersMode) open.draft else open.draft.copy(replaceChatIds = emptyList())
        setState { copy(publish = publish?.copy(saving = true)) }
        launch {
            when (val result = repository.publish(target.category, draft)) {
                is ZillitResult.Success -> {
                    val count = draft.documentIds.size
                    setState {
                        copy(
                            publish = null,
                            selectedDocumentIds = emptySet(),
                            notice = "Published $count file" +
                                (if (count == 1) "" else "s") + " to ${target.label}",
                        )
                    }
                    load(currentState.destination)
                }

                is ZillitResult.Failure -> {
                    setState { copy(publish = publish?.copy(saving = false)) }
                    report(result.error)
                }
            }
        }
    }

    private fun moveSelection(folderId: String?, closesDialog: Boolean) {
        val folders = currentState.selectedFolderIds.toList()
        val documents = currentState.selectedDocumentIds.toList()
        val moved = folders.size + documents.size
        if (moved == 0) return
        if (refusesWrite()) return
        if (folders.any { it == folderId }) {
            report(ZillitError.Unknown("A folder cannot be moved into itself."))
            return
        }
        // The dialog bars the root row when files are selected; this is the
        // backstop behind it, because the destination also arrives from paths
        // the dialog does not own.
        if (documents.isNotEmpty() && folderId == null) {
            report(ZillitError.Unknown("Files must be moved into a folder, not the root."))
            return
        }
        if (closesDialog) setState { copy(moveTarget = moveTarget?.copy(saving = true)) }
        launch {
            val failure = moveParts(folders, documents, folderId)
            if (failure != null) {
                setState { copy(moveTarget = moveTarget?.copy(saving = false)) }
                report(failure)
                return@launch
            }
            setState {
                copy(
                    selectedFolderIds = emptySet(),
                    selectedDocumentIds = emptySet(),
                    moveTarget = null,
                    notice = "Moved $moved item" + if (moved == 1) "" else "s",
                )
            }
            load(currentState.destination)
        }
    }

    private suspend fun moveParts(
        folders: List<String>,
        documents: List<String>,
        folderId: String?,
    ): ZillitError? {
        if (folders.isNotEmpty()) {
            val result = repository.moveFolders(folders, folderId)
            if (result is ZillitResult.Failure) return result.error
        }
        if (documents.isNotEmpty()) {
            val result = repository.moveDocuments(documents, folderId)
            if (result is ZillitResult.Failure) return result.error
        }
        return null
    }

    /**
     * Refuses a write this person has no posting rights for, and offers the ask.
     *
     * The buttons all stay on screen and gate their own presses, so most
     * refusals never get here. This is the second layer the web keeps
     * deliberately (`requirePost()` in every handler): a stale window, a
     * socket-driven reload or a dialog opened before a rights change can still
     * reach a handler, and the answer should be the same offer either way.
     */
    private fun refusesWrite(): Boolean {
        if (currentState.viewer.canPost) return false
        askForRights(RightsKind.Post)
        return true
    }

    private fun mutate(
        block: suspend () -> ZillitResult<Unit>,
        success: String,
        onDone: () -> Unit = {},
    ) {
        if (refusesWrite()) return
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
        // Checked here as well as on the button, which gates its own press:
        // the composer can be left open across a rights change, and a
        // keyboard shortcut reaches this with no button involved at all.
        if (!state.viewer.canPost) {
            askForRights(RightsKind.Post)
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

    /**
     * Raises the request the frame answers with its admin picker.
     *
     * Says so on screen too: the dialog opens over the tool window, and a
     * click that produced only a dialog somewhere else would read as the
     * button having done nothing.
     */
    private fun askForRights(kind: RightsKind) {
        val asked = rights != null
        if (asked) rights.ask(MODULE_LABEL, kind)
        sendEffect(
            DocDistEffect.Failed(
                if (asked) {
                    "You do not have ${kind.verb} rights on $MODULE_LABEL — asking an administrator."
                } else {
                    "You do not have ${kind.verb} rights on $MODULE_LABEL."
                },
            ),
        )
    }

    private fun openUrl(documentId: String) {
        if (!currentState.viewer.canDownload) {
            askForRights(RightsKind.Download)
            return
        }
        // Found on the rows in hand: where a document's bytes live comes back
        // with the listing, and the server has no route that will tell us
        // again.
        val document = currentState.documents.firstOrNull { it.id == documentId }
            ?: return report(ZillitError.Unknown("That file is no longer in this folder."))

        launch {
            when (val url = repository.documentUrl(document)) {
                is ZillitResult.Success -> sendEffect(DocDistEffect.OpenUrl(url.data))
                is ZillitResult.Failure -> report(url.error)
            }
        }
    }

    private fun composer(reducer: ComposerState.() -> ComposerState) =
        setState { copy(composer = composer.reducer()) }

    private fun report(error: ZillitError) {
        sendEffect(DocDistEffect.Failed(error.localised()))
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

/** The menu's senders: the endpoint's list, plus anyone a loaded row names that it did not, by id. */
internal fun mergedSenders(known: List<DistributionSender>, rows: List<Distribution>): List<DistributionSender> {
    val byId = linkedMapOf<String, DistributionSender>()
    known.forEach { if (it.id.isNotBlank()) byId[it.id] = it }
    rows.forEach { row ->
        if (row.senderId.isNotBlank() && row.senderId !in byId) {
            byId[row.senderId] = DistributionSender(row.senderId, row.sentByName)
        }
    }
    return byId.values.sortedBy { it.name.lowercase() }
}

private fun DocDistUiState.togglingSender(id: String): DocDistUiState =
    copy(historySenderIds = if (id in historySenderIds) historySenderIds - id else historySenderIds + id)

private fun DocDistUiState.clearingSenders(): DocDistUiState =
    copy(historySenderIds = emptySet(), historySenderQuery = "")

/**
 * The filter is applied here as well as pushed as `sent_by`: a backend that
 * ignores the param still answers, and the rows must not show senders the
 * menu says are off.
 */
private fun DocDistUiState.historyLoaded(rows: List<Distribution>, ids: Set<String>): DocDistUiState {
    val kept = if (ids.isEmpty()) rows else rows.filter { it.senderId in ids }
    return copy(
        history = kept.sortedByDescending { it.sentAt ?: Long.MIN_VALUE },
        historySenders = mergedSenders(historySenders, rows),
    )
}

/** What the reader calls this tool; it reaches an admin's chat verbatim. */
private const val MODULE_LABEL = "Document Distribution"
