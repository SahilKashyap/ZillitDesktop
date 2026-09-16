package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.feature.documentdistribution.domain.DocDistBadges
import com.zillit.desktop.feature.documentdistribution.domain.DocDistUnread
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.feature.documentdistribution.domain.DocDistHost
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRefresh
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRepository
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.LibraryGrouping
import com.zillit.desktop.feature.documentdistribution.domain.LibraryPage
import com.zillit.desktop.feature.documentdistribution.domain.LibraryQuery
import com.zillit.desktop.feature.documentdistribution.domain.LibrarySort
import kotlinx.coroutines.CoroutineScope
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
 * behind it: every mutation ends by reloading the page it changed.
 *
 * ## The viewer is resolved before anything else
 *
 * Rights decide which tabs exist and whether the tool renders at all, so
 * [start] reads them first. The view model is built once for the whole app —
 * before any production is open — so the viewer is a lambda resolved in
 * [start], never a constructor snapshot.
 *
 * ## Six sections, one state
 *
 * The handlers live in `*Section` classes over [VmScope]; this class routes
 * events to them and owns the loads. See [VmScope] for why.
 */
@Suppress("TooManyFunctions", "LargeClass") // One route per user action; see detekt.yml.
class DocDistViewModel(
    private val repository: DocDistRepository,
    /** Who is looking, read at start rather than at construction. See the class doc. */
    private val viewer: () -> DocDistViewer,
    /**
     * Today, in the machine's zone. Injected because "Today ·" is a wall-clock
     * question and this is common code with no clock in it.
     */
    private val today: () -> LocalDate,
    /** Where "ask an admin for this right" goes; null in tests. */
    private val rights: RightsRequestBus? = null,
    /** The machine: file dialogs, PDF rendering, Downloads, the clipboard. */
    private val host: DocDistHost = DocDistHost.None,
    /** The ledger's rows for this tool, and its reads. */
    private val badges: DocDistBadges = DocDistBadges.None,
) : ZillitViewModel<DocDistUiState, DocDistEvent, DocDistEffect>(
    DocDistUiState(viewer = viewer()),
) {

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var started = false
    private var listening = false

    private val scope: VmScope = Scope()
    private val library = LibrarySection(scope)
    private val composer = ComposerSection(scope, library)
    private val history = HistorySection(scope, library)
    private val lists = ListsSection(scope, library)
    private val contacts = ContactsSection(scope, library)
    private val templates = TemplatesSection(scope)
    private val watermark = WatermarkSection(scope, library, composer)

    /**
     * Resolves who this is, then opens their landing page. Idempotent because
     * a torn-off window and the tab in the frame are the same view model.
     */
    fun start() {
        if (started) return
        started = true
        launch {
            badges.leaves.collect { leaves ->
                setState { copy(unread = DocDistUnread(leaves)) }
                // A row landing on the open section is read as it lands.
                readSection(currentState.destination)
            }
        }
        val identity = viewer()
        setState { copy(viewer = identity, today = today(), destination = DocDistDestination.landing(identity)) }
        if (!identity.isBlocked) {
            load(currentState.destination)
            loadWatermarkDefaults()
        }
        listenOnce()
    }

    /**
     * The shared stamp appearance, once per production open. A failure keeps
     * the built-in values, which are what the server answers for a project
     * that has never saved — so nothing is reported.
     */
    private fun loadWatermarkDefaults() {
        launch {
            (repository.watermarkSettings() as? ZillitResult.Success)?.let { settings ->
                setState { copy(watermarkDefaults = settings.data) }
            }
        }
    }

    /**
     * Reloads a destination when the socket says another client changed its
     * listing, and only while that destination is on screen.
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
        launch {
            // Only the cache: an open wizard keeps the draft its user is editing.
            repository.watermarkSettingsUpdates.collect { settings -> setState { copy(watermarkDefaults = settings) } }
        }
    }

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
        history.reset()
        setState { DocDistUiState(viewer = viewer()) }
        start()
    }

    /**
     * Swaps in the real rights once the tool grid has answered — they arrive
     * with the Home load a beat after the production is chosen.
     */
    fun onRightsChanged() {
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
            DocDistEvent.DismissInfoBanner -> setState { copy(infoBannerDismissed = true) }
            is DocDistEvent.Open -> {
                setState { copy(destination = event.destination, error = null) }
                load(event.destination)
                readSection(event.destination)
            }
            else -> route(event)
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One branch per user action.
    private fun route(event: DocDistEvent) {
        when (event) {
            // -- library ---------------------------------------------------
            is DocDistEvent.OpenFolder -> openFolder(event.folderId)
            DocDistEvent.GoUp -> openFolder(currentState.currentFolder?.parentId)
            DocDistEvent.OpenNewFolder -> library.openNewFolder()
            is DocDistEvent.OpenEditFolder -> library.openEditFolder(event.folderId)
            is DocDistEvent.EditFolderName -> library.editFolder { copy(name = event.text) }
            is DocDistEvent.EditFolderDescription -> library.editFolder { copy(description = event.text) }
            is DocDistEvent.EditFolderDate -> library.editFolder { copy(folderDate = event.isoDate) }
            DocDistEvent.CloseFolderEditor -> setState { copy(folderEditor = null) }
            DocDistEvent.SaveFolder -> library.saveFolder()
            is DocDistEvent.ConfirmDeleteFolder -> library.confirmDeleteFolder(event.folderId)
            is DocDistEvent.DeleteFolder -> library.deleteFolder(event.folderId)
            is DocDistEvent.Search -> {
                setState { copy(search = event.text) }
                debounced(::loadLibrary)
            }
            is DocDistEvent.FilterByDate -> {
                setState { copy(dateFilter = event.isoDate?.takeIf { it.isNotBlank() }) }
                loadLibrary()
            }
            DocDistEvent.ClearFilters -> {
                setState { copy(search = "", dateFilter = null) }
                loadLibrary()
            }
            is DocDistEvent.SortBy -> {
                setState { copy(sort = event.sort) }
                loadLibrary()
            }
            is DocDistEvent.SetView -> setState { copy(view = event.view) }
            DocDistEvent.LoadMore -> loadMore()
            is DocDistEvent.ToggleDocument -> setState {
                copy(selectedDocumentIds = selectedDocumentIds.toggled(event.documentId))
            }
            is DocDistEvent.ToggleFolder -> setState { copy(
                selectedFolderIds = selectedFolderIds.toggled(event.folderId),
            ) }
            DocDistEvent.ClearSelection -> setState { copy(
                selectedDocumentIds = emptySet(),
                selectedFolderIds = emptySet(),
            ) }
            is DocDistEvent.SelectAll -> library.selectAll(event.selected)
            is DocDistEvent.ConfirmDeleteDocument -> library.confirmDeleteDocument(event.documentId)
            is DocDistEvent.DeleteDocument -> library.deleteDocument(event.documentId)
            DocDistEvent.ConfirmDeleteSelection -> library.confirmDeleteSelection()
            DocDistEvent.DeleteSelection -> library.deleteSelection()
            DocDistEvent.PickAndUpload -> library.pickAndUpload()
            is DocDistEvent.DropFiles ->
                if (currentState.composer.open) composer.attachDropped(event.files) else library.dropFiles(event.files)
            is DocDistEvent.DragHover -> setState { copy(dragHover = event.hovering && currentFolder != null) }
            is DocDistEvent.OpenDocument -> {
                // The preview is the file's read (`Library.jsx:282-290`).
                if (currentState.unread.file(event.documentId) > 0) badges.readFile(event.documentId)
                library.openPreview(event.documentId)
            }
            DocDistEvent.ClosePreview -> setState { copy(
                preview = null,
                composer = composer.copy(watermarkPreview = null),
            ) }
            is DocDistEvent.DownloadDocument -> library.download(event.documentId)
            DocDistEvent.RemoveMissingRecord -> library.removeMissingRecord()
            // publish
            DocDistEvent.OpenPublishSelection -> library.publishSelection()
            is DocDistEvent.PublishDocument -> library.publishDocument(event.documentId)
            is DocDistEvent.PublishFolder -> library.publishFolder(event.folderId)
            DocDistEvent.ClosePublish -> setState { copy(publish = null) }
            is DocDistEvent.ChoosePublishTarget -> library.choosePublishTarget(event.category)
            is DocDistEvent.EditPublishDraft -> setState { copy(publish = publish?.copy(draft = event.draft)) }
            is DocDistEvent.ToggleReplaceTarget -> setState {
                val open = publish ?: return@setState this
                val chosen = open.draft.replaceChatIds.toSet().toggled(event.chatId)
                copy(publish = open.copy(draft = open.draft.copy(replaceChatIds = chosen.toList())))
            }
            DocDistEvent.ConfirmPublish -> library.confirmPublish()
            // move
            DocDistEvent.OpenMove -> library.openMove()
            DocDistEvent.CloseMove -> setState { copy(moveTarget = null) }
            is DocDistEvent.ChooseMoveDestination -> setState {
                copy(moveTarget = moveTarget?.copy(destinationId = event.folderId))
            }
            DocDistEvent.ConfirmMove -> library.moveSelection(
                currentState.moveTarget?.destinationId,
                closesDialog = true,
            )
            is DocDistEvent.MoveSelection -> library.moveSelection(event.folderId, closesDialog = false)
            else -> routeMore(event)
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One branch per user action.
    private fun routeMore(event: DocDistEvent) {
        when (event) {
            // -- watermark + picker ----------------------------------------
            is DocDistEvent.OpenWatermarkDownload -> watermark.openDownload(event.documentId)
            DocDistEvent.CloseWatermarkDownload -> setState { copy(watermarkDownload = null) }
            is DocDistEvent.EditWatermarkLine1 -> watermark.editDownload { copy(line1 = event.text) }
            is DocDistEvent.EditWatermarkLine2 -> watermark.editDownload { copy(line2 = event.text) }
            is DocDistEvent.EditWatermarkDownloadStyle -> watermark.editDownload { copy(style = event.style) }
            DocDistEvent.ConfirmWatermarkDownload -> watermark.confirmDownload()
            DocDistEvent.OpenWatermarkBatch -> watermark.openBatch()
            DocDistEvent.CloseWatermarkBatch -> setState { copy(watermarkBatch = null) }
            is DocDistEvent.RemoveBatchDocument -> watermark.removeBatchDocument(event.documentId)
            is DocDistEvent.EditBatchStyle -> watermark.editBatchStyle(event.style)
            is DocDistEvent.EditBatchRecipientInput -> watermark.editBatchInput(event.text)
            DocDistEvent.AddBatchRecipient -> watermark.addBatchRecipient()
            is DocDistEvent.AddBatchRecipientFromContact -> watermark.addBatchContact(event.email)
            is DocDistEvent.RemoveBatchRecipient -> watermark.removeBatchRecipient(event.email)
            is DocDistEvent.BatchListMenu -> setState { copy(
                watermarkBatch = watermarkBatch?.copy(listMenuOpen = event.open),
            ) }
            is DocDistEvent.AddBatchList -> watermark.addBatchList(event.listId)
            DocDistEvent.ConfirmWatermarkBatch -> watermark.confirmBatch()
            is DocDistEvent.OpenPicker -> watermark.openPicker(event.purpose)
            DocDistEvent.ClosePicker -> watermark.closePicker()
            is DocDistEvent.PickerFolder -> watermark.pickerFolder(event.folderId)
            is DocDistEvent.PickerSearch -> watermark.pickerSearch(event.text)
            is DocDistEvent.PickerToggle -> watermark.pickerToggle(event.documentId)
            DocDistEvent.PickerToggleAllVisible -> watermark.pickerToggleAllVisible()
            DocDistEvent.PickerClear -> watermark.pickerClear()
            DocDistEvent.PickerConfirm -> watermark.pickerConfirm()

            // -- composer ----------------------------------------------------
            DocDistEvent.Compose -> composer.composeSelection()
            DocDistEvent.ComposeBlank -> composer.composeBlank()
            is DocDistEvent.DistributeDocument -> composer.distributeDocument(event.documentId)
            is DocDistEvent.DistributeFolder -> composer.distributeFolder(event.folderId)
            is DocDistEvent.ComposeTo -> composer.composeTo(event.email)
            is DocDistEvent.ComposeWithList -> composer.composeWithList(event.listId)
            DocDistEvent.CloseComposer -> composer.close()
            is DocDistEvent.ComposeStage -> editComposer { copy(stage = event.stage) }
            is DocDistEvent.ComposeSubject -> editComposer { copy(subject = event.text) }
            is DocDistEvent.ComposeBody -> editComposer { copy(body = event.text) }
            is DocDistEvent.ComposeAddresses -> composer.addresses(event.field, event.tokens, event.input)
            is DocDistEvent.ShowCcBcc -> editComposer { copy(showCcBcc = event.shown) }
            is DocDistEvent.ComposeListMenu -> editComposer { copy(listMenuOpen = event.open) }
            is DocDistEvent.ComposeTemplateMenu -> editComposer { copy(templateMenuOpen = event.open) }
            is DocDistEvent.ComposeSignatureMenu -> editComposer { copy(signatureMenuOpen = event.open) }
            is DocDistEvent.AddList -> composer.addList(event.listId)
            is DocDistEvent.ApplyTemplate -> composer.applyTemplate(event.templateId)
            is DocDistEvent.InsertSignature -> composer.insertSignature(event.signatureId)
            DocDistEvent.SaveCurrentAsTemplate -> composer.saveCurrentAsTemplate()
            DocDistEvent.OpenListEditor -> lists.openEditor()
            is DocDistEvent.ToggleWatermark -> composer.toggleWatermark(event.documentId)
            DocDistEvent.OpenWatermarkWizard -> composer.openWizard()
            is DocDistEvent.EditWizard -> editComposer { copy(wizardDraft = event.style) }
            DocDistEvent.SaveWizard -> composer.saveWizard()
            DocDistEvent.CloseWizard -> editComposer { copy(wizardDraft = null) }
            is DocDistEvent.OpenWatermarkPreview -> composer.openWatermarkPreview(event.documentId)
            is DocDistEvent.RemoveAttachment -> composer.removeAttachment(event.documentId)
            is DocDistEvent.MoveAttachment -> composer.moveAttachment(event.documentId, event.delta)
            DocDistEvent.PickAndAttach -> composer.pickAndAttach()
            is DocDistEvent.AttachDroppedFiles -> composer.attachDropped(event.files)
            is DocDistEvent.ToggleOversizeFile -> composer.toggleOversize(event.index)
            DocDistEvent.ConfirmOversize -> composer.confirmOversize()
            DocDistEvent.CancelOversize -> editComposer { copy(oversize = null) }
            DocDistEvent.Send -> composer.send()
            else -> routeDirectories(event)
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One branch per user action.
    private fun routeDirectories(event: DocDistEvent) {
        when (event) {
            // -- history ---------------------------------------------------
            is DocDistEvent.SearchHistory -> {
                setState { copy(historySearch = event.text) }
                debounced(history::load)
            }
            is DocDistEvent.ToggleHistorySender -> history.toggleSender(event.senderId)
            DocDistEvent.ClearHistorySenders -> history.clearSenders()
            is DocDistEvent.SearchHistorySenders -> setState { copy(historySenderQuery = event.text) }
            is DocDistEvent.HistorySenderMenu -> setState { copy(historySenderMenuOpen = event.open) }
            DocDistEvent.LoadMoreHistory -> history.loadMore()
            is DocDistEvent.ExpandDistribution -> history.expand(event.distributionId)
            DocDistEvent.RefreshDistribution -> history.refreshDetail()
            is DocDistEvent.DuplicateDistribution -> composer.duplicate(event.distributionId)
            is DocDistEvent.OpenSaveRecipientsAsList -> history.openSaveAsList(event.open)
            is DocDistEvent.EditSaveListName -> history.editSaveListName(event.text)
            DocDistEvent.ConfirmSaveRecipientsAsList -> history.confirmSaveAsList()
            DocDistEvent.ExportRecipientsCsv -> history.exportRecipientsCsv()

            // -- lists -----------------------------------------------------
            is DocDistEvent.SearchLists -> setState { copy(listsSearch = event.text) }
            is DocDistEvent.NewListRow -> lists.newListRow(event.open)
            is DocDistEvent.EditNewListName -> setState { copy(newListName = event.text) }
            DocDistEvent.CreateListInline -> lists.createInline()
            is DocDistEvent.OpenList -> lists.openList(event.listId)
            DocDistEvent.CloseList -> lists.closeList()
            is DocDistEvent.EditListName -> lists.editName(event.text)
            is DocDistEvent.EditListRecipientInput -> lists.editInput(event.email, event.name, event.job)
            is DocDistEvent.PickListContact -> lists.pickContact(event.email)
            DocDistEvent.AddListRecipient -> lists.addRecipient()
            is DocDistEvent.RemoveListRecipient -> lists.removeRecipient(event.email)
            DocDistEvent.SaveList -> lists.save()
            is DocDistEvent.ConfirmRemoveList -> lists.confirmRemove(event.listId)
            is DocDistEvent.DeleteList -> lists.delete(event.listId)
            is DocDistEvent.ExportList -> lists.exportList(event.listId)
            DocDistEvent.DownloadCsvTemplate -> lists.downloadTemplate()
            DocDistEvent.PickCsv -> lists.pickCsv(forEditor = false)
            DocDistEvent.CancelCsv -> lists.cancelCsv()
            DocDistEvent.ConfirmCsv -> lists.confirmCsv()
            DocDistEvent.CloseListEditor -> lists.closeEditor()
            is DocDistEvent.EditListEditor -> lists.editEditorFields(event.name, event.description)
            is DocDistEvent.EditListEditorInput -> lists.editEditorInput(event.email, event.name, event.job)
            is DocDistEvent.PickListEditorContact -> lists.pickEditorContact(event.email)
            DocDistEvent.AddListEditorRecipient -> lists.addEditorRecipient()
            is DocDistEvent.RemoveListEditorRecipient -> lists.removeEditorRecipient(event.email)
            DocDistEvent.PickCsvForListEditor -> lists.pickCsv(forEditor = true)
            DocDistEvent.SaveListEditor -> lists.saveEditor { created ->
                if (currentState.composer.open) composer.addList(created.id)
            }

            // -- address book ----------------------------------------------
            is DocDistEvent.SearchContacts -> setState { copy(contactsSearch = event.text) }
            is DocDistEvent.SelectContact -> contacts.select(event.email)
            DocDistEvent.OpenAddContact -> contacts.openAdd()
            DocDistEvent.OpenEditContact -> contacts.openEdit()
            is DocDistEvent.EditContact -> contacts.edit(event.name, event.email, event.job, event.listIds)
            DocDistEvent.CloseContactEditor -> contacts.close()
            DocDistEvent.SaveContactEditor -> contacts.saveEditor()
            is DocDistEvent.ConfirmDeleteContact -> contacts.confirmDelete(event.email)
            is DocDistEvent.DeleteContact -> contacts.delete(event.email)
            is DocDistEvent.SaveContact -> contacts.save(event.contact)
            is DocDistEvent.AddContactToList -> contacts.addToList(event.listId)
            is DocDistEvent.RemoveContactFromList -> contacts.removeFromList(event.listId)
            DocDistEvent.CopyContactEmail -> contacts.copyEmail()
            DocDistEvent.ExportContactsCsv -> contacts.exportCsv()
            is DocDistEvent.ViewEmail -> contacts.viewEmail(event.distributionId)

            // -- templates -------------------------------------------------
            DocDistEvent.OpenNewTemplate -> templates.openNew()
            is DocDistEvent.OpenEditTemplate -> templates.openEdit(event.templateId)
            is DocDistEvent.EditTemplate -> templates.edit(event.name, event.description, event.subject, event.body)
            DocDistEvent.CloseTemplateEditor -> templates.close()
            DocDistEvent.SaveTemplateEditor -> templates.save()
            is DocDistEvent.ConfirmDeleteTemplate -> templates.confirmDelete(event.templateId)
            is DocDistEvent.DeleteTemplate -> templates.delete(event.templateId)
            else -> Unit
        }
    }

    private inline fun editComposer(crossinline change: ComposerState.() -> ComposerState) =
        setState { copy(composer = composer.change()) }

    // -- loading -----------------------------------------------------------

    private fun load(destination: DocDistDestination) {
        loadJob?.cancel()
        when (destination) {
            DocDistDestination.Library -> loadLibrary()
            DocDistDestination.History -> history.load()
            DocDistDestination.Lists -> lists.load()
            DocDistDestination.AddressBook -> contacts.load()
            DocDistDestination.Templates -> templates.load()
        }
    }

    /** A side section opened clears its units' events — the web's modal-open read. */
    private fun readSection(destination: DocDistDestination) {
        destination.badgeUnits.filter { currentState.unread.unit(it) > 0 }.forEach(badges::readUnit)
    }

    private fun openFolder(folderId: String?) {
        // Entering a folder reads the folder's own events (`Library.jsx:228-234`);
        // the files inside stay unread until each is opened.
        if (folderId != null && currentState.unread.folder(folderId) > 0) badges.readFolder(folderId)
        setState {
            copy(
                currentFolderId = folderId,
                // Selection is per folder: carrying it across a navigation
                // means a bulk delete hits rows the user can no longer see.
                selectedDocumentIds = emptySet(),
                selectedFolderIds = emptySet(),
                search = "",
            )
        }
        loadLibrary()
    }

    /**
     * Folders and the first page of documents, together. Cancels whatever
     * was in flight first, so an early reply cannot land after a late one.
     */
    private fun loadLibrary() {
        loadJob?.cancel()
        setState { copy(loading = true, error = null) }
        loadJob = launch {
            val folders = repository.folders()
            val page = repository.documents(query(page = 0))
            setState {
                copy(
                    loading = false,
                    // A folder call that failed keeps the tree that was there.
                    folders = (folders as? ZillitResult.Success)?.data ?: this.folders,
                    error = (page as? ZillitResult.Failure)?.error?.userMessage,
                )
            }
            (page as? ZillitResult.Success)?.let { applyPage(it.data, append = false) }
        }
    }

    private fun query(page: Int) = LibraryQuery(
        folderId = currentState.currentFolderId,
        search = currentState.search,
        documentDate = currentState.dateFilter,
        sort = currentState.sort,
        page = page,
    )

    private fun loadMore() {
        val state = currentState
        if (state.loadingMore || state.loading || !state.hasMore) return
        setState { copy(loadingMore = true) }
        launch {
            val page = repository.documents(query(page = state.documents.size / LibraryQuery.PAGE_LIMIT))
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
                today = today(),
                documents = rows,
                totalDocuments = page.total,
                dateCounts = page.dateCounts,
                groups = if (sort.groupsByDate) {
                    LibraryGrouping.group(rows, page.dateCounts, today(), ascending = sort == LibrarySort.DateAsc)
                } else {
                    emptyList()
                },
            )
        }
    }

    /** Runs [block] once the user stops typing — the web's 300 ms. */
    private fun debounced(block: () -> Unit) {
        searchJob?.cancel()
        searchJob = launch {
            delay(SEARCH_DEBOUNCE_MS)
            block()
        }
    }

    // -- shared ------------------------------------------------------------

    /** Raises the request the frame answers with its admin picker, and says so. */
    private fun askForRights(kind: RightsKind) {
        val asked = rights != null
        if (asked) rights.ask(MODULE_LABEL, kind)
        sendEffect(
            DocDistEffect.Failed(
                if (asked) "You do not have ${kind.verb} rights on $MODULE_LABEL — asking an administrator."
                else "You do not have ${kind.verb} rights on $MODULE_LABEL.",
            ),
        )
    }

    private fun report(error: ZillitError) = sendEffect(DocDistEffect.Failed(error.localised()))

    private inner class Scope : VmScope {
        override val state: DocDistUiState get() = currentState
        override val repository: DocDistRepository get() = this@DocDistViewModel.repository
        override val host: DocDistHost get() = this@DocDistViewModel.host
        override fun update(reducer: DocDistUiState.() -> DocDistUiState) = setState(reducer)
        override fun run(block: suspend CoroutineScope.() -> Unit): Job = launch(block)
        override fun report(error: ZillitError) = this@DocDistViewModel.report(error)
        override fun fail(message: String) = sendEffect(DocDistEffect.Failed(message))
        override fun notice(message: String) = setState { copy(notice = message) }
        override fun today(): LocalDate = this@DocDistViewModel.today()
        override fun reload() = load(currentState.destination)
        override fun loadLibrary() = this@DocDistViewModel.loadLibrary()
        override fun askForRights(kind: RightsKind) = this@DocDistViewModel.askForRights(kind)

        /**
         * The second layer the web keeps deliberately (`requirePost()` in every
         * handler): a stale window or a dialog opened before a rights change
         * can still reach a handler, and the answer should be the same offer.
         */
        override fun refusesWrite(): Boolean {
            if (currentState.viewer.canPost) return false
            askForRights(RightsKind.Post)
            return true
        }

        override fun refusesDownload(): Boolean {
            if (currentState.viewer.canDownload) return false
            askForRights(RightsKind.Download)
            return true
        }
    }

    private companion object {
        /** What the web waits, and long enough that a fast typist sends one request. */
        const val SEARCH_DEBOUNCE_MS = 300L

        /** What the reader calls this tool; it reaches an admin's chat verbatim. */
        const val MODULE_LABEL = "Document Distribution"
    }
}
