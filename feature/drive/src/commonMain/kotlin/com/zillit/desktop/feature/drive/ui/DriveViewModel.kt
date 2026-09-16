package com.zillit.desktop.feature.drive.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.permissions.rightsRefusalMessage
import com.zillit.desktop.feature.drive.domain.DriveAction
import com.zillit.desktop.feature.drive.domain.DriveInnerTab
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveListQuery
import com.zillit.desktop.feature.drive.domain.DriveListing
import com.zillit.desktop.feature.drive.domain.DrivePerson
import com.zillit.desktop.feature.drive.domain.DriveRef
import com.zillit.desktop.feature.drive.domain.DriveRepository
import com.zillit.desktop.feature.drive.domain.DriveScope
import com.zillit.desktop.feature.drive.domain.DriveSection
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.domain.MyDriveFilter
import com.zillit.desktop.feature.drive.domain.PreviewKind
import com.zillit.desktop.feature.drive.domain.breadcrumbTo
import com.zillit.desktop.feature.drive.domain.eligible
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The Drive's one view model.
 *
 * ## One scope at a time, narrowed on the client
 *
 * [loadListing] fetches every file and folder of the open scope — My Drive,
 * Shared with me, or Shared by me — the way the web's `fetchDriveData` does,
 * and [DriveUiState.rows] narrows to the open folder. That is what gives the
 * folder tree for "Move to…", the folder sizes, the search-hit breadcrumb and
 * instant folder navigation. Every mutation ends by reloading the scope.
 *
 * ## The viewer is resolved before anything else
 *
 * Rights decide whether Upload appears and whether the tool renders at all,
 * so [start] reads them first. The view model is built once for the whole
 * app — before any production is open — so the viewer is a lambda resolved
 * in [start], never a constructor snapshot.
 *
 * The drawers, the details panel, the uploads and the preview each live in a
 * collaborator with the same seams ([update], [run], [reportError]) so this
 * class stays the dispatcher and the navigation.
 */
// One handler per user action, one seam per host capability; the drawers, details and uploads already
// live in their own collaborators.
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class DriveViewModel(
    private val repository: DriveRepository,
    /** Who is looking, read at start rather than at construction. See the class doc. */
    private val viewer: () -> DriveViewer,
    /**
     * Performs the actual byte transfer for an upload.
     *
     * A port because common code has no file API and no way to read a 10 GB
     * file in chunks. The default does nothing so tests and the render harness
     * can build this view model without a host — an upload simply never starts.
     */
    private val uploader: DriveUploader = DriveUploader.Unsupported,
    /** Fetches preview bytes and rasterises PDFs — the preview dialog's host. */
    private val previewHost: DrivePreviewHost = DrivePreviewHost.Unsupported,
    /** The production's crew, for names, avatars and the share pickers. */
    private val crew: () -> List<DrivePerson> = { emptyList() },
    /** Ids for queued uploads. Injected so a test can predict them. */
    private val newUploadId: () -> String = { "upload-" + (uploadCounter++) },
    /** Carries a refused press to the app frame, which offers to ask an admin. */
    private val rights: RightsRequestBus? = null,
    /** The clock, for "expired" and relative stamps. */
    internal val now: () -> Long = { 0L },
) : ZillitViewModel<DriveUiState, DriveEvent, DriveEffect>(DriveUiState(viewer = viewer())) {

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var started = false
    private var listening = false

    /** The "Shared with me" landing falls back to My Drive once, if it is empty (ZL-21229). */
    private var landing = true

    private val details = DriveDetails(this)
    private val uploads = UploadQueue(this, uploader, newUploadId)
    private val drawers = DriveDrawers(this, uploads)
    private val thumbnailsInFlight = mutableSetOf<String>()

    /**
     * Files whose thumbnail could not be fetched. Without this a row that
     * recomposes (a listing pulse, a hover) asks again, and a broken preview
     * turns into a presigned-URL request every few seconds.
     */
    private val thumbnailsFailed = mutableSetOf<String>()
    private val thumbnailPermits = Semaphore(THUMBNAIL_PARALLELISM)

    fun start() {
        if (started) return
        started = true
        landing = true
        val identity = viewer()
        setState { copy(viewer = identity, crew = crew()) }
        if (!identity.isBlocked) {
            loadListing()
            launch { primeFavourites() }
            launch { primeTags() }
            launch { primeShareable() }
        }
        listenOnce()
    }

    /**
     * Reloads the open scope when the socket announces a change from
     * another client — the web's refetch on the same events. Guarded
     * separately from [started]: a project switch resets [started] but must
     * not stack a collector.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.collect {
                if (started && !currentState.viewer.isBlocked) {
                    loadListing()
                    if (currentState.showTrash) loadTrash()
                }
            }
        }
    }

    /**
     * The host could not open the document editor or the media player.
     *
     * Routed back through the view model rather than surfaced by the host, so
     * it reaches the user on the same toast as every other failure in this
     * tool.
     */
    fun onEditorUnavailable(reason: String) {
        sendEffect(DriveEffect.Failed(reason))
    }

    /** Re-reads rights when the open production changes. */
    fun onProjectChanged() {
        started = false
        uploads.cancelAll()
        setState { DriveUiState(viewer = viewer()) }
        start()
    }

    /**
     * Swaps in the real rights once the tool grid has answered.
     *
     * `projectId` flips the moment a production is chosen, but the rights that
     * gate this screen arrive with the Home load a beat later — so the viewer
     * resolved at open is the "not yet known" one, and nothing used to replace
     * it. Only the viewer changes here; the open page and its data are already
     * right.
     */
    fun onRightsChanged() {
        val resolved = viewer()
        setState { copy(viewer = resolved, crew = crew()) }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One branch per user action.
    override fun onEvent(event: DriveEvent) {
        when (event) {
            DriveEvent.Refresh -> {
                loadListing()
                if (currentState.showTrash) loadTrash()
            }

            DriveEvent.ClearNotice -> setState { copy(notice = null) }
            DriveEvent.DismissPrompt -> setState { copy(prompt = null) }
            DriveEvent.ConfirmPrompt -> currentState.prompt?.let { prompt ->
                setState { copy(prompt = null) }
                onEvent(prompt.event)
            }

            // -- navigation ------------------------------------------------

            is DriveEvent.OpenSection -> openSection(event.section)
            is DriveEvent.FilterMyDrive -> {
                setState { copy(myDriveFilter = event.filter, selected = emptySet()) }
                loadListing()
            }

            is DriveEvent.OpenInnerTab -> setState { copy(innerTab = event.tab, selected = emptySet()) }
            is DriveEvent.SetViewMode -> setState { copy(viewMode = event.mode) }
            is DriveEvent.ShowTrash -> {
                setState { copy(showTrash = event.show, selected = emptySet(), menu = null) }
                if (event.show) loadTrash()
            }

            is DriveEvent.OpenFolder -> openFolder(event.folderId)
            DriveEvent.GoBack -> openFolder(currentState.breadcrumb.dropLast(1).lastOrNull()?.id)
            is DriveEvent.OpenItem ->
                if (event.item.isFolder) openFolder(event.item.id) else drawers.preview(event.item)

            is DriveEvent.SearchInput -> searchInput(event.text)
            DriveEvent.CommitSearch -> commitSearch()
            DriveEvent.ClearSearch -> {
                searchJob?.cancel()
                val wasSearching = currentState.isSearching
                setState { copy(searchInput = "", search = "") }
                if (wasSearching) loadListing()
            }

            is DriveEvent.SortBy -> setState { copy(sort = sort.toggled(event.column)) }
            is DriveEvent.FilterByTag -> filterByTag(event.tagId)
            is DriveEvent.ShowFavouritesOnly -> setState { copy(showFavouritesOnly = event.on) }

            is DriveEvent.ToggleSelection -> setState { copy(selected = selected.toggled(event.itemId)) }
            is DriveEvent.SetSelected -> setState {
                copy(selected = if (event.selected) selected + event.itemId else selected - event.itemId)
            }

            is DriveEvent.SelectAll -> setState {
                copy(selected = if (event.selected) rows.map { it.id }.toSet() else emptySet())
            }

            DriveEvent.ClearSelection -> setState { copy(selected = emptySet()) }
            is DriveEvent.OpenMenu -> setState { copy(menu = ItemMenuState(event.item, event.x, event.y)) }
            DriveEvent.CloseMenu -> setState { copy(menu = null) }
            is DriveEvent.WantThumbnail -> wantThumbnail(event.item)

            // -- acting on items -------------------------------------------

            is DriveEvent.MoveTo -> moveTo(event.refs, event.targetFolderId)
            is DriveEvent.RequestDelete -> requestDelete(event.refs)
            is DriveEvent.Delete -> delete(event.refs)
            is DriveEvent.ToggleFavourite -> toggleFavourite(event.ref)
            is DriveEvent.Download -> download(event.item)
            DriveEvent.DownloadSelection -> downloadSelection()
            is DriveEvent.CopyLink -> copyLink(event.item)
            is DriveEvent.OpenInEditor -> openEditor(event.item, event.editable)
            is DriveEvent.Preview -> drawers.preview(event.item)
            DriveEvent.ClosePreview -> setState { copy(preview = null) }
            DriveEvent.OpenPreviewInBrowser -> currentState.preview?.url?.let { sendEffect(DriveEffect.OpenUrl(it)) }

            // -- uploads ---------------------------------------------------

            DriveEvent.OpenUpload -> drawers.openUpload()
            DriveEvent.CloseUpload -> setState { copy(upload = null) }
            DriveEvent.PickUploadFiles -> sendEffect(DriveEffect.PickFiles)
            DriveEvent.PickUploadFolder -> sendEffect(DriveEffect.PickFolder)
            is DriveEvent.PickFilesOf -> if (requirePosting()) sendEffect(DriveEffect.PickFilesOf(event.kind))
            is DriveEvent.AddUploadFiles -> drawers.addUploadFiles(event.files)
            is DriveEvent.RemoveUploadFile -> drawers.updateUpload {
                copy(files = files.filterNot { it.path == event.path })
            }
            DriveEvent.ClearUploadFiles -> drawers.updateUpload { copy(files = emptyList(), unsupported = emptyList()) }
            DriveEvent.ClearUnsupported -> drawers.updateUpload { copy(unsupported = emptyList()) }
            is DriveEvent.UploadDestination -> drawers.updateUpload {
                copy(pickExisting = event.pickExisting, destinationFolderId = event.folderId)
            }

            is DriveEvent.UploadDescription -> drawers.updateUpload { copy(description = event.text) }
            DriveEvent.ToggleUploadDetails -> drawers.updateUpload { copy(showDetails = !showDetails) }
            DriveEvent.ToggleUploadAccess -> drawers.updateUpload { copy(accessExpanded = !accessExpanded) }
            is DriveEvent.UploadAccess -> drawers.updateUpload { copy(access = event.access) }
            DriveEvent.SubmitUpload -> drawers.submitUpload()

            is DriveEvent.DropFiles -> drawers.dropFiles(event.files)
            is DriveEvent.DropAccess -> setState { copy(dropUpload = dropUpload?.copy(access = event.access)) }
            is DriveEvent.ConfirmDrop -> drawers.confirmDrop(event.withAccess)
            DriveEvent.CancelDrop -> setState { copy(dropUpload = null) }

            is DriveEvent.CancelUpload -> uploads.cancel(event.uploadId)
            is DriveEvent.RemoveUpload -> setState {
                copy(uploads = this.uploads.filterNot { it.id == event.uploadId })
            }
            DriveEvent.ClearFinishedUploads -> setState { copy(uploads = this.uploads.filterNot { it.isSettled }) }
            DriveEvent.ToggleOperations -> setState { copy(operationsExpanded = !operationsExpanded) }

            // -- create folder ---------------------------------------------

            DriveEvent.OpenNewFolder -> drawers.openNewFolder()
            DriveEvent.CloseNewFolder -> setState { copy(newFolder = null) }
            is DriveEvent.NewFolderName -> drawers.updateNewFolder { copy(name = event.text) }
            is DriveEvent.NewFolderDescription -> drawers.updateNewFolder { copy(description = event.text) }
            is DriveEvent.NewFolderDestination -> drawers.updateNewFolder {
                copy(pickExisting = event.pickExisting, destinationFolderId = event.folderId)
            }

            is DriveEvent.NewFolderAccess -> drawers.updateNewFolder { copy(access = event.access) }
            DriveEvent.SubmitNewFolder -> drawers.submitNewFolder()

            // -- edit info -------------------------------------------------

            is DriveEvent.OpenEdit -> drawers.openEdit(event.item)
            DriveEvent.CloseEdit -> setState { copy(edit = null) }
            is DriveEvent.EditName -> drawers.updateEdit { copy(name = event.text) }
            is DriveEvent.EditDescription -> drawers.updateEdit { copy(description = event.text) }
            DriveEvent.SubmitEdit -> drawers.submitEdit()

            // -- share -----------------------------------------------------

            is DriveEvent.OpenShare -> drawers.openShare(event.item)
            DriveEvent.CloseShare -> setState { copy(share = null) }
            is DriveEvent.ShareTabTo -> drawers.updateShare { copy(tab = event.tab) }
            is DriveEvent.ShareAccess -> drawers.updateShare { copy(access = event.access) }
            DriveEvent.SubmitShare -> drawers.submitShare()
            is DriveEvent.ShareLinkRecipients -> drawers.updateLink { copy(recipients = event.text) }
            is DriveEvent.ShareLinkPermission -> drawers.updateLink { copy(permission = event.permission) }
            is DriveEvent.ShareLinkExpiry -> drawers.updateLink { copy(expiresInMillis = event.millis) }
            is DriveEvent.ShareLinkMaxViews -> drawers.updateLink { copy(maxViews = event.views) }
            is DriveEvent.ShareLinkMessage -> drawers.updateLink { copy(message = event.text) }
            DriveEvent.GenerateShareLink -> drawers.generateShareLink()
            is DriveEvent.CopyShareLink -> sendEffect(DriveEffect.CopyToClipboard(event.link.url, "Link copied"))
            is DriveEvent.RevokeShareLink -> drawers.revokeShareLink(event.link)

            // -- move to ---------------------------------------------------

            is DriveEvent.OpenMoveTo -> drawers.openMoveTo(event.items)
            DriveEvent.OpenMoveSelection -> drawers.openMoveTo(currentState.selectedItems)
            DriveEvent.CloseMoveTo -> setState { copy(moveTo = null) }
            is DriveEvent.PickMoveTarget -> setState { copy(moveTo = moveTo?.copy(targetFolderId = event.folderId)) }
            DriveEvent.ConfirmMove -> currentState.moveTo?.let { picker ->
                setState { copy(moveTo = null) }
                moveTo(picker.items.map { it.ref }, picker.targetFolderId)
            }

            // -- file requests ---------------------------------------------

            is DriveEvent.OpenFileRequests -> openFileRequests(event.folder)
            DriveEvent.OpenFileRequestsHere -> currentState.folderId?.let { id ->
                currentState.folderById[id]?.let(::openFileRequests)
            }

            DriveEvent.CloseFileRequests -> setState { copy(fileRequests = FileRequestState()) }
            is DriveEvent.FileRequestTitle -> setState {
                copy(fileRequests = fileRequests.copy(title = event.text))
            }

            is DriveEvent.FileRequestDescription -> setState {
                copy(fileRequests = fileRequests.copy(description = event.text))
            }

            is DriveEvent.FileRequestExpiry -> setState {
                copy(fileRequests = fileRequests.copy(expiryDays = event.days))
            }

            is DriveEvent.FileRequestRequireName -> setState {
                copy(fileRequests = fileRequests.copy(requireName = event.on))
            }

            is DriveEvent.FileRequestRequireEmail -> setState {
                copy(fileRequests = fileRequests.copy(requireEmail = event.on))
            }

            DriveEvent.SubmitFileRequest -> submitFileRequest()
            is DriveEvent.CopyFileRequest -> sendEffect(
                DriveEffect.CopyToClipboard(event.request.link, "Request link copied"),
            )

            is DriveEvent.RevokeFileRequest -> revokeFileRequest(event.request)

            // -- activity log ----------------------------------------------

            DriveEvent.OpenActivityLog -> drawers.openActivityLog()
            DriveEvent.CloseActivityLog -> setState { copy(activityLog = ActivityLogState()) }
            is DriveEvent.FilterActivity -> setState { copy(activityLog = activityLog.copy(filter = event.category)) }
            DriveEvent.LoadMoreActivity -> drawers.loadMoreActivity()
            DriveEvent.RefreshActivity -> drawers.openActivityLog()

            // -- details ---------------------------------------------------

            is DriveEvent.ShowDetails -> details.show(event.item)
            is DriveEvent.CommentDraft -> setState { copy(details = details.copy(commentDraft = event.text)) }
            DriveEvent.PostComment -> details.postComment()
            is DriveEvent.StartEditComment -> setState {
                copy(details = details.copy(editingCommentId = event.commentId, editingText = event.text))
            }

            is DriveEvent.EditCommentText -> setState { copy(details = details.copy(editingText = event.text)) }
            DriveEvent.SaveComment -> details.saveComment()
            DriveEvent.CancelEditComment -> setState {
                copy(details = details.copy(editingCommentId = null, editingText = ""))
            }

            is DriveEvent.DeleteComment -> details.deleteComment(event.commentId)
            is DriveEvent.TagDraft -> details.tagDraft(event.text)
            is DriveEvent.AssignTag -> details.assignTag(event.tagId)
            is DriveEvent.RemoveTag -> details.removeTag(event.tagId)
            DriveEvent.CreateAndAssignTag -> details.createAndAssignTag()
            is DriveEvent.DownloadVersion -> downloadVersion(event.item, event.versionId)
            is DriveEvent.RequestRestoreVersion -> setState {
                copy(
                    prompt = DrivePrompt(
                        title = "Restore this version?",
                        message = "The current file will be saved as a new version first.",
                        confirmLabel = "Restore",
                        event = DriveEvent.RestoreVersion(event.fileId, event.versionId),
                        danger = false,
                    ),
                )
            }

            is DriveEvent.RestoreVersion -> mutate(
                { repository.restoreVersion(event.fileId, event.versionId) },
                "Version restored",
            ) { details.reloadOpen() }

            // -- trash -----------------------------------------------------

            is DriveEvent.Restore -> mutate({ repository.restore(event.ref) }, "Restored") { loadTrash() }
            is DriveEvent.RequestPurge -> setState {
                copy(
                    prompt = DrivePrompt(
                        title = "Permanently delete?",
                        message = "\"${event.item.name}\" will be permanently removed. This action cannot be undone.",
                        confirmLabel = "Delete forever",
                        event = DriveEvent.Purge(event.item.ref),
                    ),
                )
            }

            is DriveEvent.Purge -> mutate({ repository.purge(event.ref) }, "Permanently deleted") { loadTrash() }
            DriveEvent.RequestEmptyTrash -> setState {
                copy(
                    prompt = DrivePrompt(
                        title = "Empty trash?",
                        message = "All ${trash.items.size} item" +
                            (if (trash.items.size == 1) "" else "s") +
                            " will be permanently deleted. This cannot be undone.",
                        confirmLabel = "Empty trash",
                        event = DriveEvent.EmptyTrash,
                    ),
                )
            }

            DriveEvent.EmptyTrash -> mutate({ repository.emptyTrash() }, "Trash emptied") { loadTrash() }
        }
    }

    // -- navigation --------------------------------------------------------

    private fun openSection(section: DriveSection) {
        if (section == currentState.section) return
        landing = false
        setState {
            copy(
                section = section,
                myDriveFilter = MyDriveFilter.All,
                folderId = null,
                breadcrumb = emptyList(),
                selected = emptySet(),
                details = DetailsState(),
                menu = null,
            )
        }
        loadListing()
    }

    /**
     * Opens [folderId], rebuilding the trail from the folders' own ancestry.
     *
     * From a search hit the search is cleared first and the scope refetched:
     * a search-scoped list ignores the folder, and the web's breadcrumb was
     * corrupted exactly this way before ZL-20182.
     */
    private fun openFolder(folderId: String?) {
        val state = currentState
        setState {
            copy(
                folderId = folderId,
                breadcrumb = breadcrumbTo(folderId, listing.folders),
                selected = emptySet(),
                menu = null,
            )
        }
        if (state.isSearching) {
            searchJob?.cancel()
            setState { copy(searchInput = "", search = "") }
            loadListing()
        }
    }

    private fun searchInput(text: String) {
        setState { copy(searchInput = text) }
        searchJob?.cancel()
        searchJob = launch {
            delay(SEARCH_DEBOUNCE_MS)
            commitSearch()
        }
    }

    private fun commitSearch() {
        val next = currentState.searchInput.trim()
        if (next == currentState.search) return
        setState { copy(search = next, selected = emptySet()) }
        loadListing()
    }

    private fun filterByTag(tagId: String?) {
        setState { copy(tagFilterId = tagId, taggedIds = null) }
        if (tagId == null) return
        launch {
            val ids = repository.itemsByTag(tagId)
            if (currentState.tagFilterId != tagId) return@launch
            setState { copy(taggedIds = (ids as? ZillitResult.Success)?.data ?: emptySet()) }
        }
    }

    // -- loading -----------------------------------------------------------

    /**
     * The scope's files and folders.
     *
     * Cancels whatever was in flight first: a search fires one of these per
     * pause in typing, and without cancellation an early reply can land after
     * a late one and repopulate the listing with a stale query's rows.
     */
    internal fun loadListing() {
        loadJob?.cancel()
        val state = currentState
        val query = DriveListQuery(
            scope = DriveScope.of(state.section, state.myDriveFilter),
            search = state.search,
        )
        setState { copy(loading = true, error = null) }
        loadJob = launch {
            when (val loaded = repository.listing(query)) {
                is ZillitResult.Success -> applyListing(loaded.data, query)
                is ZillitResult.Failure -> setState {
                    copy(loading = false, error = loaded.error.localised())
                }
            }
        }
    }

    private fun applyListing(listing: DriveListing, query: DriveListQuery) {
        val state = currentState
        val folderGone = state.folderId != null && !state.isSearching &&
            listing.folders.none { it.id == state.folderId }
        setState {
            copy(
                loading = false,
                listing = listing,
                // A folder that vanished under the user (deleted elsewhere,
                // or access withdrawn) sends them back to the root, and says so.
                folderId = if (folderGone) null else folderId,
                breadcrumb = if (folderGone) emptyList() else breadcrumbTo(folderId, listing.folders),
                notice = if (folderGone) "That folder no longer exists — back at the root." else notice,
            )
        }
        // ZL-21229: the "Shared with me" landing only stays when it has
        // something to show; the first fetch answers that without a second
        // round trip, and a user-driven tab change is never second-guessed.
        if (landing) {
            landing = false
            if (query.scope == DriveScope.Shared && listing.isEmpty && state.section == DriveSection.SharedWithMe) {
                openSection(DriveSection.MyDrive)
            }
        }
    }

    internal fun loadTrash() {
        setState { copy(trash = trash.copy(loading = true)) }
        launch {
            when (val loaded = repository.trash()) {
                is ZillitResult.Success -> setState {
                    copy(trash = TrashState(items = loaded.data.sortedByDescending { it.deletedAt ?: 0L }))
                }

                is ZillitResult.Failure -> {
                    setState { copy(trash = trash.copy(loading = false)) }
                    report(loaded.error)
                }
            }
        }
    }

    private suspend fun primeFavourites() {
        (repository.favouriteIds() as? ZillitResult.Success)?.let { loaded ->
            setState { copy(favouriteIds = loaded.data) }
        }
    }

    private suspend fun primeTags() {
        (repository.tags() as? ZillitResult.Success)?.let { loaded ->
            setState { copy(tags = loaded.data) }
        }
    }

    private suspend fun primeShareable() {
        (repository.viewAccessUserIds() as? ZillitResult.Success)?.let { loaded ->
            setState { copy(shareableIds = loaded.data) }
        }
    }

    // -- mutations ---------------------------------------------------------

    /**
     * Runs a write, then reloads the scope it changed.
     *
     * Reloading rather than patching the list in place: the server assigns
     * ordering, per-item permissions and the sharing summary, and a locally
     * patched row disagrees with all three until the next refresh.
     */
    internal fun mutate(
        block: suspend () -> ZillitResult<Unit>,
        success: String,
        onDone: () -> Unit = {},
    ) {
        launch {
            when (val result = block()) {
                is ZillitResult.Success -> {
                    onDone()
                    setState { copy(notice = success) }
                    loadListing()
                }

                is ZillitResult.Failure -> report(result.error)
            }
        }
    }

    private fun moveTo(refs: List<DriveRef>, targetFolderId: String?) {
        if (refs.isEmpty()) return
        val state = currentState
        val matched = state.rows.filter { row -> refs.any { it.id == row.id } }
        val allowed = state.viewer.eligible(DriveAction.Edit, matched)
        if (matched.isNotEmpty() && allowed.isEmpty()) {
            sendEffect(DriveEffect.Failed("You do not have permission to move the selected items."))
            return
        }
        // A folder cannot go into itself or its own descendants; the server
        // would refuse the loop, and refusing here says why.
        if (targetFolderId != null && allowed.any { it.isFolder && it.id == targetFolderId }) {
            sendEffect(DriveEffect.Failed("A folder cannot be moved into itself."))
            return
        }
        val toMove = if (matched.isEmpty()) refs else allowed.map { it.ref }
        val targetName = targetFolderId?.let { state.folderById[it]?.name } ?: state.rootName
        val skipped = matched.size - allowed.size
        mutate(
            {
                if (toMove.size == 1) {
                    repository.move(toMove.first(), targetFolderId)
                } else {
                    repository.bulkMove(toMove, targetFolderId)
                }
            },
            buildString {
                if (toMove.size == 1) {
                    append("Moved \"${allowed.firstOrNull()?.name ?: "item"}\"")
                } else {
                    append("Moved ${toMove.size} items")
                }
                append(" to $targetName")
                if (skipped > 0) append(" · $skipped skipped (no edit rights)")
            },
        ) { setState { copy(selected = emptySet()) } }
    }

    /**
     * Raises the delete confirmation, naming what will and will not go.
     *
     * The count is what the viewer may actually delete, not what they selected:
     * the server checks each item, so a mixed selection partially succeeds, and
     * a dialog that promises twenty and removes seventeen is a dialog that lied.
     */
    private fun requestDelete(refs: List<DriveRef>) {
        val state = currentState
        if (!state.viewer.canPost && !state.viewer.isAdmin) {
            askForRights(RightsKind.Post)
            return
        }
        val items = state.rows.filter { item -> refs.any { it.id == item.id } }
        val allowed = state.viewer.eligible(DriveAction.Delete, items)
        if (allowed.isEmpty()) {
            sendEffect(DriveEffect.Failed("You do not have permission to delete the selected items."))
            return
        }
        val blocked = items.size - allowed.size
        setState {
            copy(
                menu = null,
                prompt = DrivePrompt(
                    title = if (allowed.size == 1) {
                        "Delete ${if (allowed.first().isFolder) "folder" else "file"}?"
                    } else {
                        "Delete ${allowed.size} items?"
                    },
                    message = buildString {
                        append("This action will move the selected item")
                        append(if (allowed.size == 1) "" else "s")
                        append(" to trash.")
                        if (allowed.any { it.isFolder }) append(" Everything inside a folder goes with it.")
                        if (blocked > 0) {
                            append(" ($blocked item")
                            append(if (blocked == 1) "" else "s")
                            append(" skipped — insufficient permissions.)")
                        }
                    },
                    confirmLabel = "Delete",
                    event = DriveEvent.Delete(allowed.map { it.ref }),
                ),
            )
        }
    }

    private fun delete(refs: List<DriveRef>) {
        if (refs.isEmpty()) return
        val deletingCurrent = refs.any { it.id == currentState.folderId }
        val parent = currentState.breadcrumb.dropLast(1).lastOrNull()?.id
        mutate(
            {
                if (refs.size == 1) repository.delete(refs.first()) else repository.bulkDelete(refs)
            },
            if (refs.size == 1) "Moved to trash" else "${refs.size} items moved to trash",
        ) {
            setState {
                copy(
                    selected = selected - refs.map { it.id }.toSet(),
                    details = if (details.item?.id in refs.map { it.id }) DetailsState() else details,
                    // Deleting the folder we are standing in: step up to its
                    // parent rather than wait for the reload to bounce us.
                    folderId = if (deletingCurrent) parent else folderId,
                    breadcrumb = if (deletingCurrent) breadcrumb.dropLast(1) else breadcrumb,
                )
            }
        }
    }

    /**
     * Stars or unstars, updating the set before the server answers.
     *
     * The one optimistic update in this module: a star that waits for a round
     * trip feels broken, and the cost of being wrong is a star in the wrong
     * state until the next refresh rather than a lost file.
     */
    private fun toggleFavourite(ref: DriveRef) {
        if (!currentState.viewer.canPost && !currentState.viewer.isAdmin) {
            askForRights(RightsKind.Post)
            return
        }
        val adding = ref.id !in currentState.favouriteIds
        setState { copy(favouriteIds = favouriteIds.toggled(ref.id), menu = null) }
        launch {
            when (val result = repository.toggleFavourite(ref)) {
                is ZillitResult.Success -> setState {
                    copy(notice = if (adding) "Added to favourites" else "Removed from favourites")
                }

                is ZillitResult.Failure -> {
                    setState { copy(favouriteIds = favouriteIds.toggled(ref.id)) }
                    report(result.error)
                }
            }
        }
    }

    // -- opening files -----------------------------------------------------

    internal fun openEditor(item: DriveItem, editable: Boolean) {
        setState { copy(menu = null) }
        launch {
            when (val url = repository.editorUrl(item.id, editable)) {
                is ZillitResult.Success -> {
                    sendEffect(DriveEffect.OpenEditor(url.data, item.name))
                    if (!editable) setState { copy(notice = "Opening in preview mode") }
                }

                is ZillitResult.Failure -> report(url.error)
            }
        }
    }

    private fun download(item: DriveItem) {
        setState { copy(menu = null) }
        if (!currentState.viewer.canDownload && !currentState.viewer.isAdmin) {
            askForRights(RightsKind.Download)
            return
        }
        if (!currentState.viewer.may(DriveAction.Download, item)) {
            sendEffect(DriveEffect.Failed("You do not have download rights for that file."))
            return
        }
        launch {
            when (val url = repository.downloadUrl(item.id)) {
                is ZillitResult.Success -> {
                    setState { copy(notice = "Downloading ${item.name}") }
                    sendEffect(DriveEffect.OpenUrl(url.data))
                }

                is ZillitResult.Failure -> report(url.error)
            }
        }
    }

    /**
     * Downloads one earlier version of a file.
     *
     * Gated on the same download right as the current version — the web's
     * ZL-18294 fix, which existed because an old version was reachable by
     * someone who could not download the live one.
     */
    private fun downloadVersion(item: DriveItem, versionId: String) {
        if (!currentState.viewer.may(DriveAction.Download, item)) {
            askForRights(RightsKind.Download)
            return
        }
        launch {
            when (val url = repository.versionDownloadUrl(item.id, versionId)) {
                is ZillitResult.Success -> sendEffect(DriveEffect.OpenUrl(url.data))
                is ZillitResult.Failure -> report(url.error)
            }
        }
    }

    private fun downloadSelection() {
        val state = currentState
        if (!state.viewer.canDownload && !state.viewer.isAdmin) {
            askForRights(RightsKind.Download)
            return
        }
        val files = state.selectedItems.filterNot { it.isFolder }
        val allowed = state.viewer.eligible(DriveAction.Download, files)
        if (allowed.isEmpty()) {
            sendEffect(
                DriveEffect.Failed(
                    if (files.isEmpty()) {
                        "No files selected for download (folders cannot be downloaded yet)."
                    } else {
                        "You do not have permission to download the selected files."
                    },
                ),
            )
            return
        }
        val skipped = files.size - allowed.size
        setState {
            copy(
                notice = "Downloading ${allowed.size} file" + (if (allowed.size == 1) "" else "s") +
                    (if (skipped > 0) " ($skipped skipped — no download permission)" else "…"),
            )
        }
        launch {
            when (val urls = repository.bulkDownloadUrls(allowed.map { it.id })) {
                is ZillitResult.Success -> urls.data.forEach { sendEffect(DriveEffect.OpenUrl(it)) }
                is ZillitResult.Failure -> report(urls.error)
            }
        }
    }

    /** The bare 24-hour link — a form of re-sharing, so it needs posting rights. */
    private fun copyLink(item: DriveItem) {
        setState { copy(menu = null) }
        if (!currentState.viewer.canPost && !currentState.viewer.isAdmin) {
            askForRights(RightsKind.Post)
            return
        }
        launch {
            when (val link = repository.shareLink(item.id)) {
                is ZillitResult.Success -> sendEffect(
                    DriveEffect.CopyToClipboard(link.data, "Link copied (expires in 24h)"),
                )

                is ZillitResult.Failure -> report(link.error)
            }
        }
    }

    // -- thumbnails --------------------------------------------------------

    /**
     * A small preview for an image row, fetched once and kept for the
     * session. Bounded in size and in parallelism: a grid of fifty photos
     * must not open fifty downloads at once.
     */
    private fun wantThumbnail(item: DriveItem) {
        if (item.isFolder || item.previewKind != PreviewKind.Image) return
        if (item.sizeBytes > THUMBNAIL_MAX_BYTES) return
        if (item.id in currentState.thumbnails || item.id in thumbnailsFailed) return
        if (!thumbnailsInFlight.add(item.id)) return
        launch {
            val fetched = thumbnailPermits.withPermit {
                val url = repository.previewUrl(item.id) as? ZillitResult.Success ?: return@withPermit false
                val bytes = previewHost.fetchBytes(url.data, THUMBNAIL_MAX_BYTES) as? ZillitResult.Success
                    ?: return@withPermit false
                setState { copy(thumbnails = thumbnails + (item.id to bytes.data)) }
                true
            }
            if (!fetched) thumbnailsFailed += item.id
            thumbnailsInFlight.remove(item.id)
        }
    }

    // -- file requests -----------------------------------------------------

    /** Refuses, and offers the one thing that changes the answer. */
    internal fun askForRights(kind: RightsKind) {
        rights?.ask(MODULE_LABEL, kind)
        sendEffect(DriveEffect.Failed(rightsRefusalMessage(MODULE_LABEL, kind, rights != null)))
    }

    private fun openFileRequests(folder: DriveItem) {
        setState { copy(menu = null) }
        if (!currentState.viewer.canPost && !currentState.viewer.isAdmin) {
            askForRights(RightsKind.Post)
            return
        }
        setState { copy(fileRequests = FileRequests.opening(folder)) }
        launch {
            val rows = repository.fileRequests(folder.id)
            setState {
                copy(
                    fileRequests = FileRequests.loaded(
                        fileRequests,
                        (rows as? ZillitResult.Success)?.data.orEmpty(),
                    ),
                )
            }
        }
    }

    /** Makes the request, then keeps its link on screen to be copied. */
    private fun submitFileRequest() {
        if (!currentState.fileRequests.canSubmit) return
        val draft = FileRequests.draft(currentState.fileRequests) ?: return
        setState { copy(fileRequests = fileRequests.copy(submitting = true)) }
        launch {
            when (val made = repository.createFileRequest(draft)) {
                is ZillitResult.Success -> {
                    setState { copy(fileRequests = FileRequests.created(fileRequests, made.data)) }
                    sendEffect(DriveEffect.CopyToClipboard(made.data.link, "Request link copied"))
                }

                is ZillitResult.Failure -> {
                    setState { copy(fileRequests = fileRequests.copy(submitting = false)) }
                    report(made.error)
                }
            }
        }
    }

    /** Closes one for good; the row stays, marked, so the reader sees what happened. */
    private fun revokeFileRequest(request: com.zillit.desktop.feature.drive.domain.DriveFileRequest) {
        launch {
            when (val answer = repository.revokeFileRequest(request.id)) {
                is ZillitResult.Success ->
                    setState { copy(fileRequests = FileRequests.revoked(fileRequests, request.id)) }

                is ZillitResult.Failure -> report(answer.error)
            }
        }
    }

    // -- shared ------------------------------------------------------------

    private fun report(error: ZillitError) {
        sendEffect(DriveEffect.Failed(error.localised()))
    }

    // -- seams for the collaborators ----------------------------------------

    internal val repo: DriveRepository get() = repository

    internal val previews: DrivePreviewHost get() = previewHost

    internal fun update(reducer: DriveUiState.() -> DriveUiState) = setState(reducer)

    internal fun run(block: suspend () -> Unit): Job = launch { block() }

    internal fun effect(effect: DriveEffect) = sendEffect(effect)

    internal fun reportError(error: ZillitError) = report(error)

    internal fun reportFailure(reason: String) = sendEffect(DriveEffect.Failed(reason))

    internal fun notice(text: String) = setState { copy(notice = text) }

    internal suspend fun reloadTags() = primeTags()

    /** Whether the user may write at all; refuses through the rights bus when not. */
    internal fun requirePosting(): Boolean {
        val v = currentState.viewer
        if (v.isAdmin || v.canPost) return true
        askForRights(RightsKind.Post)
        return false
    }

    private companion object {
        var uploadCounter = 0
        const val SEARCH_DEBOUNCE_MS = 300L
        const val THUMBNAIL_PARALLELISM = 3
        /** Bigger than this and a thumbnail costs more than it saves. */
        const val THUMBNAIL_MAX_BYTES = 8L * 1024 * 1024
    }
}

/**
 * Puts one file's bytes into the drive.
 *
 * A port because common code has no file API: reading a 10 GB file a chunk at a
 * time is a platform concern, and the presigned PUTs it drives are ordinary
 * HTTP the host already has a client for. Kept as an interface so the view
 * model, the render tests and the drag-and-drop host all talk to the same
 * contract.
 */
interface DriveUploader {

    /**
     * Uploads [file] into [target] and returns the created drive item.
     *
     * [onProgress] receives (uploaded parts, total parts) as each part is
     * acknowledged — parts rather than bytes, because that is what the client
     * can honestly observe: a part is either accepted by S3 or it is not.
     */
    suspend fun upload(
        file: PickedFile,
        target: UploadTarget,
        onProgress: (uploaded: Int, total: Int) -> Unit,
    ): ZillitResult<DriveItem>

    /**
     * The uploader before a host has supplied one.
     *
     * Fails rather than silently doing nothing: an upload that reports success
     * without moving a byte is the worst possible default, and this way a
     * missing wiring shows up as a message the first time anyone tries.
     */
    object Unsupported : DriveUploader {
        override suspend fun upload(
            file: PickedFile,
            target: UploadTarget,
            onProgress: (Int, Int) -> Unit,
        ): ZillitResult<DriveItem> = ZillitResult.Failure(
            ZillitError.Validation("Uploading is not available in this build."),
        )
    }
}

/** Where an upload lands and what goes on its record. */
data class UploadTarget(
    val folderId: String?,
    val description: String = "",
    val fileAccess: List<com.zillit.desktop.feature.drive.domain.DriveAccessEntry> = emptyList(),
)

/**
 * What the preview dialog needs from the machine: the bytes behind a
 * presigned address, and a PDF rasteriser. Common code has neither.
 */
interface DrivePreviewHost {
    suspend fun fetchBytes(url: String, maxBytes: Long): ZillitResult<ByteArray>

    suspend fun renderPdfPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<ByteArray>>

    object Unsupported : DrivePreviewHost {
        override suspend fun fetchBytes(url: String, maxBytes: Long): ZillitResult<ByteArray> =
            ZillitResult.Failure(ZillitError.Validation("Previews are not available in this build."))

        override suspend fun renderPdfPages(pdf: ByteArray, targetWidthPx: Int): ZillitResult<List<ByteArray>> =
            ZillitResult.Failure(ZillitError.Validation("PDF previews are not available in this build."))
    }
}

/** Adds or removes, whichever the current membership implies. */
internal fun <T> Set<T>.toggled(value: T): Set<T> =
    if (value in this) this - value else this + value

/** The inner tab a kind of item belongs to at the root. */
internal fun DriveItem.innerTab(): DriveInnerTab = if (isFolder) DriveInnerTab.Folders else DriveInnerTab.Files

/** Every selected row as a ref, for the bulk routes. */
internal fun List<DriveItem>.refs(): List<DriveRef> = map { it.ref }

private const val MODULE_LABEL = "Drive"
